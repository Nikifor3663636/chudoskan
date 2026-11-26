using System.Globalization;
using System.Net;
using System.Net.Http.Headers;
using System.Text;
using System.Text.Json;
using System.Text.Json.Nodes;
using ChudoScan.Print.Core;

namespace ChudoScan.Print;

/// <summary>Завдання друку для цього ПК (незалежно від сховища).</summary>
public sealed record PrintJob(
    string Id, string Status, string? ShopId, string? Printer, byte[]? Data, int Attempts,
    DateTimeOffset? At, DateTimeOffset? StartedAt, DateTimeOffset? DoneAt, string? Version);

/// <summary>Пульс ПК: що бачить картка ПК у застосунку.</summary>
public sealed record AgentBeat(string ShopId, string PcName, bool Online, string Version, int Queue, string LastError, IReadOnlyList<GodexPrinter> Printers);

/// <summary>
/// Де живуть підключення, пульс і черга: Firestore (як було) або Supabase (Postgres) — appsettings.json «backend».
/// Відмова прав — <see cref="PermissionDeniedException"/> (ПК не підключено або відключено).
/// </summary>
public interface IAgentStore
{
    /// <summary>Скільки чекати між опитуваннями черги, якщо ніхто не розбудив.</summary>
    TimeSpan PollEvery { get; }
    /// <summary>Чи обслуговує ПК чергу сам (Firestore) — у Supabase це робить pg_cron.</summary>
    bool SelfMaintain { get; }
    Task PrepareAsync(CancellationToken ct);
    /// <summary>Магазин, до якого підключено ПК; null — ще не підключено.</summary>
    Task<string?> DeviceShopAsync(CancellationToken ct);
    Task<bool> CreatePairingAsync(string code, string pcName, DateTimeOffset expires, CancellationToken ct);
    Task DeletePairingAsync(string code, CancellationToken ct);
    Task HeartbeatAsync(AgentBeat beat, CancellationToken ct);
    Task<PrintJob?> NextQueuedAsync(CancellationToken ct);
    /// <summary>Забрати завдання (лише якщо воно ще queued): printing, attempts+1. false — уже взято.</summary>
    Task<bool> ClaimAsync(PrintJob job, CancellationToken ct);
    Task FinishAsync(PrintJob job, string? error, CancellationToken ct);
    Task<IReadOnlyList<PrintJob>> AllJobsAsync(int limit, CancellationToken ct);
    Task RequeueAsync(PrintJob job, CancellationToken ct);
    Task FailAsync(PrintJob job, string error, CancellationToken ct);
    Task DeleteJobAsync(PrintJob job, CancellationToken ct);
    Task DisconnectAsync(CancellationToken ct);
    /// <summary>Будильник: нове завдання (Realtime). Firestore — ніколи.</summary>
    event Action? JobsChanged;
}

// ---------- Firestore (REST) ----------

public sealed class FirestoreAgentStore(FirestoreGateway fs, FirebaseAuthClient auth) : IAgentStore
{
    public TimeSpan PollEvery => TimeSpan.FromSeconds(5);
    public bool SelfMaintain => true;
#pragma warning disable CS0067 // у Firestore будильника немає — лише опитування
    public event Action? JobsChanged;
#pragma warning restore CS0067

    public Task PrepareAsync(CancellationToken ct) => auth.TokenAsync(ct);

    public async Task<string?> DeviceShopAsync(CancellationToken ct)
    {
        try { return (await fs.GetAsync($"devices/{auth.Uid}", ct))?.Str("shopId"); }
        catch (PermissionDeniedException) { return null; }
    }

    public async Task<bool> CreatePairingAsync(string code, string pcName, DateTimeOffset expires, CancellationToken ct)
    {
        try
        {
            return await fs.CreateAsync("devicePairing", code, new JsonObject
            {
                ["uid"] = FirestoreGateway.S(auth.Uid!), ["pcName"] = FirestoreGateway.S(pcName), ["expires"] = FirestoreGateway.T(expires),
            }, ct);
        }
        catch (PermissionDeniedException) { return false; } // код зайнятий іншим ПК
    }

    public Task DeletePairingAsync(string code, CancellationToken ct) => fs.DeleteAsync($"devicePairing/{code}", ct);

