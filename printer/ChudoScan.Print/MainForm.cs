using System.Diagnostics;
using System.Drawing.Drawing2D;
using ChudoScan.Print.Core;

namespace ChudoScan.Print;

/// <summary>Вікно «ЧудоСкан Друк»: смуга стану, Godex, черга, дії; значок у треї.</summary>
public sealed class MainForm : Form
{
    private static readonly Color Green = Color.FromArgb(0x1E, 0x8E, 0x3E);
    private static readonly Color Blue = Color.FromArgb(0x1A, 0x73, 0xE8);
    private static readonly Color Red = Color.FromArgb(0xD9, 0x30, 0x25);
    private static readonly Color Amber = Color.FromArgb(0xE3, 0x74, 0x00);
    private static readonly Color Gray = Color.FromArgb(0x70, 0x75, 0x7A);

    private readonly AgentService _agent;
    private readonly NotifyIcon _tray = new();
    private readonly Panel _band = new() { Dock = DockStyle.Top, Height = 150, Padding = new Padding(20, 14, 20, 14) };
    private readonly Label _bandTitle = new() { AutoSize = false, Dock = DockStyle.Top, Height = 34, ForeColor = Color.White, Font = new Font("Segoe UI", 16f, FontStyle.Bold) };
    private readonly Label _bandCode = new() { AutoSize = false, Dock = DockStyle.Top, Height = 58, ForeColor = Color.White, Font = new Font("Segoe UI", 34f, FontStyle.Bold) };
    private readonly Label _bandNote = new() { AutoSize = false, Dock = DockStyle.Fill, ForeColor = Color.White, Font = new Font("Segoe UI", 11f) };
    private readonly Label _printer = new() { AutoSize = false, Height = 52, Dock = DockStyle.Top, Font = new Font("Segoe UI", 11f), Padding = new Padding(20, 12, 20, 0) };
    private readonly Label _counts = new() { AutoSize = false, Height = 34, Dock = DockStyle.Top, Font = new Font("Segoe UI", 11f), Padding = new Padding(20, 4, 20, 0) };
    private readonly Label _message = new() { AutoSize = false, Height = 40, Dock = DockStyle.Top, Font = new Font("Segoe UI", 10f), ForeColor = Gray, Padding = new Padding(20, 6, 20, 0) };
    private readonly LinkLabel _disconnect = new() { Text = "Відключити від магазину", AutoSize = true, Font = new Font("Segoe UI", 10f) };
    private readonly Label _footer = new() { AutoSize = false, Dock = DockStyle.Bottom, Height = 30, ForeColor = Gray, Font = new Font("Segoe UI", 9f), Padding = new Padding(20, 6, 20, 0) };
    private OverallState? _shownState;
    private bool _exiting;

    public MainForm(AgentService agent)
    {
        _agent = agent;
        Text = "ЧудоСкан Друк";
        ClientSize = new Size(460, 420);
        FormBorderStyle = FormBorderStyle.FixedSingle;
        MaximizeBox = false;
        StartPosition = FormStartPosition.CenterScreen;
        Font = new Font("Segoe UI", 10f);
        BackColor = Color.White;

        _band.Controls.Add(_bandNote);
        _band.Controls.Add(_bandCode);
        _band.Controls.Add(_bandTitle);

        var buttons = new FlowLayoutPanel { Dock = DockStyle.Top, Height = 56, Padding = new Padding(16, 8, 16, 0), WrapContents = false };
        buttons.Controls.Add(Button("Тест етикетки", async () => Say(await _agent.TestLabelAsync())));
        buttons.Controls.Add(Button("Спробувати ще", () => { _agent.Retry(); Say("Перевіряю…"); return Task.CompletedTask; }));
        buttons.Controls.Add(Button("Журнал", () => { OpenLog(); return Task.CompletedTask; }));

        var links = new FlowLayoutPanel { Dock = DockStyle.Top, Height = 34, Padding = new Padding(20, 6, 20, 0) };
        links.Controls.Add(_disconnect);
        _disconnect.LinkClicked += async (_, _) => await DisconnectAsync();

        _footer.Text = $"{_agent.PcName} · версія {AgentService.Version}";

        Controls.Add(links);
        Controls.Add(_message);
        Controls.Add(buttons);
        Controls.Add(_counts);
        Controls.Add(_printer);
        Controls.Add(_band);
        Controls.Add(_footer);

        _tray.Text = "ЧудоСкан Друк";
        _tray.Visible = true;
        _tray.MouseClick += (_, e) => { if (e.Button == MouseButtons.Left) ShowWindow(); };
        var menu = new ContextMenuStrip();
        menu.Items.Add("Відкрити", null, (_, _) => ShowWindow());
        menu.Items.Add("Тест етикетки", null, async (_, _) => await RunAsync(async () => Say(await _agent.TestLabelAsync())));
        menu.Items.Add("Журнал", null, (_, _) => OpenLog());
        menu.Items.Add(new ToolStripSeparator());
        menu.Items.Add("Вийти", null, (_, _) => ExitApp());
        _tray.ContextMenuStrip = menu;

        _agent.StatusChanged += s => { if (IsHandleCreated) BeginInvoke(() => Render(s)); };
        var tick = new System.Windows.Forms.Timer { Interval = 30_000 };
        tick.Tick += (_, _) => Render(_agent.Status); // «дійсний ще N хв»
        tick.Start();
        Render(_agent.Status);
    }

