namespace ChudoScan.Print;

internal static class Program
{
    private const string MutexName = @"Local\ChudoScanPrint";
    private const string ShowEventName = @"Local\ChudoScanPrint.Show";

    /// <summary>Одна копія: повторний запуск лише показує вікно першої. «--hidden» — автозапуск у трей.</summary>
    [STAThread]
    private static void Main(string[] args)
    {
        using var mutex = new Mutex(true, MutexName, out var first);
        if (!first)
        {
            try
            {
                using var show = EventWaitHandle.OpenExisting(ShowEventName);
                show.Set();
            }
            catch (WaitHandleCannotBeOpenedException)
            {
                // перша копія ще запускається
            }
            return;
        }

        ApplicationConfiguration.Initialize();
        Application.ThreadException += (_, e) => Log.Error("Помилка вікна", e.Exception);
        AppDomain.CurrentDomain.UnhandledException += (_, e) => Log.Error("Збій", e.ExceptionObject as Exception);

        using var showEvent = new EventWaitHandle(false, EventResetMode.AutoReset, ShowEventName);
        using var agent = new AgentService(AppSettings.Load());
        using var form = new MainForm(agent);

        // Сигнал від другої копії — показати вікно.
        var waiter = new Thread(() =>
        {
            while (showEvent.WaitOne())
            {
                if (form.IsHandleCreated) form.BeginInvoke(form.ShowWindow);
            }
        }) { IsBackground = true };
        waiter.Start();

        agent.Start();
        var hidden = args.Contains("--hidden", StringComparer.OrdinalIgnoreCase);
        if (hidden)
        {
            // Автозапуск: лише значок у треї; вікно створюємо, щоб працювали сповіщення.
            _ = form.Handle;
            Application.Run();
        }
        else
        {
            Application.Run(form);
        }
    }
}