    public Task HeartbeatAsync(AgentBeat b, CancellationToken ct)
    {
        var printers = b.Printers.Select(p => FirestoreGateway.M(new JsonObject
        {
            ["name"] = FirestoreGateway.S(p.Name), ["isDefault"] = FirestoreGateway.B(p.IsDefault), ["kind"] = FirestoreGateway.S("label_40x24"),
            ["state"] = FirestoreGateway.S(p.State.ToString().ToLowerInvariant()), ["text"] = FirestoreGateway.S(p.Text),
        }));
        var fields = new JsonObject
        {
            ["shopId"] = FirestoreGateway.S(b.ShopId), ["pcName"] = FirestoreGateway.S(b.PcName), ["online"] = FirestoreGateway.B(b.Online),
            ["version"] = FirestoreGateway.S(b.Version), ["queue"] = FirestoreGateway.I(b.Queue), ["lastError"] = FirestoreGateway.S(b.LastError),
            ["printers"] = FirestoreGateway.A(printers),
        };
        return fs.SetWithServerTimeAsync($"agents/{auth.Uid}", fields, ["lastSeen"], ct);
    }

    public async Task<PrintJob?> NextQueuedAsync(CancellationToken ct) =>
        (await fs.JobsAsync(auth.Uid!, "queued", 1, ct)).Select(ToJob).FirstOrDefault();

    public Task<bool> ClaimAsync(PrintJob job, CancellationToken ct) =>
        fs.UpdateAsync($"printJobs/{job.Id}", new JsonObject { ["status"] = FirestoreGateway.S("printing") }, ["startedAt"], job.Version, ct,
            new Dictionary<string, long> { ["attempts"] = 1 });

    public async Task FinishAsync(PrintJob job, string? error, CancellationToken ct)
    {
        var fields = new JsonObject { ["status"] = FirestoreGateway.S(error == null ? "done" : "error") };
        if (error != null) fields["error"] = FirestoreGateway.S(error);
        await fs.UpdateAsync($"printJobs/{job.Id}", fields, ["doneAt"], null, ct);
    }

    public async Task<IReadOnlyList<PrintJob>> AllJobsAsync(int limit, CancellationToken ct) =>
        (await fs.JobsAsync(auth.Uid!, null, limit, ct)).Select(ToJob).ToList();

    public Task RequeueAsync(PrintJob job, CancellationToken ct) =>
        fs.UpdateAsync($"printJobs/{job.Id}", new JsonObject { ["status"] = FirestoreGateway.S("queued") }, [], job.Version, ct);

    public Task FailAsync(PrintJob job, string error, CancellationToken ct) =>
        fs.UpdateAsync($"printJobs/{job.Id}", new JsonObject { ["status"] = FirestoreGateway.S("error"), ["error"] = FirestoreGateway.S(error) }, ["doneAt"], job.Version, ct);

    public Task DeleteJobAsync(PrintJob job, CancellationToken ct) => fs.DeleteAsync($"printJobs/{job.Id}", ct);

    public async Task DisconnectAsync(CancellationToken ct)
    {
        try { await fs.DeleteAsync($"agents/{auth.Uid}", ct); } catch (Exception e) when (e is not OperationCanceledException) { Log.Error("agents", e); }
        await fs.DeleteAsync($"devices/{auth.Uid}", ct);
    }

    private static PrintJob ToJob(FsDoc d) => new(d.Id, d.Str("status") ?? "", d.Str("shopId"), d.Str("printer"), d.Bytes("data"),
        (int)(d.Int("attempts") ?? 0), d.Time("at"), d.Time("startedAt"), d.Time("doneAt"), d.UpdateTime);
}

// ---------- Supabase (PostgREST + Realtime) ----------

/// <summary>
/// Supabase з Firebase ID token (Third-Party Auth). Права — RLS: ПК бачить лише свої devices / agents / print_jobs.
/// Черга: Realtime будить одразу, резервне опитування — кожні 15 с. Завислі й старі завдання прибирає pg_cron.
/// </summary>
public sealed class SupabaseAgentStore : IAgentStore, IDisposable
{
    private readonly HttpClient _http;
    private readonly FirebaseAuthClient _auth;
    private readonly AppSettings _settings;
    private RealtimeWatch? _realtime;

    public SupabaseAgentStore(HttpClient http, FirebaseAuthClient auth, AppSettings settings)
    {
        _http = http; _auth = auth; _settings = settings;
    }

    public TimeSpan PollEvery => TimeSpan.FromSeconds(15);
    public bool SelfMaintain => false;
    public event Action? JobsChanged;