    /// <summary>Хрестик ховає вікно; програма працює у треї.</summary>
    protected override void OnFormClosing(FormClosingEventArgs e)
    {
        if (!_exiting && e.CloseReason == CloseReason.UserClosing)
        {
            e.Cancel = true;
            Hide();
            return;
        }
        _tray.Visible = false;
        base.OnFormClosing(e);
    }

    public void ShowWindow()
    {
        Show();
        if (WindowState == FormWindowState.Minimized) WindowState = FormWindowState.Normal;
        Activate();
    }

    private void ExitApp()
    {
        _exiting = true;
        _tray.Visible = false;
        Close();
        Application.Exit();
    }

    private void Render(AgentStatus s)
    {
        var (color, title) = s.State switch
        {
            OverallState.Ok => (Green, "Працює"),
            OverallState.Warn => (Amber, "Працює, але є попередження"),
            OverallState.Pairing => (Blue, "Підключіть комп'ютер"),
            OverallState.Error => (Red, "Потрібна увага"),
            _ => (Gray, "Немає зв'язку"),
        };
        _band.BackColor = color;
        _bandTitle.Text = title;
        if (s.State == OverallState.Pairing && s.Code != null)
        {
            _bandCode.Text = $"{s.Code[..3]} {s.Code[3..]}";
            _bandCode.Visible = true;
            var left = s.CodeExpires is { } exp ? PairingCode.MinutesLeft(exp, DateTimeOffset.UtcNow) : 0;
            _bandNote.Text = $"Введіть код у застосунку: «Профіль → Принтери → Підключити комп'ютер». Дійсний ще {left} хв.";
        }
        else
        {
            _bandCode.Visible = false;
            _bandNote.Text = s.State switch
            {
                OverallState.Ok => s.ShopId != null ? $"Магазин: {s.ShopId}" : s.Text,
                OverallState.Offline => "Перевірте інтернет — програма підключиться сама.",
                _ => s.Text,
            };
        }
        var g = s.Godex.FirstOrDefault();
        _printer.Text = g == null ? "Godex не знайдено" : $"{g.Name} — {g.Text.ToLowerInvariant()}" + (g.Shared ? " (спільний принтер)" : "");
        _counts.Text = $"У черзі: {s.Queue}   ·   Надруковано сьогодні: {s.PrintedToday}";
        _disconnect.Visible = s.Paired;

        _tray.Icon?.Dispose();
        _tray.Icon = DrawIcon(color);
        _tray.Text = Truncate($"ЧудоСкан Друк — {title}", 63);
        if (s.State == OverallState.Error && _shownState != OverallState.Error)
            _tray.ShowBalloonTip(5000, "ЧудоСкан Друк: потрібна увага", s.Text, ToolTipIcon.Warning);
        _shownState = s.State;
    }

    /// <summary>Значок за станом: кольорове коло з літерою «Ч».</summary>
    public static Icon DrawIcon(Color color)
    {
        using var bmp = new Bitmap(32, 32);
        using (var gfx = Graphics.FromImage(bmp))
        {
            gfx.SmoothingMode = SmoothingMode.AntiAlias;
            gfx.TextRenderingHint = System.Drawing.Text.TextRenderingHint.AntiAliasGridFit;
            using var brush = new SolidBrush(color);
            gfx.FillEllipse(brush, 1, 1, 30, 30);
            using var font = new Font("Segoe UI", 15f, FontStyle.Bold, GraphicsUnit.Pixel);
            var size = gfx.MeasureString("Ч", font);
            gfx.DrawString("Ч", font, Brushes.White, (32 - size.Width) / 2, (32 - size.Height) / 2);
        }
        var h = bmp.GetHicon();
        using var tmp = Icon.FromHandle(h);
        var icon = (Icon)tmp.Clone();
        DestroyIcon(h);
        return icon;
    }

    [System.Runtime.InteropServices.DllImport("user32.dll")]
    private static extern bool DestroyIcon(IntPtr handle);

    private async Task DisconnectAsync()
    {
        var ok = MessageBox.Show(this, "Відключити цей комп'ютер від магазину? Друк з телефонів на цей Godex зупиниться, з'явиться новий код.",
            "ЧудоСкан Друк", MessageBoxButtons.YesNo, MessageBoxIcon.Question);
        if (ok != DialogResult.Yes) return;
        await RunAsync(async () => { await _agent.DisconnectAsync(); Say("Комп'ютер відключено від магазину."); });
    }

    private Button Button(string text, Func<Task> action)
    {
        var b = new Button { Text = text, AutoSize = true, Height = 36, Padding = new Padding(8, 2, 8, 2), Margin = new Padding(4) };
        b.Click += async (_, _) => await RunAsync(action);
        return b;
    }

    private async Task RunAsync(Func<Task> action)
    {
        try
        {
            await action();
        }
        catch (Exception e)
        {
            Log.Error("Дія з вікна", e);
            Say(e.Message);
        }
    }

    private void Say(string text) => _message.Text = text;

    private static void OpenLog()
    {
        Directory.CreateDirectory(AppPaths.Logs);
        Process.Start(new ProcessStartInfo { FileName = File.Exists(Log.Current) ? Log.Current : AppPaths.Logs, UseShellExecute = true });
    }

    private static string Truncate(string s, int max) => s.Length <= max ? s : s[..max];

    protected override void Dispose(bool disposing)
    {
        if (disposing)
        {
            _tray.Icon?.Dispose();
            _tray.Dispose();
        }
        base.Dispose(disposing);
    }
}
