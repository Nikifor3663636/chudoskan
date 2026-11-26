using System.Net.WebSockets;
using System.Text;
using System.Text.Json.Nodes;
using ChudoScan.Print.Core;

namespace ChudoScan.Print;

/// <summary>
/// Supabase Realtime (протокол Phoenix): підписка на зміни print_jobs цього ПК → <see cref="Changed"/>.
/// Лише будильник — дані все одно читаються запитом (і RLS). Обрив — перепідключення; токен оновлюється кожні 5 хв.
/// </summary>
public sealed class RealtimeWatch(AppSettings settings, Func<Task<string>> token, string uid) : IDisposable
{
    private readonly CancellationTokenSource _stop = new();
    private Task? _loop;
    private int _ref;

    public event Action? Changed;

    public void Start() => _loop = Task.Run(() => RunAsync(_stop.Token));

    private async Task RunAsync(CancellationToken ct)
    {
        var backoff = TimeSpan.FromSeconds(2);
        while (!ct.IsCancellationRequested)
        {
            try
            {
                await SessionAsync(ct);
                backoff = TimeSpan.FromSeconds(2);
            }
            catch (OperationCanceledException) when (ct.IsCancellationRequested)
            {
                return;
            }
            catch (Exception e)
            {
                Log.Error("Realtime: обрив, перепідключаюся", e);
            }
            try { await Task.Delay(backoff, ct); } catch (OperationCanceledException) { return; }
            backoff = TimeSpan.FromSeconds(Math.Min(60, backoff.TotalSeconds * 2));
        }
    }

    private async Task SessionAsync(CancellationToken ct)
    {
        using var ws = new ClientWebSocket();
        var host = settings.SupabaseUrl.TrimEnd('/').Replace("https://", "wss://", StringComparison.Ordinal);
        await ws.ConnectAsync(new Uri($"{host}/realtime/v1/websocket?apikey={Uri.EscapeDataString(settings.SupabaseKey)}&vsn=1.0.0"), ct);
        var topic = $"realtime:print_jobs-{uid}";
        await SendAsync(ws, SupabaseWire.JoinMessage(topic, uid, await token(), Next()), ct);
        Changed?.Invoke(); // після (пере)підключення — перевірити чергу

        using var session = CancellationTokenSource.CreateLinkedTokenSource(ct);
        var pings = Task.Run(async () =>
        {
            var n = 0;
            while (!session.Token.IsCancellationRequested)
            {
                await Task.Delay(TimeSpan.FromSeconds(25), session.Token);
                await SendAsync(ws, new JsonObject { ["topic"] = "phoenix", ["event"] = "heartbeat", ["payload"] = new JsonObject(), ["ref"] = Next() }, session.Token);
                if (++n % 12 == 0) // ~5 хв: свіжий токен у канал
                    await SendAsync(ws, new JsonObject { ["topic"] = topic, ["event"] = "access_token", ["payload"] = new JsonObject { ["access_token"] = await token() }, ["ref"] = Next() }, session.Token);
            }
        }, session.Token);

        var buf = new byte[16 * 1024];
        var sb = new StringBuilder();
        try
        {
            while (ws.State == WebSocketState.Open && !ct.IsCancellationRequested)
            {
                var r = await ws.ReceiveAsync(buf, ct);
                if (r.MessageType == WebSocketMessageType.Close) break;
                sb.Append(Encoding.UTF8.GetString(buf, 0, r.Count));
                if (!r.EndOfMessage) continue;
                var msg = sb.ToString();
                sb.Clear();
                if (SupabaseWire.IsChange(msg)) Changed?.Invoke();
            }
        }
        finally
        {
            session.Cancel();
            try { await pings; } catch { /* зупинка */ }
        }
    }

    private string Next() => Interlocked.Increment(ref _ref).ToString(System.Globalization.CultureInfo.InvariantCulture);

    private static Task SendAsync(ClientWebSocket ws, JsonObject msg, CancellationToken ct) =>
        ws.SendAsync(Encoding.UTF8.GetBytes(msg.ToJsonString()), WebSocketMessageType.Text, true, ct);

    public void Dispose()
    {
        _stop.Cancel();
        try { _loop?.Wait(TimeSpan.FromSeconds(2)); } catch { /* зупинка */ }
        _stop.Dispose();
    }
}