    private string Rest => $"{_settings.SupabaseUrl.TrimEnd('/')}/rest/v1";

    /// <summary>Токен з role = authenticated (ensure-role), потім Realtime на свою чергу.</summary>
    public async Task PrepareAsync(CancellationToken ct)
    {
        var token = await _auth.TokenAsync(ct);
        if (!SupabaseWire.HasRole(token))
        {
            using var req = new HttpRequestMessage(HttpMethod.Post, $"{_settings.SupabaseUrl.TrimEnd('/')}/functions/v1/ensure-role");
            req.Headers.Authorization = new AuthenticationHeaderValue("Bearer", token);
            using var r = await _http.SendAsync(req, ct);
            if (!r.IsSuccessStatusCode) throw new HttpRequestException($"ensure-role: HTTP {(int)r.StatusCode}");
            await _auth.ForceRefreshAsync(ct);
            Log.Info("Роль у токені встановлено");
        }
        if (_realtime == null)
        {
            _realtime = new RealtimeWatch(_settings, () => _auth.TokenAsync(CancellationToken.None), _auth.Uid!);
            _realtime.Changed += () => JobsChanged?.Invoke();
            _realtime.Start();
        }
    }

    public async Task<string?> DeviceShopAsync(CancellationToken ct)
    {
        var rows = await GetAsync($"devices?uid=eq.{Esc(_auth.Uid!)}&select=shop_id", ct);
        return rows.Count == 0 ? null : rows[0]?["shop_id"]?.GetValue<string>();
    }

    public async Task<bool> CreatePairingAsync(string code, string pcName, DateTimeOffset expires, CancellationToken ct)
    {
        var body = new JsonObject { ["code"] = code, ["uid"] = _auth.Uid, ["pc_name"] = pcName.Length > 100 ? pcName[..100] : pcName, ["expires_at"] = Iso(expires) };
        using var r = await SendAsync(HttpMethod.Post, "device_pairing", body, ct, allow: HttpStatusCode.Conflict);
        return r.StatusCode != HttpStatusCode.Conflict;
    }

    public async Task DeletePairingAsync(string code, CancellationToken ct)
    {
        using var _ = await SendAsync(HttpMethod.Delete, $"device_pairing?code=eq.{Esc(code)}", null, ct);
    }

    public async Task HeartbeatAsync(AgentBeat b, CancellationToken ct)
    {
        var printers = new JsonArray(b.Printers.Select(p => (JsonNode)new JsonObject
        {
            ["name"] = p.Name, ["isDefault"] = p.IsDefault, ["kind"] = "label_40x24",
            ["state"] = p.State.ToString().ToLowerInvariant(), ["text"] = p.Text,
        }).ToArray());
        var body = new JsonObject
        {
            ["uid"] = _auth.Uid, ["shop_id"] = b.ShopId, ["pc_name"] = b.PcName, ["online"] = b.Online, ["version"] = b.Version,
            ["queue"] = b.Queue, ["last_error"] = b.LastError.Length > 500 ? b.LastError[..500] : b.LastError, ["printers"] = printers,
        };
        // last_seen ставить сервер (тригер agents_touch).
        using var _ = await SendAsync(HttpMethod.Post, "agents?on_conflict=uid", body, ct, prefer: "resolution=merge-duplicates,return=minimal");
    }

    public async Task<PrintJob?> NextQueuedAsync(CancellationToken ct)
    {
        var rows = await GetAsync($"print_jobs?agent_id=eq.{Esc(_auth.Uid!)}&status=eq.queued&order=created_at.asc&limit=1&select=*", ct);
        return rows.Count == 0 ? null : ToJob(rows[0]!.AsObject());
    }

    /// <summary>UPDATE … SET status='printing' WHERE id=? AND status='queued' RETURNING — друге вікно чи ПК не візьме те саме.</summary>
    public async Task<bool> ClaimAsync(PrintJob job, CancellationToken ct)
    {
        var body = new JsonObject { ["status"] = "printing", ["attempts"] = job.Attempts + 1 };
        using var r = await SendAsync(HttpMethod.Patch, $"print_jobs?id=eq.{Esc(job.Id)}&status=eq.queued&select=id", body, ct, prefer: "return=representation");
        return JsonNode.Parse(await r.Content.ReadAsStringAsync(ct)) is JsonArray { Count: > 0 };
    }

