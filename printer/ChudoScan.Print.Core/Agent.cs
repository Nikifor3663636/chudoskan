using System.Globalization;
using System.Security.Cryptography;
using System.Text;

namespace ChudoScan.Print.Core;

/// <summary>Код підключення ПК: 6 цифр, перша не 0, діє 15 хв (devicePairing/{code}).</summary>
public static class PairingCode
{
    public static readonly TimeSpan Lifetime = TimeSpan.FromMinutes(15);

    public static string New(Func<int, int>? next = null)
    {
        next ??= RandomNumberGenerator.GetInt32;
        var sb = new StringBuilder(6);
        sb.Append((char)('1' + next(9)));
        for (var i = 0; i < 5; i++) sb.Append((char)('0' + next(10)));
        return sb.ToString();
    }

    public static bool IsValid(string? code) =>
        code is { Length: 6 } && code[0] is >= '1' and <= '9' && code.All(c => c is >= '0' and <= '9');

    public static DateTimeOffset ExpiresAt(DateTimeOffset createdAt) => createdAt + Lifetime;

    public static bool IsExpired(DateTimeOffset expiresAt, DateTimeOffset now) => now >= expiresAt;

    /// <summary>«дійсний ще N хв» — округлюємо вгору, щоб не показувати 0, поки код ще діє.</summary>
    public static int MinutesLeft(DateTimeOffset expiresAt, DateTimeOffset now) =>
        Math.Max(0, (int)Math.Ceiling((expiresAt - now).TotalMinutes));
}

/// <summary>Завдання printJobs/{id} — те, що потрібно для обслуговування черги.</summary>
public sealed record JobInfo(string Id, string Status, int Attempts, DateTimeOffset? At, DateTimeOffset? StartedAt, DateTimeOffset? DoneAt);

public enum JobFix { None, Requeue, Fail, Delete }

/// <summary>Обслуговування черги (кожні 5 хв): завислі й старі завдання.</summary>
public static class JobMaintenance
{
    public static readonly TimeSpan StuckAfter = TimeSpan.FromMinutes(2);
    public static readonly TimeSpan KeepFinished = TimeSpan.FromDays(7);
    public const int MaxAttempts = 2;

    /// <summary>printing &gt; 2 хв → знову в чергу, якщо спроб &lt; 2, інакше error; done/error &gt; 7 днів → видалити.</summary>
    public static JobFix Decide(JobInfo job, DateTimeOffset now) => job.Status switch
    {
        "printing" when job.StartedAt is { } s && now - s > StuckAfter => job.Attempts < MaxAttempts ? JobFix.Requeue : JobFix.Fail,
        "done" or "error" when (job.DoneAt ?? job.StartedAt ?? job.At) is { } t && now - t > KeepFinished => JobFix.Delete,
        _ => JobFix.None,
    };

    public const string StuckError = "Принтер не відповів за 2 хв";
}

public enum OverallState { Pairing, Ok, Warn, Error, Offline }

/// <summary>Загальний стан для вікна й значка.</summary>
public static class Overall
{
    /// <summary>
    /// Немає зв'язку → offline; не підключено до магазину → pairing; немає Godex або Godex з помилкою чи
    /// не відповідає → error; остання помилка друку або «мало паперу» → warn; інакше ok.
    /// </summary>
    public static (OverallState State, string Text) Of(bool connected, bool paired, IReadOnlyList<GodexPrinter> godex, string? lastError)
    {
        if (!connected) return (OverallState.Offline, "Немає зв'язку");
        if (!paired) return (OverallState.Pairing, "Підключіть комп'ютер");
        if (godex.Count == 0) return (OverallState.Error, "Godex не знайдено. Перевірте кабель і драйвер.");
        var main = godex[0];
        if (main.State != PrinterState.Ok) return (OverallState.Error, $"Godex: {main.Text.ToLowerInvariant()}");
        if (!string.IsNullOrWhiteSpace(lastError)) return (OverallState.Warn, lastError!);
        if (main.Text == "Мало паперу") return (OverallState.Warn, "Godex: мало паперу");
        return (OverallState.Ok, "Працює");
    }
}

/// <summary>Тестова етикетка 40×24 на Godex (EZPL), лише ASCII.</summary>
public static class TestLabel
{
    public static string Ezpl(DateTimeOffset now) => string.Join("\r\n",
        "^Q24,2",
        "^W40",
        "^P1",
        "^L",
        "AC,16,16,1,1,0,0,ChudoScan",
        "AA,16,64,1,1,0,0,Test " + now.ToString("yyyy-MM-dd HH:mm", CultureInfo.InvariantCulture),
        "E") + "\r\n";

    public static byte[] Bytes(DateTimeOffset now) => Encoding.ASCII.GetBytes(Ezpl(now));
}
