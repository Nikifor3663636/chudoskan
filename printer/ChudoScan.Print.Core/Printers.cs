using System.Text.RegularExpressions;

namespace ChudoScan.Print.Core;

/// <summary>Рядок Win32_Printer (WMI) — лише поля, які нам потрібні.</summary>
public sealed record WmiPrinter(
    string Name,
    bool Default = false,
    bool WorkOffline = false,
    int PrinterStatus = 3,
    int DetectedErrorState = 2);

public enum PrinterState { Ok, Offline, Error }

/// <summary>Godex на цьому ПК: ім'я для друку, стан і текст українською. Стан — лише підказка, друк не блокує.</summary>
public sealed record GodexPrinter(string Name, bool IsDefault, bool Shared, PrinterState State, bool? Paper, string Text);

/// <summary>Принтери Windows → Godex для друку етикеток 40×24.</summary>
public static partial class Printers
{
    // Godex: G500/G530, RT2xx, EZ-серія.
    [GeneratedRegex(@"godex|\bg5[03]0\b|\brt2\d\d|\bez[-\s]?\d", RegexOptions.IgnoreCase)]
    private static partial Regex GodexName();

    // «\\admintz\Godex G500», «\\admintz.co22.corp\Godex G500».
    [GeneratedRegex(@"^\\\\[^\\]+\\(.+)$")]
    private static partial Regex UncName();

    // «Godex G500 на admintz», «Godex G500 on admintz».
    [GeneratedRegex(@"^(.+?)\s+(на|on)\s+\S+$", RegexOptions.IgnoreCase)]
    private static partial Regex OnHost();

    [GeneratedRegex(@"pdf|xps|onenote|fax", RegexOptions.IgnoreCase)]
    private static partial Regex Virtual();

    public static bool IsGodex(string? name) => !string.IsNullOrWhiteSpace(name) && GodexName().IsMatch(name) && !Virtual().IsMatch(name);

    /// <summary>Розшарений принтер іншого ПК?</summary>
    public static bool IsShared(string name) => UncName().IsMatch(name) || OnHost().IsMatch(name);

    /// <summary>Модель без імені ПК: «\\admintz\Godex G500» і «Godex G500 на admintz» → «godex g500».</summary>
    public static string Model(string name)
    {
        var n = name.Trim();
        var unc = UncName().Match(n);
        if (unc.Success) n = unc.Groups[1].Value;
        var on = OnHost().Match(n);
        if (on.Success) n = on.Groups[1].Value;
        return Regex.Replace(n, @"\s+", " ").Trim().ToLowerInvariant();
    }

    /// <summary>
    /// Лише Godex; той самий розшарений принтер під різними іменами — один, локальний має перевагу,
    /// далі — принтер за замовчуванням.
    /// </summary>
    public static IReadOnlyList<GodexPrinter> Godex(IEnumerable<WmiPrinter> rows) =>
        rows.Where(r => IsGodex(r.Name))
            .GroupBy(r => Model(r.Name))
            .Select(g => g.OrderBy(r => IsShared(r.Name)).ThenByDescending(r => r.Default).First())
            .Select(ToGodex)
            .OrderByDescending(p => p.IsDefault)
            .ThenBy(p => p.Shared)
            .ThenBy(p => p.Name, StringComparer.OrdinalIgnoreCase)
            .ToList();

    /// <summary>
    /// Стан з WMI. DetectedErrorState: 3 мало паперу, 4 немає паперу, 7 кришка відкрита, 8 зам'ятий,
    /// 9 офлайн, 10 потрібне обслуговування; PrinterStatus 7 — офлайн.
    /// </summary>
    public static GodexPrinter ToGodex(WmiPrinter r)
    {
        var shared = IsShared(r.Name);
        (PrinterState state, bool? paper, string text) = r.DetectedErrorState switch
        {
            4 => (PrinterState.Error, (bool?)false, "Немає паперу"),
            7 => (PrinterState.Error, (bool?)null, "Відкрита кришка"),
            8 => (PrinterState.Error, (bool?)null, "Зам'ятий папір"),
            10 => (PrinterState.Error, (bool?)null, "Потрібне обслуговування"),
            9 => (PrinterState.Offline, (bool?)null, "Не відповідає"),
            3 => (PrinterState.Ok, (bool?)true, "Мало паперу"),
            _ when r.WorkOffline || r.PrinterStatus == 7 => (PrinterState.Offline, (bool?)null, "Не відповідає"),
            _ => (PrinterState.Ok, (bool?)true, "Готовий"),
        };
        if (state == PrinterState.Ok && (r.WorkOffline || r.PrinterStatus == 7)) (state, text) = (PrinterState.Offline, "Не відповідає");
        return new GodexPrinter(r.Name, r.Default, shared, state, paper, text);
    }

    // ---------- Перенесено з Node-агента (agent/lib.js) ----------

    [GeneratedRegex(@"brother|mfc-|hl-|dcp-|laserjet|kyocera|xerox|ricoh|canon|epson|pantum|samsung", RegexOptions.IgnoreCase)]
    private static partial Regex OfficeName();

    /// <summary>Вид принтера за ім'ям: Godex → «label_40x24», офісні → «a4», інші — null.</summary>
    public static string? KindOf(string? name)
    {
        if (string.IsNullOrEmpty(name)) return null;
        if (Regex.IsMatch(name, @"godex|g500|g530|rt200|rt230|ez-?\d", RegexOptions.IgnoreCase)) return "label_40x24";
        if (OfficeName().IsMatch(name)) return "a4";
        return null;
    }

    public sealed record AgentPrinter(string Name, bool IsDefault, string Kind);

    /// <summary>Список для agents/{uid}.printers: лише Godex (програма друкує тільки на Godex), без віртуальних.</summary>
    public static IReadOnlyList<AgentPrinter> PrinterList(IEnumerable<GodexPrinter> godex) =>
        godex.Select(p => new AgentPrinter(p.Name, p.IsDefault, "label_40x24")).ToList();

    /// <summary>Підмережі «a/24,b/23» → лише коректні /22…/32.</summary>
    public static IReadOnlyList<string> ParseSubnets(string? arg) =>
        (arg ?? "").Split(',').Select(s => s.Trim())
            .Where(s => Regex.IsMatch(s, @"^\d{1,3}(\.\d{1,3}){3}/(2[2-9]|3[0-2])$"))
            .ToList();
}
