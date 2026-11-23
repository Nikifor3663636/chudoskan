using System.Text.Json.Nodes;
using ChudoScan.Print.Core;

namespace ChudoScan.Print;

/// <summary>Що показує вікно й значок.</summary>
public sealed record AgentStatus(
    OverallState State,
    string Text,
    bool Connected,
    bool Paired,
    string? ShopId,
    string? Code,
    DateTimeOffset? CodeExpires,
    IReadOnlyList<GodexPrinter> Godex,
    int Queue,
    int PrintedToday,
    string? LastError);

/// <summary>
/// Підключення ПК до магазину (devicePairing → devices/{uid}), пульс agents/{uid} кожні 60 с, друк printJobs
/// по одному (опитування кожні 5 с), обслуговування черги кожні 5 хв. Вхід — анонімний користувач Firebase.
/// </summary>
public sealed class AgentService : IDisposable
{
    private static readonly TimeSpan PollEvery = TimeSpan.FromSeconds(5);
    private static readonly TimeSpan HeartbeatEvery = TimeSpan.FromSeconds(60);
    private static readonly TimeSpan MaintenanceEvery = TimeSpan.FromMinutes(5);

    private readonly FirebaseAuthClient _auth;
    private readonly FirestoreGateway _fs;
    private readonly CancellationTokenSource _stop = new();
    private readonly SemaphoreSlim _printLock = new(1, 1);
    private Task? _loop;
    private CancellationTokenSource _wake = new();

    private bool _connected;
    private string? _shopId;
    private string? _code;
    private DateTimeOffset? _codeExpires;
    private IReadOnlyList<GodexPrinter> _godex = [];
    private int _queue;
    private int _printedToday;
    private DateOnly _today = DateOnly.FromDateTime(DateTime.Now);
    private string? _lastError;

    public string PcName { get; } = Environment.MachineName;
    public static string Version { get; } = typeof(AgentService).Assembly.GetName().Version?.ToString(3) ?? "1.0.0";
    public event Action<AgentStatus>? StatusChanged;
    public AgentStatus Status { get; private set; } = new(OverallState.Offline, "Підключаюся…", false, false, null, null, null, [], 0, 0, null);

    public AgentService(AppSettings settings)
    {
        var http = new HttpClient { Timeout = TimeSpan.FromSeconds(30) };
        http.DefaultRequestHeaders.UserAgent.ParseAdd($"ChudoScanPrint/{Version}");
        _auth = new FirebaseAuthClient(http, settings);
        _fs = new FirestoreGateway(http, _auth, settings);
    }

    public void Start() => _loop = Task.Run(() => RunAsync(_stop.Token));

    /// <summary>«Спробувати ще»: не чекати наступного циклу.</summary>
    public void Retry()
    {
        _lastError = null;
        _wake.Cancel();
    }

    // ---------- Головний цикл ----------

    private async Task RunAsync(CancellationToken ct)
    {
        Log.Info($"Старт, версія {Version}, ПК {PcName}");
        while (!ct.IsCancellationRequested)
        {
            try
            {
                RefreshPrinters();
                await _auth.TokenAsync(ct);
                var device = await DeviceAsync(ct);
                SetConnected(true);
                if (device == null) await PairAsync(ct);
                else await WorkAsync(device, ct);
            }
            catch (OperationCanceledException) when (ct.IsCancellationRequested)
            {
                break;
            }
            catch (PermissionDeniedException)
            {
                // devices/{uid} видалили або правила не пускають — знову код.
                Log.Info("Доступ до магазину закрито — показуємо код підключення");
                _shopId = null;
                Publish();
            }
            catch (Exception e) when (e is HttpRequestException or TaskCanceledException or TimeoutException)
            {
                Log.Error("Немає зв'язку", e);
                SetConnected(false);
                await SleepAsync(TimeSpan.FromSeconds(15), ct);
            }
            catch (Exception e)
            {
                Log.Error("Помилка", e);
                _lastError = e.Message;
                Publish();
                await SleepAsync(TimeSpan.FromSeconds(15), ct);
            }
        }
    }

