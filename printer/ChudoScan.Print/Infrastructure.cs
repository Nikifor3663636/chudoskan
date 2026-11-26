using System.Security.Cryptography;
using System.Text;
using System.Text.Json;

namespace ChudoScan.Print;

/// <summary>
/// appsettings.json поруч з програмою (без секретів): Web API key і projectId Firebase (вхід),
/// backend = firestore | supabase (де дані), URL і publishable key Supabase.
/// </summary>
public sealed record AppSettings(string FirebaseApiKey, string ProjectId, string Backend = "supabase",
    string SupabaseUrl = AppSettings.DefaultSupabaseUrl, string SupabaseKey = AppSettings.DefaultSupabaseKey)
{
    public const string DefaultSupabaseUrl = "https://wbboztsxzuxfocdqjyzk.supabase.co";
    public const string DefaultSupabaseKey = "sb_publishable_pXZn08SVg5og-I9_PuX5LA_qdTmydyk";

    public bool IsSupabase => Backend.Equals("supabase", StringComparison.OrdinalIgnoreCase);

    public static AppSettings Load() => Parse(Path.Combine(AppContext.BaseDirectory, "appsettings.json"));

    public static AppSettings Parse(string path)
    {
        if (!File.Exists(path)) return new AppSettings("", "chudoostriv");
        using var doc = JsonDocument.Parse(File.ReadAllText(path));
        string Str(string name, string fallback) =>
            doc.RootElement.TryGetProperty(name, out var v) && v.ValueKind == JsonValueKind.String && !string.IsNullOrWhiteSpace(v.GetString()) ? v.GetString()! : fallback;
        return new AppSettings(Str("firebaseApiKey", ""), Str("projectId", "chudoostriv"), Str("backend", "supabase").Trim().ToLowerInvariant(),
            Str("supabaseUrl", DefaultSupabaseUrl), Str("supabaseKey", DefaultSupabaseKey));
    }
}

/// <summary>Папка програми: %LOCALAPPDATA%\ChudoScan.</summary>
public static class AppPaths
{
    public static string Root { get; } = Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData), "ChudoScan");
    public static string Logs => Path.Combine(Root, "logs");
    public static string Auth => Path.Combine(Root, "auth.dat");
}

/// <summary>Журнал: %LOCALAPPDATA%\ChudoScan\logs, до 5 файлів по 5 МБ. Токени сюди не пишемо.</summary>
public static class Log
{
    private const long MaxBytes = 5 * 1024 * 1024;
    private const int Files = 5;
    private static readonly object Gate = new();
    public static string Current => Path.Combine(AppPaths.Logs, "chudoscan.log");

    public static void Info(string message) => Write("INFO", message);
    public static void Error(string message, Exception? e = null) => Write("ERROR", e == null ? message : $"{message}: {e.GetType().Name}: {e.Message}");

    private static void Write(string level, string message)
    {
        try
        {
            lock (Gate)
            {
                Directory.CreateDirectory(AppPaths.Logs);
                var info = new FileInfo(Current);
                if (info.Exists && info.Length > MaxBytes) Rotate();
                File.AppendAllText(Current, $"{DateTime.Now:yyyy-MM-dd HH:mm:ss} {level} {message}{Environment.NewLine}", Encoding.UTF8);
            }
        }
        catch
        {
            // Журнал не повинен ламати друк.
        }
    }

    private static void Rotate()
    {
        for (var i = Files - 1; i >= 1; i--)
        {
            var from = i == 1 ? Current : $"{Current}.{i - 1}";
            var to = $"{Current}.{i}";
            if (!File.Exists(from)) continue;
            File.Delete(to);
            File.Move(from, to);
        }
    }
}

/// <summary>Збережений вхід: refreshToken і uid під DPAPI (лише цей користувач Windows).</summary>
public sealed record StoredAuth(string Uid, string RefreshToken)
{
    public static StoredAuth? Load()
    {
        try
        {
            if (!File.Exists(AppPaths.Auth)) return null;
            var plain = ProtectedData.Unprotect(File.ReadAllBytes(AppPaths.Auth), null, DataProtectionScope.CurrentUser);
            return JsonSerializer.Deserialize<StoredAuth>(plain);
        }
        catch (Exception e)
        {
            Log.Error("Не вдалося прочитати збережений вхід", e);
            return null;
        }
    }

