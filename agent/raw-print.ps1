# ЧудоСкан: надіслати файл у принтер Windows без перетворення (тип даних RAW).
# Godex отримує EZPL, Brother — PCL рівно так, як їх зібрав телефон.
# Використання: raw-print.ps1 -Printer "Godex G500" -File C:\temp\job.bin
param(
    [Parameter(Mandatory = $true)][string]$Printer,
    [Parameter(Mandatory = $true)][string]$File
)
$ErrorActionPreference = 'Stop'

Add-Type -TypeDefinition @'
using System;
using System.Runtime.InteropServices;

public static class ChudoRawPrinter {
    [StructLayout(LayoutKind.Sequential, CharSet = CharSet.Unicode)]
    public class DOCINFO {
        [MarshalAs(UnmanagedType.LPWStr)] public string pDocName;
        [MarshalAs(UnmanagedType.LPWStr)] public string pOutputFile;
        [MarshalAs(UnmanagedType.LPWStr)] public string pDataType;
    }

    [DllImport("winspool.drv", EntryPoint = "OpenPrinterW", SetLastError = true, CharSet = CharSet.Unicode)]
    static extern bool OpenPrinter(string name, out IntPtr handle, IntPtr defaults);
    [DllImport("winspool.drv", SetLastError = true)]
    static extern bool ClosePrinter(IntPtr handle);
    [DllImport("winspool.drv", EntryPoint = "StartDocPrinterW", SetLastError = true, CharSet = CharSet.Unicode)]
    static extern int StartDocPrinter(IntPtr handle, int level, [In] DOCINFO info);
    [DllImport("winspool.drv", SetLastError = true)]
    static extern bool EndDocPrinter(IntPtr handle);
    [DllImport("winspool.drv", SetLastError = true)]
    static extern bool StartPagePrinter(IntPtr handle);
    [DllImport("winspool.drv", SetLastError = true)]
    static extern bool EndPagePrinter(IntPtr handle);
    [DllImport("winspool.drv", SetLastError = true)]
    static extern bool WritePrinter(IntPtr handle, byte[] data, int count, out int written);

    public static void Send(string printer, byte[] data) {
        IntPtr h;
        if (!OpenPrinter(printer, out h, IntPtr.Zero)) throw new Exception("Принтер не знайдено: " + printer + " (код " + Marshal.GetLastWin32Error() + ")");
        try {
            var info = new DOCINFO { pDocName = "ЧудоСкан", pDataType = "RAW" };
            if (StartDocPrinter(h, 1, info) == 0) throw new Exception("Спулер не прийняв завдання (код " + Marshal.GetLastWin32Error() + ")");
            try {
                StartPagePrinter(h);
                int written;
                if (!WritePrinter(h, data, data.Length, out written) || written != data.Length)
                    throw new Exception("Записано " + written + " з " + data.Length + " байт (код " + Marshal.GetLastWin32Error() + ")");
                EndPagePrinter(h);
            } finally { EndDocPrinter(h); }
        } finally { ClosePrinter(h); }
    }
}
'@

[ChudoRawPrinter]::Send($Printer, [System.IO.File]::ReadAllBytes($File))