    private async Task<FsDoc?> DeviceAsync(CancellationToken ct)
    {
        try
        {
            return await _fs.GetAsync($"devices/{_auth.Uid}", ct);
        }
        catch (PermissionDeniedException)
        {
            return null; // ще не підключено
        }
    }

    /// <summary>Код підключення: новий кожні 15 хв, поки адмін не введе його в застосунку.</summary>
    private async Task PairAsync(CancellationToken ct)
    {
        _shopId = null;
        while (!ct.IsCancellationRequested)
        {
            var now = DateTimeOffset.UtcNow;
            if (_code == null || _codeExpires == null || PairingCode.IsExpired(_codeExpires.Value, now)) await NewCodeAsync(ct);
            Publish();
            await SleepAsync(PollEvery, ct);
            RefreshPrinters();
            var device = await DeviceAsync(ct);
            SetConnected(true);
            if (device != null)
            {
                if (_code != null) await TryAsync(() => _fs.DeleteAsync($"devicePairing/{_code}", ct));
                _code = null;
                _codeExpires = null;
                Log.Info($"ПК підключено до магазину {device.Str("shopId")}");
                return;
            }
        }
    }

    private async Task NewCodeAsync(CancellationToken ct)
    {
        if (_code != null) await TryAsync(() => _fs.DeleteAsync($"devicePairing/{_code}", ct));
        for (var attempt = 0; attempt < 5; attempt++)
        {
            var code = PairingCode.New();
            var expires = PairingCode.ExpiresAt(DateTimeOffset.UtcNow);
            var fields = new JsonObject
            {
                ["uid"] = FirestoreGateway.S(_auth.Uid!),
                ["pcName"] = FirestoreGateway.S(PcName),
                ["expires"] = FirestoreGateway.T(expires),
            };
            bool created;
            try
            {
                created = await _fs.CreateAsync("devicePairing", code, fields, ct);
            }
            catch (PermissionDeniedException)
            {
                created = false; // такий код уже зайнятий іншим ПК
            }
            if (!created) continue;
            _code = code;
            _codeExpires = expires;
            Log.Info("Новий код підключення");
            return;
        }
        throw new InvalidOperationException("Не вдалося створити код підключення");
    }

    /// <summary>Підключено: пульс, друк, обслуговування черги.</summary>
    private async Task WorkAsync(FsDoc device, CancellationToken ct)
    {
        _shopId = device.Str("shopId");
        var nextBeat = DateTimeOffset.MinValue;
        var nextMaintenance = DateTimeOffset.MinValue;
        while (!ct.IsCancellationRequested)
        {
            var now = DateTimeOffset.UtcNow;
            if (now >= nextBeat)
            {
                RefreshPrinters();
                if (await DeviceAsync(ct) == null)
                {
                    Log.Info("devices/{uid} видалено — ПК відключено від магазину");
                    _shopId = null;
                    return;
                }
                await HeartbeatAsync(true, ct);
                nextBeat = now + HeartbeatEvery;
            }
            if (now >= nextMaintenance)
            {
                await MaintainAsync(ct);
                nextMaintenance = now + MaintenanceEvery;
            }
            var jobs = await _fs.JobsAsync(_auth.Uid!, "queued", 1, ct);
            SetConnected(true);
            if (jobs.Count > 0) await PrintJobAsync(jobs[0], ct);
            else await SleepAsync(PollEvery, ct);
        }
    }

