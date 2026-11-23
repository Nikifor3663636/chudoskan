using ChudoScan.Print.Core;
using Xunit;

namespace ChudoScan.Print.Tests;

public class PrinterTests
{
    [Theory]
    [InlineData("Godex G500", true)]
    [InlineData("GoDEX G530 (копія 1)", true)]
    [InlineData("G500", true)]
    [InlineData("RT230", true)]
    [InlineData("EZ-1100 Plus", true)]
    [InlineData("Brother MFC-L2700DW series", false)]
    [InlineData("Microsoft Print to PDF", false)]
    [InlineData("Fax", false)]
    [InlineData("", false)]
    public void OnlyGodex(string name, bool godex) => Assert.Equal(godex, Printers.IsGodex(name));

    [Fact]
    public void SharedGodexIsOneAndLocalWins()
    {
        var list = Printers.Godex(new[]
        {
            new WmiPrinter(@"\\admintz\Godex G500"),
            new WmiPrinter(@"\\admintz.co22.corp\Godex G500"),
            new WmiPrinter("Godex G500 на admintz"),
            new WmiPrinter("Godex G500", Default: true),
            new WmiPrinter("Brother HL-L2360D series"),
        });
        var p = Assert.Single(list);
        Assert.Equal("Godex G500", p.Name);
        Assert.False(p.Shared);
        Assert.True(p.IsDefault);
    }

    [Fact]
    public void OnlySharedGodexIsKept()
    {
        var list = Printers.Godex(new[] { new WmiPrinter(@"\\admintz.co22.corp\Godex G500"), new WmiPrinter("Godex G500 on admintz") });
        var p = Assert.Single(list);
        Assert.True(p.Shared);
    }

    [Fact]
    public void ModelWithoutHost()
    {
        Assert.Equal("godex g500", Printers.Model(@"\\admintz\Godex G500"));
        Assert.Equal("godex g500", Printers.Model(@"\\admintz.co22.corp\Godex  G500"));
        Assert.Equal("godex g500", Printers.Model("Godex G500 на admintz"));
        Assert.Equal("godex g500", Printers.Model("Godex G500"));
    }

    [Theory]
    [InlineData(false, 3, 2, PrinterState.Ok, true, "Готовий")]
    [InlineData(false, 3, 4, PrinterState.Error, false, "Немає паперу")]
    [InlineData(false, 3, 3, PrinterState.Ok, true, "Мало паперу")]
    [InlineData(false, 3, 7, PrinterState.Error, null, "Відкрита кришка")]
    [InlineData(false, 3, 8, PrinterState.Error, null, "Зам'ятий папір")]
    [InlineData(false, 3, 9, PrinterState.Offline, null, "Не відповідає")]
    [InlineData(true, 3, 2, PrinterState.Offline, null, "Не відповідає")]
    [InlineData(false, 7, 0, PrinterState.Offline, null, "Не відповідає")]
    public void StateFromWmi(bool workOffline, int status, int error, PrinterState state, bool? paper, string text)
    {
        var p = Printers.ToGodex(new WmiPrinter("Godex G500", WorkOffline: workOffline, PrinterStatus: status, DetectedErrorState: error));
        Assert.Equal(state, p.State);
        Assert.Equal(paper, p.Paper);
        Assert.Equal(text, p.Text);
    }

    // ---------- Перенесено з agent/lib.test.js ----------

    [Fact]
    public void KindByName()
    {
        Assert.Equal("label_40x24", Printers.KindOf("Godex G500"));
        Assert.Equal("label_40x24", Printers.KindOf("GoDEX G500 (копія 1)"));
        Assert.Equal("a4", Printers.KindOf("Brother MFC-L2700DW series"));
        Assert.Null(Printers.KindOf("Microsoft Print to PDF"));
    }

    [Fact]
    public void PrinterListOnlyGodexWithDefault()
    {
        var godex = Printers.Godex(new[]
        {
            new WmiPrinter("Godex G500", Default: true), new WmiPrinter("Microsoft Print to PDF"), new WmiPrinter("Fax"),
            new WmiPrinter("Brother HL-L2360D series"), new WmiPrinter("Невідомий"),
        });
        Assert.Equal(new[] { new Printers.AgentPrinter("Godex G500", true, "label_40x24") }, Printers.PrinterList(godex));
    }

    [Fact]
    public void SubnetsFromArgument() =>
        Assert.Equal(new[] { "10.0.0.0/24", "10.1.0.0/23" }, Printers.ParseSubnets("10.0.0.0/24, 10.1.0.0/23,сміття,1.2.3.4/8"));
}

