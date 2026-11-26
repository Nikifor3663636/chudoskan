using System.Text.Json;
using System.Text.Json.Nodes;

namespace ChudoScan.Print.Core;

/// <summary>Чисті перетворення для Supabase (PostgREST, Realtime, Firebase ID token) — без мережі, під тести.</summary>
public static class SupabaseWire
{
    /// <summary>bytea з PostgREST — «\x0102…»; інше — null.</summary>
    public static byte[]? Bytea(string? hex)
    {
        if (hex is not { Length: >= 2 } || !hex.StartsWith("\\x", StringComparison.Ordinal)) return null;
        try { return Convert.FromHexString(hex[2..]); }
        catch (FormatException) { return null; }
    }

    /// <summary>Чи є в Firebase ID token claim role = "authenticated" (підпис не перевіряємо — це робить сервер).</summary>
    public static bool HasRole(string jwt)
    {
        try
        {
            var part = jwt.Split('.')[1].Replace('-', '+').Replace('_', '/');
            part = part.PadRight(part.Length + (4 - part.Length % 4) % 4, '=');
            using var doc = JsonDocument.Parse(Convert.FromBase64String(part));
            return doc.RootElement.TryGetProperty("role", out var r) && r.ValueKind == JsonValueKind.String && r.GetString() == "authenticated";
        }
        catch (Exception e) when (e is FormatException or JsonException or IndexOutOfRangeException)
        {
            return false;
        }
    }

    /// <summary>phx_join: зміни print_jobs лише цього ПК (RLS однаково не покаже чужі).</summary>
    public static JsonObject JoinMessage(string topic, string uid, string jwt, string reference) => new()
    {
        ["topic"] = topic,
        ["event"] = "phx_join",
        ["payload"] = new JsonObject
        {
            ["config"] = new JsonObject
            {
                ["postgres_changes"] = new JsonArray(new JsonObject
                {
                    ["event"] = "*", ["schema"] = "public", ["table"] = "print_jobs", ["filter"] = $"agent_id=eq.{uid}",
                }),
            },
            ["access_token"] = jwt,
        },
        ["ref"] = reference,
        ["join_ref"] = reference,
    };

    /// <summary>Повідомлення Realtime — зміна в таблиці (а не відповідь чи heartbeat).</summary>
    public static bool IsChange(string message)
    {
        try { return JsonNode.Parse(message)?["event"]?.GetValue<string>() is "postgres_changes" or "INSERT" or "UPDATE"; }
        catch (JsonException) { return false; }
    }
}
