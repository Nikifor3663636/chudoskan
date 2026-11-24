using ChudoScan.Print.Core;
using Xunit;

namespace ChudoScan.Print.Tests;

public class SalesExporterTests
{
    private sealed class FakeSource(params SaleForExport[] sales) : ISalesSource
    {
        public List<string> Marked { get; } = new();
        public Task<IReadOnlyList<SaleForExport>> FiscalizedAsync(string shopId, CancellationToken ct) =>
            Task.FromResult<IReadOnlyList<SaleForExport>>(sales.Where(s => s.ShopCode == shopId).ToList());
        public Task MarkExportedAsync(string saleId, CancellationToken ct) { Marked.Add(saleId); return Task.CompletedTask; }
    }

    private sealed class FakeOneC : IOneCClient
    {
        public List<string> Sent { get; } = new();
        public Func<SaleForExport, OneCResult> Answer { get; set; } = _ => new OneCResult(OneCStatus.Ok);
        public Task<OneCResult> SendAsync(SaleForExport sale, CancellationToken ct) { Sent.Add(sale.SaleId); return Task.FromResult(Answer(sale)); }
    }

    private static SaleForExport Sale(string id, string shop = "22") => new(id, shop, "{}");

    [Fact]
    public async Task SameSaleIdIsNotExportedTwice()
    {
        var source = new FakeSource(Sale("s1"), Sale("s1"), Sale("s2"));
        var oneC = new FakeOneC();
        var exporter = new SalesExporter(source, oneC, new MemoryExportJournal());

        var first = await exporter.ExportAsync("22", CancellationToken.None);
        // Firestore ще не встиг показати exported1C — ті самі продажі приходять удруге.
        var second = await exporter.ExportAsync("22", CancellationToken.None);

        Assert.Equal(new[] { "s1", "s2" }, oneC.Sent);
        Assert.Equal(2, first.Sent);
        Assert.Equal(0, second.Sent);
        Assert.Equal(3, second.Skipped); // s1, ще раз s1 і s2 — уже в 1С
    }

    [Fact]
    public async Task OnlyOwnShop()
    {
        var oneC = new FakeOneC();
        await new SalesExporter(new FakeSource(Sale("a", "22"), Sale("b", "62")), oneC, new MemoryExportJournal())
            .ExportAsync("22", CancellationToken.None);
        Assert.Equal(new[] { "a" }, oneC.Sent);
    }

    [Fact]
    public async Task OneCAlreadyHasItMarksExported()
    {
        var source = new FakeSource(Sale("s1"));
        var oneC = new FakeOneC { Answer = _ => new OneCResult(OneCStatus.Exists) };
        var r = await new SalesExporter(source, oneC, new MemoryExportJournal()).ExportAsync("22", CancellationToken.None);
        Assert.Equal(0, r.Sent);
        Assert.Equal(new[] { "s1" }, source.Marked);
    }

    [Fact]
    public async Task ErrorIsRetriedNextTime()
    {
        var source = new FakeSource(Sale("s1"));
        var oneC = new FakeOneC { Answer = _ => new OneCResult(OneCStatus.Error, "Товар не знайдено") };
        var exporter = new SalesExporter(source, oneC, new MemoryExportJournal());
        var r = await exporter.ExportAsync("22", CancellationToken.None);
        Assert.Single(r.Errors);
        Assert.Empty(source.Marked);
        oneC.Answer = _ => new OneCResult(OneCStatus.Ok);
        Assert.Equal(1, (await exporter.ExportAsync("22", CancellationToken.None)).Sent);
    }

    [Fact]
    public async Task FiscalizerIsNotConfigured()
    {
        var r = await new NotConfiguredFiscalizer().FiscalizeAsync("s1", CancellationToken.None);
        Assert.False(r.Ok);
        Assert.Equal("Фіскалізацію не налаштовано", r.Error);
    }
}
