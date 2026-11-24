using System.Security.Cryptography;
using System.Text.Json;
using System.Text.Json.Serialization;

namespace ChudoScan.Print.Core;

/// <summary>Інсталятор однієї архітектури в printer-version.json.</summary>
public sealed record UpdateAsset(
    [property: JsonPropertyName("url")] string Url,
    [property: JsonPropertyName("sha256")] string Sha256);

/// <summary>
/// printer-version.json у релізі «printer-latest» (CI оновлює його з кожним тегом printer-vX.Y.Z):
/// {"version":"1.2.0","notes":"…","publishedAt":"…","x64":{"url","sha256"},"x86":{"url","sha256"}}.
/// </summary>
public sealed record PrinterManifest(
    [property: JsonPropertyName("version")] string Version,
    [property: JsonPropertyName("notes")] string? Notes,
    [property: JsonPropertyName("x64")] UpdateAsset? X64,
    [property: JsonPropertyName("x86")] UpdateAsset? X86);

public static class PrinterUpdate
{
    /// <summary>Постійне посилання без лімітів API: реліз «printer-latest», файл перезаписується.</summary>
    public const string ManifestUrl = "https://github.com/Nikifor3663636/chudoskan/releases/download/printer-latest/printer-version.json";

    public static readonly TimeSpan Interval = TimeSpan.FromHours(6);

    public static PrinterManifest? Parse(string json)
    {
        try
        {
            var m = JsonSerializer.Deserialize<PrinterManifest>(json);
            return m is { Version.Length: > 0 } && Version.TryParse(m.Version, out _) ? m : null;
        }
        catch (JsonException)
        {
            return null;
        }
    }

    /// <summary>Лише новіша версія; «1.10.0» новіша за «1.9.3».</summary>
    public static bool IsNewer(string current, string remote) =>
        Version.TryParse(current, out var c) && Version.TryParse(remote, out var r) && r > c;

    public static UpdateAsset? Asset(PrinterManifest m, bool is64Bit)
    {
        var a = is64Bit ? m.X64 : m.X86;
        return a is { Url.Length: > 0 } && a.Url.StartsWith("https://", StringComparison.Ordinal) && IsSha(a.Sha256) ? a : null;
    }

    public static bool IsSha(string? s) => s is { Length: 64 } && s.All(Uri.IsHexDigit);

    public static string Sha256(Stream s) => Convert.ToHexString(SHA256.HashData(s)).ToLowerInvariant();

    public static bool ShaMatches(Stream s, string expected) =>
        IsSha(expected) && string.Equals(Sha256(s), expected, StringComparison.OrdinalIgnoreCase);

    /// <summary>
    /// Тихе встановлення: інсталятор закриває програму, ставить нову версію і запускає її в треї.
    /// Черга друку при цьому не губиться — завдання лишаються в Firestore зі статусом "queued".
    /// </summary>
    public const string SilentArgs = "/VERYSILENT /SUPPRESSMSGBOXES /NORESTART /CLOSEAPPLICATIONS";
}