    public void Save()
    {
        Directory.CreateDirectory(AppPaths.Root);
        var data = ProtectedData.Protect(JsonSerializer.SerializeToUtf8Bytes(this), null, DataProtectionScope.CurrentUser);
        File.WriteAllBytes(AppPaths.Auth, data);
    }
}

/// <summary>Вхід: анонімний користувач Firebase Auth (REST), без жодних ключів у програмі.</summary>
public sealed class FirebaseAuthClient(HttpClient http, AppSettings settings)
{
    private StoredAuth? _auth = StoredAuth.Load();
    private string? _idToken;
    private DateTimeOffset _expires = DateTimeOffset.MinValue;
    private readonly SemaphoreSlim _lock = new(1, 1);

    public string? Uid => _auth?.Uid;

    /// <summary>ID-токен; оновлюємо за 5 хв до закінчення. Немає входу — анонімна реєстрація.</summary>
    public async Task<string> TokenAsync(CancellationToken ct)
    {
        await _lock.WaitAsync(ct);
        try
        {
            if (_idToken != null && DateTimeOffset.UtcNow < _expires - TimeSpan.FromMinutes(5)) return _idToken;
            if (_auth == null) await SignUpAsync(ct);
            else await RefreshAsync(ct);
            return _idToken!;
        }
        finally
        {
            _lock.Release();
        }
    }

    /// <summary>Новий ID-токен зараз (після ensure-role — щоб у ньому з'явився claim role).</summary>
    public async Task ForceRefreshAsync(CancellationToken ct)
    {
        await _lock.WaitAsync(ct);
        try
        {
            if (_auth == null) await SignUpAsync(ct);
            else await RefreshAsync(ct);
        }
        finally
        {
            _lock.Release();
        }
    }

    private async Task SignUpAsync(CancellationToken ct)
    {
        EnsureKey();
        using var r = await http.PostAsync($"https://identitytoolkit.googleapis.com/v1/accounts:signUp?key={settings.FirebaseApiKey}",
            new StringContent("{\"returnSecureToken\":true}", Encoding.UTF8, "application/json"), ct);
        using var doc = await ReadAsync(r, "Вхід", ct);
        var root = doc.RootElement;
        _auth = new StoredAuth(root.GetProperty("localId").GetString()!, root.GetProperty("refreshToken").GetString()!);
        _auth.Save();
        SetToken(root.GetProperty("idToken").GetString()!, root.GetProperty("expiresIn").GetString());
        Log.Info($"Новий вхід, uid {_auth.Uid}");
    }

    private async Task RefreshAsync(CancellationToken ct)
    {
        EnsureKey();
        using var r = await http.PostAsync($"https://securetoken.googleapis.com/v1/token?key={settings.FirebaseApiKey}",
            new FormUrlEncodedContent(new Dictionary<string, string> { ["grant_type"] = "refresh_token", ["refresh_token"] = _auth!.RefreshToken }), ct);
        if ((int)r.StatusCode == 400)
        {
            // Вхід відкликано — новий анонімний користувач, ПК треба підключити знову.
            Log.Info("Збережений вхід недійсний — реєструємося знову");
            _auth = null;
            await SignUpAsync(ct);
            return;
        }
        using var doc = await ReadAsync(r, "Оновлення входу", ct);
        var root = doc.RootElement;
        _auth = new StoredAuth(root.GetProperty("user_id").GetString()!, root.GetProperty("refresh_token").GetString()!);
        _auth.Save();
        SetToken(root.GetProperty("id_token").GetString()!, root.GetProperty("expires_in").GetString());
    }

    private void SetToken(string token, string? expiresIn)
    {
        _idToken = token;
        _expires = DateTimeOffset.UtcNow.AddSeconds(int.TryParse(expiresIn, out var s) ? s : 3600);
    }

    private void EnsureKey()
    {
        if (string.IsNullOrWhiteSpace(settings.FirebaseApiKey))
            throw new InvalidOperationException("У appsettings.json немає firebaseApiKey — встановіть програму з офіційного установника.");
    }

    private static async Task<JsonDocument> ReadAsync(HttpResponseMessage r, string what, CancellationToken ct)
    {
        var body = await r.Content.ReadAsStringAsync(ct);
        if (!r.IsSuccessStatusCode) throw new HttpRequestException($"{what}: HTTP {(int)r.StatusCode}");
        return JsonDocument.Parse(body);
    }
}
