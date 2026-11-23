using System.ComponentModel;
using System.Management;
using System.Runtime.InteropServices;
using ChudoScan.Print.Core;

namespace ChudoScan.Print;

/// <summary>RAW-друк у чергу Windows (winspool.drv): байти EZPL ідуть на Godex як є.</summary>
public static class RawPrinter
{
    [StructLayout(LayoutKind.Sequential, CharSet = CharSet.Unicode)]
    private sealed class DocInfo
    {
        public string DocName = "ChudoScan";
        public string? OutputFile;
        public string DataType = "RAW";
    }

    [DllImport("winspool.drv", EntryPoint = "OpenPrinterW", SetLastError = true, CharSet = CharSet.Unicode)]
    private static extern bool OpenPrinter(string name, out IntPtr handle, IntPtr defaults);

    [DllImport("winspool.drv", SetLastError = true)]
    private static extern bool ClosePrinter(IntPtr handle);

    [DllImport("winspool.drv", EntryPoint = "StartDocPrinterW", SetLastError = true, CharSet = CharSet.Unicode)]
    private static extern int StartDocPrinter(IntPtr handle, int level, [In] DocInfo info);

    [DllImport("winspool.drv", SetLastError = true)]
    private static extern bool EndDocPrinter(IntPtr handle);

    [DllImport("winspool.drv", SetLastError = true)]
    private static extern bool StartPagePrinter(IntPtr handle);

    [DllImport("winspool.drv", SetLastError = true)]
    private static extern bool EndPagePrinter(IntPtr handle);

    [DllImport("winspool.drv", SetLastError = true)]
    private static extern bool WritePrinter(IntPtr handle, IntPtr bytes, int count, out int written);

    public static readonly TimeSpan Timeout = TimeSpan.FromSeconds(60);

    /// <summary>Друк з таймаутом 60 с: завислий драйвер не зупиняє програму.</summary>
    public static async Task SendAsync(string printer, byte[] data, CancellationToken ct)
    {
        var work = Task.Run(() => Send(printer, data), ct);
        var done = await Task.WhenAny(work, Task.Delay(Timeout, ct));
        if (done != work) throw new TimeoutException("Принтер не прийняв завдання за 60 с");
        await work;
    }

    private static void Send(string printer, byte[] data)
    {
        if (!OpenPrinter(printer, out var h, IntPtr.Zero)) throw Fail($"Не вдалося відкрити принтер «{printer}»");
        var buf = Marshal.AllocHGlobal(data.Length);
        try
        {
            Marshal.Copy(data, 0, buf, data.Length);
            if (StartDocPrinter(h, 1, new DocInfo()) == 0) throw Fail("Принтер не прийняв документ");
            try
            {
                if (!StartPagePrinter(h)) throw Fail("Принтер не прийняв сторінку");
                if (!WritePrinter(h, buf, data.Length, out var written) || written != data.Length) throw Fail("Не вдалося передати дані на принтер");
                EndPagePrinter(h);
            }
            finally
            {
                EndDocPrinter(h);
            }
        }
        finally
        {
            Marshal.FreeHGlobal(buf);
            ClosePrinter(h);
        }
    }

    private static Exception Fail(string message) => new IOException($"{message} ({new Win32Exception(Marshal.GetLastWin32Error()).Message})");
}

/// <summary>Принтери Windows (Win32_Printer) → рядки для ядра.</summary>
public static class WmiPrinters
{
    public static IReadOnlyList<WmiPrinter> Read()
    {
        var list = new List<WmiPrinter>();
        try
        {
            using var searcher = new ManagementObjectSearcher("SELECT Name, Default, WorkOffline, PrinterStatus, DetectedErrorState FROM Win32_Printer");
            foreach (var o in searcher.Get().Cast<ManagementObject>())
            {
                using (o)
                {
                    list.Add(new WmiPrinter(
                        o["Name"] as string ?? "",
                        o["Default"] as bool? ?? false,
                        o["WorkOffline"] as bool? ?? false,
                        Convert.ToInt32(o["PrinterStatus"] ?? 3),
                        Convert.ToInt32(o["DetectedErrorState"] ?? 2)));
                }
            }
        }
        catch (Exception e)
        {
            Log.Error("WMI: не вдалося прочитати принтери", e);
        }
        return list;
    }
}