    private async Task HeartbeatAsync(bool online, CancellationToken ct)
    {
        // Стан Godex для картки ПК у застосунку: state (ok / offline / error) і текст українською.
        var printers = _godex.Select(p => FirestoreGateway.M(new JsonObject
        {
            ["name"] = FirestoreGateway.S(p.Name),
            ["isDefault"] = FirestoreGateway.B(p.IsDefault),
            ["kind"] = FirestoreGateway.S("label_40x24"),
            ["state"] = FirestoreGateway.S(p.State.ToString().ToLowerInvariant()),
            ["text"] = FirestoreGateway.S(p.Text),
        }));
        var fields = new JsonObject
        {
            ["shopId"] = FirestoreGateway.S(_shopId ?? ""),
            ["pcName"] = FirestoreGateway.S(PcName),
            ["online"] = FirestoreGateway.B(online),
            ["version"] = FirestoreGateway.S(Version),
            ["queue"] = FirestoreGateway.I(_queue),
            ["lastError"] = FirestoreGateway.S(_lastError ?? ""),
            ["printers"] = FirestoreGateway.A(printers),
        };
        await _fs.SetWithServerTimeAsync($"agents/{_auth.Uid}", fields, ["lastSeen"], ct);
    }

    /// <summary>Одне завдання: забрати (printing, startedAt, attempts+1), надрукувати RAW, done / error.</summary>
    private async Task PrintJobAsync(FsDoc job, CancellationToken ct)
    {
        var path = $"printJobs/{job.Id}";
        var claimed = await _fs.UpdateAsync(path, new JsonObject { ["status"] = FirestoreGateway.S("printing") }, ["startedAt"], job.UpdateTime, ct,
            new Dictionary<string, long> { ["attempts"] = 1 });
        if (!claimed) return; // уже взято
        string? error = null;
        if (job.Str("shopId") != _shopId)
        {
            error = "Завдання іншого магазину";
        }
        else
        {
            var target = PickPrinter(job.Str("printer"));
            var data = job.Bytes("data");
            if (target == null) error = "Godex не знайдено. Перевірте кабель і драйвер.";
            else if (data == null || data.Length == 0) error = "Порожнє завдання";
            else
            {
                await _printLock.WaitAsync(ct);
                try
                {
                    await RawPrinter.SendAsync(target, data, ct);
                }
                catch (Exception e) when (e is IOException or TimeoutException)
                {
                    error = $"ПК {PcName}: {e.Message}";
                }
                finally
                {
                    _printLock.Release();
                }
            }
        }
        var fields = new JsonObject { ["status"] = FirestoreGateway.S(error == null ? "done" : "error") };
        if (error != null) fields["error"] = FirestoreGateway.S(error.Length > 500 ? error[..500] : error);
        await _fs.UpdateAsync(path, fields, ["doneAt"], null, ct);
        if (error == null)
        {
            CountPrinted();
            _lastError = null;
            Log.Info($"Надруковано завдання {job.Id}");
        }
        else
        {
            _lastError = error;
            Log.Error($"Завдання {job.Id}: {error}");
        }
        Publish();
    }

    /// <summary>Кожні 5 хв: завислі — знову в чергу або error; старі done/error — видалити.</summary>
    private async Task MaintainAsync(CancellationToken ct)
    {
        var all = await _fs.JobsAsync(_auth.Uid!, null, 200, ct);
        var now = DateTimeOffset.UtcNow;
        _queue = all.Count(j => j.Str("status") == "queued");
        foreach (var j in all)
        {
            var info = new JobInfo(j.Id, j.Str("status") ?? "", (int)(j.Int("attempts") ?? 0), j.Time("at"), j.Time("startedAt"), j.Time("doneAt"));
            switch (JobMaintenance.Decide(info, now))
            {
                case JobFix.Requeue:
                    await _fs.UpdateAsync($"printJobs/{j.Id}", new JsonObject { ["status"] = FirestoreGateway.S("queued") }, [], j.UpdateTime, ct);
                    break;
                case JobFix.Fail:
                    await _fs.UpdateAsync($"printJobs/{j.Id}", new JsonObject
                    {
                        ["status"] = FirestoreGateway.S("error"), ["error"] = FirestoreGateway.S(JobMaintenance.StuckError),
                    }, ["doneAt"], j.UpdateTime, ct);
                    break;
                case JobFix.Delete:
                    await TryAsync(() => _fs.DeleteAsync($"printJobs/{j.Id}", ct));
                    break;
            }
        }
        Publish();
    }