public class PairingTests
{
    [Fact]
    public void SixDigitsFirstNotZero()
    {
        for (var i = 0; i < 2000; i++)
        {
            var c = PairingCode.New();
            Assert.True(PairingCode.IsValid(c), c);
        }
        // Найменші й найбільші значення генератора.
        Assert.Equal("100000", PairingCode.New(_ => 0));
        Assert.Equal("999999", PairingCode.New(n => n - 1));
    }

    [Theory]
    [InlineData("123456", true)]
    [InlineData("012345", false)]
    [InlineData("12345", false)]
    [InlineData("1234567", false)]
    [InlineData("12a456", false)]
    [InlineData(null, false)]
    public void Validation(string? code, bool ok) => Assert.Equal(ok, PairingCode.IsValid(code));

    [Fact]
    public void Lives15Minutes()
    {
        var t = new DateTimeOffset(2026, 11, 24, 10, 0, 0, TimeSpan.Zero);
        var exp = PairingCode.ExpiresAt(t);
        Assert.Equal(t.AddMinutes(15), exp);
        Assert.False(PairingCode.IsExpired(exp, t.AddMinutes(14)));
        Assert.True(PairingCode.IsExpired(exp, t.AddMinutes(15)));
        Assert.Equal(15, PairingCode.MinutesLeft(exp, t));
        Assert.Equal(1, PairingCode.MinutesLeft(exp, t.AddMinutes(14).AddSeconds(30)));
        Assert.Equal(0, PairingCode.MinutesLeft(exp, t.AddMinutes(20)));
    }
}

public class JobTests
{
    private static readonly DateTimeOffset Now = new(2026, 11, 24, 12, 0, 0, TimeSpan.Zero);

    [Fact]
    public void StuckPrintingRequeuedThenFailed()
    {
        Assert.Equal(JobFix.Requeue, JobMaintenance.Decide(new JobInfo("a", "printing", 1, null, Now.AddMinutes(-3), null), Now));
        Assert.Equal(JobFix.Fail, JobMaintenance.Decide(new JobInfo("b", "printing", 2, null, Now.AddMinutes(-3), null), Now));
        Assert.Equal(JobFix.None, JobMaintenance.Decide(new JobInfo("c", "printing", 1, null, Now.AddMinutes(-1), null), Now));
        Assert.Equal(JobFix.None, JobMaintenance.Decide(new JobInfo("d", "queued", 0, Now.AddDays(-30), null, null), Now));
    }

    [Fact]
    public void OldFinishedDeleted()
    {
        Assert.Equal(JobFix.Delete, JobMaintenance.Decide(new JobInfo("a", "done", 1, null, null, Now.AddDays(-8)), Now));
        Assert.Equal(JobFix.Delete, JobMaintenance.Decide(new JobInfo("b", "error", 2, Now.AddDays(-9), null, null), Now));
        Assert.Equal(JobFix.None, JobMaintenance.Decide(new JobInfo("c", "done", 1, null, null, Now.AddDays(-6)), Now));
    }
}

public class OverallTests
{
    private static GodexPrinter Ok() => Printers.ToGodex(new WmiPrinter("Godex G500"));

    [Fact]
    public void States()
    {
        Assert.Equal(OverallState.Offline, Overall.Of(false, true, new[] { Ok() }, null).State);
        Assert.Equal(OverallState.Pairing, Overall.Of(true, false, new[] { Ok() }, null).State);
        Assert.Equal(OverallState.Error, Overall.Of(true, true, Array.Empty<GodexPrinter>(), null).State);
        var off = Printers.ToGodex(new WmiPrinter("Godex G500", WorkOffline: true));
        var (s, text) = Overall.Of(true, true, new[] { off }, null);
        Assert.Equal(OverallState.Error, s);
        Assert.Equal("Godex: не відповідає", text);
        Assert.Equal(OverallState.Warn, Overall.Of(true, true, new[] { Ok() }, "Принтер не відповів за 2 хв").State);
        Assert.Equal(OverallState.Warn, Overall.Of(true, true, new[] { Printers.ToGodex(new WmiPrinter("Godex G500", DetectedErrorState: 3)) }, null).State);
        Assert.Equal((OverallState.Ok, "Працює"), Overall.Of(true, true, new[] { Ok() }, null));
    }
}

public class TestLabelTests
{
    [Fact]
    public void EzplAsciiOnly()
    {
        var now = new DateTimeOffset(2026, 11, 24, 9, 5, 0, TimeSpan.FromHours(2));
        var text = TestLabel.Ezpl(now);
        Assert.Equal("^Q24,2\r\n^W40\r\n^P1\r\n^L\r\nAC,16,16,1,1,0,0,ChudoScan\r\nAA,16,64,1,1,0,0,Test 2026-11-24 09:05\r\nE\r\n", text);
        var bytes = TestLabel.Bytes(now);
        Assert.All(bytes, b => Assert.True(b < 128));
        Assert.Equal(text.Length, bytes.Length);
    }
}
