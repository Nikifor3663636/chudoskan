using System.Text;
using ChudoScan.Print.Core;
using Xunit;

namespace ChudoScan.Print.Tests;

public class PrinterUpdateTests
{
    private static readonly string Sha = new('a', 64);

    private static string Manifest(string version, string sha) => $$$"""
        {"version":"{{{version}}}","notes":"Друк швидше","publishedAt":"2026-11-24T10:00:00Z",
         "x64":{"url":"https://github.com/x/releases/download/printer-latest/ChudoScanPrint-x64.exe","sha256":"{{{sha}}}"},
         "x86":{"url":"https://github.com/x/releases/download/printer-latest/ChudoScanPrint-x86.exe","sha256":"{{{sha}}}"}}
        """;

    [Fact]
    public void ParsesManifestAndPicksArch()
    {
        var m = PrinterUpdate.Parse(Manifest("1.2.0", Sha))!;
        Assert.Equal("1.2.0", m.Version);
        Assert.EndsWith("x64.exe", PrinterUpdate.Asset(m, true)!.Url);
        Assert.EndsWith("x86.exe", PrinterUpdate.Asset(m, false)!.Url);
        Assert.Null(PrinterUpdate.Asset(PrinterUpdate.Parse(Manifest("1.2.0", "abc"))!, true));
        Assert.Null(PrinterUpdate.Parse("<html>"));
        Assert.Null(PrinterUpdate.Parse("""{"version":"нова"}"""));
    }

    [Theory]
    [InlineData("1.0.0", "1.0.1", true)]
    [InlineData("1.9.3", "1.10.0", true)]
    [InlineData("1.2.0", "1.2.0", false)]
    [InlineData("2.0.0", "1.9.9", false)]
    [InlineData("1.0.0", "", false)]
    public void OnlyNewer(string current, string remote, bool newer) => Assert.Equal(newer, PrinterUpdate.IsNewer(current, remote));

    [Fact]
    public void ShaOfReplacedInstallerDoesNotMatch()
    {
        var bytes = Encoding.UTF8.GetBytes("інсталятор");
        var sha = PrinterUpdate.Sha256(new MemoryStream(bytes));
        Assert.True(PrinterUpdate.ShaMatches(new MemoryStream(bytes), sha.ToUpperInvariant()));
        Assert.False(PrinterUpdate.ShaMatches(new MemoryStream(Encoding.UTF8.GetBytes("підмінений")), sha));
        Assert.False(PrinterUpdate.ShaMatches(new MemoryStream(bytes), ""));
    }
}
