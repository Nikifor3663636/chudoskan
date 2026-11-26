using System.Diagnostics;
using System.Drawing.Drawing2D;
using ChudoScan.Print.Core;

namespace ChudoScan.Print;

/// <summary>Вікно «ЧудоСкан Друк»: смуга стану, Godex, черга, дії; значок у треї.</summary>
public sealed class MainForm : Form
{
    private static readonly Color Background = Color.FromArgb(247, 245, 242);
    private static readonly Color Ink = Color.FromArgb(28, 28, 31);
    private static readonly Color Muted = Color.FromArgb(84, 91, 103);
    private static readonly Color Line = Color.FromArgb(231, 225, 218);
    private static readonly Color Orange = Color.FromArgb(255, 117, 0);
    private static readonly Color Green = Color.FromArgb(30, 127, 71);
    private static readonly Color Blue = Color.FromArgb(31, 91, 194);
    private static readonly Color Red = Color.FromArgb(180, 38, 32);
    private static readonly Color Amber = Color.FromArgb(143, 86, 0);

    private readonly AgentService _agent;
    private readonly NotifyIcon _tray = new();
    private readonly System.Windows.Forms.Timer _tick = new() { Interval = 30_000 };
    private readonly Label _status = Copy("", 13, FontStyle.Bold);
    private readonly Label _code = Copy("", 32, FontStyle.Bold);
    private readonly Label _note = Copy("", color: Muted);
    private readonly Label _printer = Copy("", 14, FontStyle.Bold);
    private readonly Label _printerState = Copy("", color: Muted);
    private readonly Label _queue = Copy("0", 20, FontStyle.Bold);
    private readonly Label _printed = Copy("0", 20, FontStyle.Bold);
    private readonly Label _message = Copy("", color: Muted);
    private readonly TableLayoutPanel _totals = new() { AutoSize = true, ColumnCount = 2, Dock = DockStyle.Fill, Margin = new Padding(0, 0, 0, 12) };
    private readonly LinkLabel _disconnect = new() { Text = "Відключити від магазину", AutoSize = true };
    private readonly Bitmap _logo = LoadLogo();
    private OverallState? _shownState;
    private bool _exiting;

    public MainForm(AgentService agent)
    {
        _agent = agent;
        Text = "ЧудоСкан Друк";
        ClientSize = new Size(544, 520);
        MinimumSize = new Size(460, 480);
        StartPosition = FormStartPosition.CenterScreen;
        AutoScaleMode = AutoScaleMode.Dpi;
        Font = new Font("Segoe UI", 10f);
        BackColor = Background;
        ForeColor = Ink;
        AutoScroll = true;

        var content = Stack();
        content.Padding = new Padding(24, 18, 24, 20);
        var header = new FlowLayoutPanel { AutoSize = true, Dock = DockStyle.Fill, WrapContents = false, Margin = new Padding(0, 0, 0, 14) };
        header.Controls.Add(new PictureBox { Image = _logo, SizeMode = PictureBoxSizeMode.Zoom, Size = new Size(132, 36), AccessibleName = "Чудо Острів", Margin = new Padding(0, 0, 12, 0) });
        var heading = Copy("Друк етикеток", 16, FontStyle.Bold);
        heading.Margin = new Padding(0, 6, 0, 0);
        header.Controls.Add(heading);
        AddRow(content, header);

        AddRow(content, Card(_status, _code, _note));
        AddRow(content, Card(_printer, _printerState));

        _totals.ColumnStyles.Add(new ColumnStyle(SizeType.Percent, 50));
        _totals.ColumnStyles.Add(new ColumnStyle(SizeType.Percent, 50));
        var queueCard = Card(_queue, Copy("У черзі", color: Muted));
        queueCard.Margin = new Padding(0, 0, 6, 0);
        var printedCard = Card(_printed, Copy("Надруковано сьогодні", color: Muted));
        printedCard.Margin = new Padding(6, 0, 0, 0);
        _totals.Controls.Add(queueCard, 0, 0);
        _totals.Controls.Add(printedCard, 1, 0);
        AddRow(content, _totals);

        var actions = new FlowLayoutPanel { AutoSize = true, Dock = DockStyle.Fill, WrapContents = true, Margin = new Padding(0) };
        actions.Controls.Add(ActionButton("Тест етикетки", async () => Say(await _agent.TestLabelAsync()), primary: true));
        actions.Controls.Add(ActionButton("Перевірити зв’язок", () => { _agent.Retry(); Say("Перевіряю…"); return Task.CompletedTask; }));
        actions.Controls.Add(ActionButton("Журнал", () => { OpenLog(); return Task.CompletedTask; }));
        AddRow(content, actions);
        _message.Visible = false;
        AddRow(content, _message);

        _disconnect.LinkColor = Red;
        _disconnect.ActiveLinkColor = Red;
        _disconnect.Margin = new Padding(0, 8, 0, 8);
        _disconnect.LinkClicked += async (_, _) => await DisconnectAsync();
        AddRow(content, _disconnect);
        AddRow(content, Copy($"{_agent.PcName} · версія {AgentService.Version}", 9, color: Muted));
        Controls.Add(content);

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
        _tick.Tick += (_, _) => Render(_agent.Status);
        _tick.Start();
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
            _ => (Muted, "Немає зв'язку"),
        };
        _status.ForeColor = color;
        _status.Text = title;
        if (s.State == OverallState.Pairing && s.Code != null)
        {
            _code.Text = $"{s.Code[..3]} {s.Code[3..]}";
            _code.Visible = true;
            var left = s.CodeExpires is { } exp ? PairingCode.MinutesLeft(exp, DateTimeOffset.UtcNow) : 0;
            _note.Text = $"Введіть код у застосунку: «Профіль → Принтери → Підключити комп'ютер». Дійсний ще {left} хв.";
        }
        else
        {
            _code.Visible = false;
            _note.Text = s.State switch
            {
                OverallState.Ok => s.ShopId != null ? $"Магазин: {s.ShopId}" : s.Text,
                OverallState.Offline => "Перевірте інтернет — програма підключиться сама.",
                _ => s.Text,
            };
        }
        var g = s.Godex.FirstOrDefault();
        _printer.Text = g?.Name ?? "Godex не знайдено";
        _printerState.Text = g is null ? "Перевірте підключення принтера у Windows."
            : g.Text + (g.Shared ? " · спільний принтер" : "");
        _queue.Text = s.Queue.ToString("N0");
        _printed.Text = s.PrintedToday.ToString("N0");
        _totals.Visible = s.Paired;
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