    public async Task FinishAsync(PrintJob job, string? error, CancellationToken ct)
    {
        var body = new JsonObject { ["status"] = error == null ? "done" : "error" };
        if (error != null) body["error"] = error;
        using var _ = await SendAsync(HttpMethod.Patch, $"print_jobs?id=eq.{Esc(job.Id)}", body, ct);
    }

    public async Task<IReadOnlyList<PrintJob>> AllJobsAsync(int limit, CancellationToken ct) =>
        (await GetAsync($"print_jobs?agent_id=eq.{Esc(_auth.Uid!)}&select=id,status,shop_id,printer,attempts,created_at,started_at,done_at&limit={limit}", ct))
        .Select(n => ToJob(n!.AsObject())).ToList();

    // Черга обслуговується на сервері (pg_cron app.print_jobs_maintain) — тут нічого не робимо.
    public Task RequeueAsync(PrintJob job, CancellationToken ct) => Task.CompletedTask;
    public Task FailAsync(PrintJob job, string error, CancellationToken ct) => Task.CompletedTask;
    public Task DeleteJobAsync(PrintJob job, CancellationToken ct) => Task.CompletedTask;

    public async Task DisconnectAsync(CancellationToken ct)
    {
        try { using var _ = await SendAsync(HttpMethod.Delete, $"agents?uid=eq.{Esc(_auth.Uid!)}", null, ct); }
        catch (Exception e) when (e is not OperationCanceledException) { Log.Error("agents", e); }
        using var __ = await SendAsync(HttpMethod.Delete, $"devices?uid=eq.{Esc(_auth.Uid!)}", null, ct);
    }

    // ---------- HTTP ----------

    private async Task<JsonArray> GetAsync(string path, CancellationToken ct)
    {
        using var r = await SendAsync(HttpMethod.Get, path, null, ct);
        return JsonNode.Parse(await r.Content.ReadAsStringAsync(ct)) as JsonArray ?? [];
    }

    private async Task<HttpResponseMessage> SendAsync(HttpMethod method, string path, JsonObject? body, CancellationToken ct,
        string? prefer = null, HttpStatusCode? allow = null)
    {
        using var timeout = CancellationTokenSource.CreateLinkedTokenSource(ct);
        timeout.CancelAfter(FirestoreGateway.Timeout);
        var token = await _auth.TokenAsync(timeout.Token);
        var req = new HttpRequestMessage(method, $"{Rest}/{path}");
        req.Headers.Authorization = new AuthenticationHeaderValue("Bearer", token);
        req.Headers.Add("apikey", _settings.SupabaseKey);
        if (prefer != null) req.Headers.Add("Prefer", prefer);
        if (body != null) req.Content = new StringContent(body.ToJsonString(), Encoding.UTF8, "application/json");
        var r = await _http.SendAsync(req, timeout.Token);
        if (r.IsSuccessStatusCode || r.StatusCode == allow) return r;
        var text = await r.Content.ReadAsStringAsync(ct);
        var status = (int)r.StatusCode;
        r.Dispose();
        // 401/403 і 42501 (RLS) — ПК не підключено або відключено.
        if (status is 401 or 403 || text.Contains("42501", StringComparison.Ordinal))
            throw new PermissionDeniedException("Немає доступу (ПК не підключено до магазину)");
        throw new HttpRequestException($"Supabase: HTTP {status}");
    }

    private static string Esc(string v) => Uri.EscapeDataString(v);
    private static string Iso(DateTimeOffset t) => t.UtcDateTime.ToString("yyyy-MM-dd'T'HH:mm:ss.fff'Z'", CultureInfo.InvariantCulture);

    private static DateTimeOffset? Time(JsonObject o, string f) =>
        o[f]?.GetValue<string>() is { } s ? DateTimeOffset.Parse(s, CultureInfo.InvariantCulture) : null;

    private static PrintJob ToJob(JsonObject o) => new(
        o["id"]!.GetValue<string>(), o["status"]?.GetValue<string>() ?? "", o["shop_id"]?.GetValue<string>(), o["printer"]?.GetValue<string>(),
        SupabaseWire.Bytea(o["data"]?.GetValue<string>()), o["attempts"]?.GetValue<int>() ?? 0,
        Time(o, "created_at"), Time(o, "started_at"), Time(o, "done_at"), null);

    public void Dispose() => _realtime?.Dispose();
}
