namespace ChudoScan.Print.Core;

/// <summary>Продаж sales/{id} у статусі "fiscalized", готовий для 1С. Json — формат docs/sale.schema.json.</summary>
public sealed record SaleForExport(string SaleId, string ShopCode, string Json);

public enum OneCStatus { Ok, Exists, Error }

/// <summary>Відповідь 1С: Exists — цей saleId уже проведено (повторне надсилання нічого не створює).</summary>
public sealed record OneCResult(OneCStatus Status, string? Error = null);

/// <summary>Звідки брати продажі й куди ставити exported1C (Firestore — у програмі, фейк — у тестах).</summary>
public interface ISalesSource
{
    Task<IReadOnlyList<SaleForExport>> FiscalizedAsync(string shopId, CancellationToken ct);
    Task MarkExportedAsync(string saleId, CancellationToken ct);
}

/// <summary>Передача продажу в 1С: HTTP-сервіс або папка обміну (docs/obmin-prodazhiv-1c.md).</summary>
public interface IOneCClient
{
    Task<OneCResult> SendAsync(SaleForExport sale, CancellationToken ct);
}

/// <summary>Локальний журнал переданих saleId — щоб не надіслати двічі навіть після збою між 1С і Firestore.</summary>
public interface IExportJournal
{
    bool Contains(string saleId);
    void Add(string saleId);
}

public sealed class MemoryExportJournal : IExportJournal
{
    private readonly HashSet<string> _ids = new(StringComparer.Ordinal);
    public bool Contains(string saleId) { lock (_ids) return _ids.Contains(saleId); }
    public void Add(string saleId) { lock (_ids) _ids.Add(saleId); }
}

public sealed record ExportReport(int Sent, int Skipped, IReadOnlyList<string> Errors);

/// <summary>Бере продажі свого магазину зі статусом "fiscalized", передає в 1С, ставить exported1C.</summary>
public interface ISalesExporter
{
    Task<ExportReport> ExportAsync(string shopId, CancellationToken ct);
}

/// <summary>Ідемпотентно за saleId: уже переданий (журнал або відповідь 1С «вже є») повторно не надсилається.</summary>
public sealed class SalesExporter(ISalesSource source, IOneCClient oneC, IExportJournal journal) : ISalesExporter
{
    public async Task<ExportReport> ExportAsync(string shopId, CancellationToken ct)
    {
        var sent = 0;
        var skipped = 0;
        var errors = new List<string>();
        var seen = new HashSet<string>(StringComparer.Ordinal);
        foreach (var sale in await source.FiscalizedAsync(shopId, ct))
        {
            ct.ThrowIfCancellationRequested();
            if (!seen.Add(sale.SaleId) || journal.Contains(sale.SaleId))
            {
                // Уже в 1С: лише дописати exported1C, якщо минулого разу не встигли.
                if (journal.Contains(sale.SaleId)) await source.MarkExportedAsync(sale.SaleId, ct);
                skipped++;
                continue;
            }
            var r = await oneC.SendAsync(sale, ct);
            if (r.Status == OneCStatus.Error)
            {
                errors.Add($"{sale.SaleId}: {r.Error}");
                continue;
            }
            journal.Add(sale.SaleId);
            await source.MarkExportedAsync(sale.SaleId, ct);
            if (r.Status == OneCStatus.Ok) sent++; else skipped++;
        }
        return new ExportReport(sent, skipped, errors);
    }
}

public sealed record FiscalResult(bool Ok, string? FiscalNumber, string? Url, string? Error)
{
    public static FiscalResult NotConfigured() => new(false, null, null, "Фіскалізацію не налаштовано");
}

/// <summary>Фіскалізація оплаченого продажу (ПРРО банку або Checkbox). Ключі ПРРО — лише на цьому ПК, не в репозиторії.</summary>
public interface IFiscalizer
{
    Task<FiscalResult> FiscalizeAsync(string saleId, CancellationToken ct);
}

/// <summary>Поки договору з ПРРО немає: чесно відповідаємо «не налаштовано», нічого не фіскалізуємо.</summary>
public sealed class NotConfiguredFiscalizer : IFiscalizer
{
    public Task<FiscalResult> FiscalizeAsync(string saleId, CancellationToken ct) => Task.FromResult(FiscalResult.NotConfigured());
}
