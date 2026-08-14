using System.Media;
using System.Windows;
using System.Windows.Threading;
using Clock.Windows.Localization;

namespace Clock.Windows.Controls;

public partial class AlertWindow : Window
{
    private readonly System.Windows.Threading.DispatcherTimer? _soundTimer;
    private Action? _onDismiss;
    private Action? _onSnooze;

    public AlertWindow()
    {
        InitializeComponent();
        _soundTimer = new System.Windows.Threading.DispatcherTimer { Interval = TimeSpan.FromSeconds(1.5) };
        _soundTimer.Tick += (_, _) =>
        {
            try { SystemSounds.Asterisk.Play(); } catch { }
        };
    }

    public static AlertWindow ShowAlarm(string label, string timeText, Action onDismiss, Action onSnooze)
    {
        var w = new AlertWindow();
        w.TitleText.Text = Text.AlertAlarmTitle;
        w.MessageText.Text = string.IsNullOrWhiteSpace(label) ? timeText : $"{label}\n{timeText}";
        w.SnoozeButton.Visibility = Visibility.Visible;
        w._onDismiss = onDismiss;
        w._onSnooze = onSnooze;
        w.Closed += (_, _) => w._soundTimer?.Stop();
        w.Show();
        w.Activate();
        w._soundTimer.Start();
        try { SystemSounds.Asterisk.Play(); } catch { }
        return w;
    }

    public static AlertWindow ShowTimer(string label, Action onDismiss)
    {
        var w = new AlertWindow();
        w.TitleText.Text = Text.AlertTimerTitle;
        w.MessageText.Text = string.IsNullOrWhiteSpace(label) ? Text.TimesUp : $"{label}\n{Text.TimesUp}";
        w.SnoozeButton.Visibility = Visibility.Collapsed;
        w._onDismiss = onDismiss;
        w.Closed += (_, _) => w._soundTimer?.Stop();
        w.Show();
        w.Activate();
        w._soundTimer.Start();
        try { SystemSounds.Exclamation.Play(); } catch { }
        return w;
    }

    private void OnDismiss(object sender, RoutedEventArgs e)
    {
        _onDismiss?.Invoke();
        Close();
    }

    private void OnSnooze(object sender, RoutedEventArgs e)
    {
        _onSnooze?.Invoke();
        Close();
    }
}
