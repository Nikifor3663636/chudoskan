using System.Diagnostics;
using System.Runtime.InteropServices;
using System.Security.Cryptography.X509Certificates;
using ChudoScan.Print.Core;

namespace ChudoScan.Print;

/// <summary>
/// Самостійне оновлення: при старті (через хвилину) і раз на 6 год — printer-version.json,
/// завантаження інсталятора, sha256 і Authenticode (якщо файл підписано), запуск інсталятора
/// окремим процесом і вихід. Інсталятор ставить нову версію й запускає її в треї.
/// </summary>
internal sealed class Updater : IDisposable
{
    private readonly HttpClient _http;
    private readonly Action _exit;
    private readonly System.Threading.Timer _timer;
    private int _busy;

    public Updater(Action exit)
    {
        _exit = exit;
        _http = new HttpClient { Timeout = TimeSpan.FromMinutes(5) };
        _http.DefaultRequestHeaders.UserAgent.ParseAdd($"ChudoScanPrint/{AgentService.Version}");
        _timer = new System.Threading.Timer(_ => _ = CheckAsync(), null, Timeout.Infinite, Timeout.Infinite);
    }

    public void Start() => _timer.Change(TimeSpan.FromMinutes(1), PrinterUpdate.Interval);

    public async Task CheckAsync()
    {
        if (Interlocked.Exchange(ref _busy, 1) == 1) return;
        try
        {
            var json = await _http.GetStringAsync(PrinterUpdate.ManifestUrl);
            var m = PrinterUpdate.Parse(json);
            if (m == null || !PrinterUpdate.IsNewer(AgentService.Version, m.Version)) return;
            var asset = PrinterUpdate.Asset(m, Environment.Is64BitOperatingSystem);
            if (asset == null) { Log.Error($"Оновлення {m.Version}: немає інсталятора для цієї Windows"); return; }

            var file = Path.Combine(Path.GetTempPath(), $"ChudoScanPrint-update-{m.Version}.exe");
            await using (var src = await _http.GetStreamAsync(asset.Url))
            await using (var dst = File.Create(file))
            {
                await src.CopyToAsync(dst);
            }
            await using (var check = File.OpenRead(file))
            {
                if (!PrinterUpdate.ShaMatches(check, asset.Sha256))
                {
                    Log.Error($"Оновлення {m.Version}: sha256 не збігається — файл видалено");
                    File.Delete(file);
                    return;
                }
            }
            if (!SignatureOk(file))
            {
                Log.Error($"Оновлення {m.Version}: недійсний цифровий підпис — файл видалено");
                File.Delete(file);
                return;
            }

            Log.Info($"Оновлення {AgentService.Version} → {m.Version}: запускаю інсталятор");
            Process.Start(new ProcessStartInfo(file, PrinterUpdate.SilentArgs) { UseShellExecute = false });
            _exit();
        }
        catch (Exception e)
        {
            Log.Error("Перевірка оновлення", e);
        }
        finally
        {
            Interlocked.Exchange(ref _busy, 0);
        }
    }

    /// <summary>Непідписаний інсталятор — дозволено (захищає sha256); підписаний — підпис має бути дійсним.</summary>
    private static bool SignatureOk(string file)
    {
        try
        {
#pragma warning disable SYSLIB0057 // потрібен лише факт наявності підпису
            _ = X509Certificate.CreateFromSignedFile(file);
#pragma warning restore SYSLIB0057
        }
        catch (System.Security.Cryptography.CryptographicException)
        {
            return true;
        }
        return WinTrust.Verify(file);
    }

    public void Dispose()
    {
        _timer.Dispose();
        _http.Dispose();
    }
}

/// <summary>WinVerifyTrust: перевірка Authenticode-підпису файлу.</summary>
internal static class WinTrust
{
    private static readonly Guid GenericVerifyV2 = new("00AAC56B-CD44-11d0-8CC2-00C04FC295EE");

    [StructLayout(LayoutKind.Sequential, CharSet = CharSet.Unicode)]
    private struct FileInfo
    {
        public uint cbStruct;
        public string pcwszFilePath;
        public IntPtr hFile;
        public IntPtr pgKnownSubject;
    }

    [StructLayout(LayoutKind.Sequential)]
    private struct TrustData
    {
        public uint cbStruct;
        public IntPtr pPolicyCallbackData;
        public IntPtr pSIPClientData;
        public uint dwUIChoice;
        public uint fdwRevocationChecks;
        public uint dwUnionChoice;
        public IntPtr pFile;
        public uint dwStateAction;
        public IntPtr hWVTStateData;
        public IntPtr pwszURLReference;
        public uint dwProvFlags;
        public uint dwUIContext;
        public IntPtr pSignatureSettings;
    }

    [DllImport("wintrust.dll", ExactSpelling = true, SetLastError = false, CharSet = CharSet.Unicode)]
    private static extern int WinVerifyTrust(IntPtr hwnd, [MarshalAs(UnmanagedType.LPStruct)] Guid action, ref TrustData data);

    public static bool Verify(string path)
    {
        var fi = new FileInfo { cbStruct = (uint)Marshal.SizeOf<FileInfo>(), pcwszFilePath = path };
        var pFile = Marshal.AllocHGlobal(Marshal.SizeOf<FileInfo>());
        try
        {
            Marshal.StructureToPtr(fi, pFile, false);
            var data = new TrustData
            {
                cbStruct = (uint)Marshal.SizeOf<TrustData>(),
                dwUIChoice = 2, // WTD_UI_NONE
                fdwRevocationChecks = 0,
                dwUnionChoice = 1, // WTD_CHOICE_FILE
                pFile = pFile,
                dwStateAction = 0,
                dwProvFlags = 0x00000080, // WTD_REVOCATION_CHECK_NONE
            };
            return WinVerifyTrust(IntPtr.Zero, GenericVerifyV2, ref data) == 0;
        }
        finally
        {
            Marshal.DestroyStructure<FileInfo>(pFile);
            Marshal.FreeHGlobal(pFile);
        }
    }
}