    private static Bitmap LoadLogo()
    {
        using var stream = typeof(MainForm).Assembly.GetManifestResourceStream("ChudoScan.Print.BrandLogo.png")!;
        using var image = Image.FromStream(stream);
        return new Bitmap(image);
    }

    private static Label Copy(string text, float size = 10, FontStyle style = FontStyle.Regular, Color? color = null) =>
        new() { Text = text, AutoSize = true, Font = new Font("Segoe UI", size, style), ForeColor = color ?? Ink, Margin = new Padding(0, 0, 0, 4) };

    private static TableLayoutPanel Stack()
    {
        var stack = new TableLayoutPanel { AutoSize = true, AutoSizeMode = AutoSizeMode.GrowAndShrink, ColumnCount = 1, Dock = DockStyle.Top, Margin = new Padding(0) };
        stack.ColumnStyles.Add(new ColumnStyle(SizeType.Percent, 100));
        return stack;
    }

    private static void AddRow(TableLayoutPanel stack, Control control)
    {
        stack.RowStyles.Add(new RowStyle(SizeType.AutoSize));
        stack.Controls.Add(control, 0, stack.RowCount++);
    }

    private static TableLayoutPanel Card(params Label[] labels)
    {
        var card = Stack();
        card.BackColor = Color.White;
        card.Padding = new Padding(16, 12, 16, 10);
        card.Margin = new Padding(0, 0, 0, 12);
        card.SizeChanged += (_, _) =>
        {
            var width = Math.Max(1, card.ClientSize.Width - card.Padding.Horizontal);
            foreach (var label in labels) label.MaximumSize = new Size(width, 0);
            var diameter = Math.Min(28 * card.DeviceDpi / 96, Math.Min(card.Width, card.Height));
            if (diameter < 2) return;
            using var outline = new GraphicsPath();
            outline.AddArc(0, 0, diameter, diameter, 180, 90);
            outline.AddArc(card.Width - diameter, 0, diameter, diameter, 270, 90);
            outline.AddArc(card.Width - diameter, card.Height - diameter, diameter, diameter, 0, 90);
            outline.AddArc(0, card.Height - diameter, diameter, diameter, 90, 90);
            outline.CloseFigure();
            card.Region = new Region(outline);
        };
        foreach (var label in labels) AddRow(card, label);
        return card;
    }

    private Button ActionButton(string text, Func<Task> action, bool primary = false)
    {
        var button = new Button
        {
            Text = text, AutoSize = true, MinimumSize = new Size(0, 36),
            Padding = new Padding(8, 2, 8, 2), Margin = new Padding(0, 0, 8, 8),
            FlatStyle = FlatStyle.Flat, BackColor = primary ? Orange : Color.White,
            ForeColor = Ink, UseVisualStyleBackColor = false,
        };
        button.FlatAppearance.BorderColor = primary ? Orange : Line;
        button.Click += async (_, _) => await RunAsync(action);
        return button;
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

    private void Say(string text)
    {
        _message.Text = text;
        _message.Visible = true;
    }

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
            _tick.Dispose();
            _logo.Dispose();
            _tray.Icon?.Dispose();
            _tray.Dispose();
        }
        base.Dispose(disposing);
    }
}