    // ---------- Дії з вікна ----------

    /// <summary>«Тест етикетки»: EZPL прямо на Godex цього ПК, без Firestore.</summary>
    public async Task<string> TestLabelAsync()
    {
        RefreshPrinters();
        var target = PickPrinter(null) ?? throw new InvalidOperationException("Godex не знайдено. Перевірте кабель і драйвер.");
        await _printLock.WaitAsync();
        try
        {
            await RawPrinter.SendAsync(target, TestLabel.Bytes(DateTimeOffset.Now), CancellationToken.None);
        }
        finally
        {
            _printLock.Release();
        }
        Log.Info($"Тестова етикетка на «{target}»");
        return $"Тестову етикетку надіслано на «{target}»";
    }

    /// <summary>«Відключити від магазину»: прибрати agents/{uid} і devices/{uid} — далі знову код.</summary>
    public async Task DisconnectAsync()
    {
        using var cts = new CancellationTokenSource(FirestoreGateway.Timeout);
        await TryAsync(() => _fs.DeleteAsync($"agents/{_auth.Uid}", cts.Token));
        await _fs.DeleteAsync($"devices/{_auth.Uid}", cts.Token);
        Log.Info("ПК відключено від магазину користувачем");
        _shopId = null;
        Retry();
    }

    // ---------- Допоміжне ----------

    private string? PickPrinter(string? wanted)
    {
        if (wanted != null && _godex.Any(g => g.Name.Equals(wanted, StringComparison.OrdinalIgnoreCase))) return wanted;
        return _godex.FirstOrDefault()?.Name;
    }

    private void RefreshPrinters() => _godex = Printers.Godex(WmiPrinters.Read());

    private void CountPrinted()
    {
        var today = DateOnly.FromDateTime(DateTime.Now);
        if (today != _today) { _today = today; _printedToday = 0; }
        _printedToday++;
    }

    private void SetConnected(bool v)
    {
        if (_connected == v) return;
        _connected = v;
        Publish();
    }

    private void Publish()
    {
        var paired = _shopId != null;
        var (state, text) = Overall.Of(_connected, paired, _godex, _lastError);
        if (DateOnly.FromDateTime(DateTime.Now) != _today) { _today = DateOnly.FromDateTime(DateTime.Now); _printedToday = 0; }
        Status = new AgentStatus(state, text, _connected, paired, _shopId, paired ? null : _code, paired ? null : _codeExpires, _godex, _queue, _printedToday, _lastError);
        StatusChanged?.Invoke(Status);
    }

    private async Task SleepAsync(TimeSpan delay, CancellationToken ct)
    {
        using var linked = CancellationTokenSource.CreateLinkedTokenSource(ct, _wake.Token);
        try
        {
            await Task.Delay(delay, linked.Token);
        }
        catch (OperationCanceledException) when (!ct.IsCancellationRequested)
        {
            _wake.Dispose();
            _wake = new CancellationTokenSource(); // «Спробувати ще»
        }
    }

    private static async Task TryAsync(Func<Task> action)
    {
        try { await action(); }
        catch (Exception e) when (e is not OperationCanceledException) { Log.Error("Необов'язкова дія не вдалася", e); }
    }

    public void Dispose()
    {
        try
        {
            using var cts = new CancellationTokenSource(TimeSpan.FromSeconds(5));
            if (_shopId != null) HeartbeatAsync(false, cts.Token).Wait(cts.Token);
        }
        catch
        {
            // вихід — без помилок
        }
        _stop.Cancel();
        try { _loop?.Wait(TimeSpan.FromSeconds(3)); } catch { /* зупинка */ }
        _stop.Dispose();
        _wake.Dispose();
        _printLock.Dispose();
    }
}
