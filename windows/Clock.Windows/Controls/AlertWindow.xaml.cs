using System.Windows;
using System.Windows.Threading;
using Clock.Windows.Localization;
using Clock.Windows.Models;
using Clock.Windows.Services;

namespace Clock.Windows.Controls;

public partial class AlertWindow : Window
{
    private readonly System.Windows.Threading.DispatcherTimer? _soundTimer;
    private Action? _onDismiss;
    private Action? _onSnooze;
    private string _mathHardness = MathChallenge.HardnessOff;
    private bool _actionSatisfied;

    /// <summary>Set before a programmatic <c>Close()</c> (e.g. a remote silence) so a pending
    /// math mission does not block it.</summary>
    public bool BypassMissionOnClose { get; set; }

    /// <summary>UUID of the alarm or timer that opened this alert.</summary>
    public string ItemUuid { get; private set; } = "";

    /// <summary>Whether this alert belongs to a timer (otherwise it belongs to an alarm).</summary>
    public bool IsTimerAlert { get; private set; }

    /// <summary>Alarm silence timestamp when this alert was opened.</summary>
    public long OpenedSilencedAt { get; private set; }

    public AlertWindow()
    {
        InitializeComponent();
        Closing += OnClosing;
        _soundTimer = new System.Windows.Threading.DispatcherTimer { Interval = TimeSpan.FromSeconds(1.5) };
        _soundTimer.Tick += (_, _) =>
        {
            if (!SoundService.IsLooping) SoundService.PlayFallback();
        };
    }

    public static AlertWindow ShowAlarm(string uuid, long silencedAt, string label, string timeText, string mathHardness,
        Action onDismiss, Action onSnooze)
    {
        var w = new AlertWindow();
        w.ItemUuid = uuid;
        w.OpenedSilencedAt = silencedAt;
        w._mathHardness = string.IsNullOrEmpty(mathHardness) ? MathChallenge.HardnessOff : mathHardness;
        w.TitleText.Text = Text.AlertAlarmTitle;
        w.MessageText.Text = string.IsNullOrWhiteSpace(label) ? timeText : $"{label}\n{timeText}";
        w.SnoozeButton.Visibility = Visibility.Visible;
        w._onDismiss = onDismiss;
        w._onSnooze = onSnooze;
        w.Closed += (_, _) => { w._soundTimer?.Stop(); SoundService.Stop(); };
        w.Show();
        w.Activate();
        w._soundTimer.Start();
        SoundService.Start();
        return w;
    }

    public static AlertWindow ShowTimer(string uuid, string label, Action onDismiss)
    {
        var w = new AlertWindow();
        w.ItemUuid = uuid;
        w.IsTimerAlert = true;
        w.TitleText.Text = Text.AlertTimerTitle;
        w.MessageText.Text = string.IsNullOrWhiteSpace(label) ? Text.TimesUp : $"{label}\n{Text.TimesUp}";
        w.SnoozeButton.Visibility = Visibility.Collapsed;
        w._onDismiss = onDismiss;
        w.Closed += (_, _) => { w._soundTimer?.Stop(); SoundService.Stop(); };
        w.Show();
        w.Activate();
        w._soundTimer.Start();
        SoundService.Start();
        return w;
    }

    private void OnDismiss(object sender, RoutedEventArgs e)
    {
        if (!SolveMission(snooze: false)) return;
        _actionSatisfied = true;
        _onDismiss?.Invoke();
        Close();
    }

    private void OnSnooze(object sender, RoutedEventArgs e)
    {
        if (!SolveMission(snooze: true)) return;
        _actionSatisfied = true;
        _onSnooze?.Invoke();
        Close();
    }

    /// <summary>Returns true when no math mission is set or the user solves it.</summary>
    private bool SolveMission(bool snooze)
    {
        if (_mathHardness == MathChallenge.HardnessOff) return true;
        return MathChallengeWindow.ShowDialog(this, _mathHardness, snooze);
    }

    /// <summary>Blocks the title-bar close button while a mission is pending, matching the
    /// non-cancelable Android dialog.</summary>
    private void OnClosing(object? sender, System.ComponentModel.CancelEventArgs e)
    {
        if (_actionSatisfied || BypassMissionOnClose || App.AllowExit) return;
        if (_mathHardness != MathChallenge.HardnessOff) e.Cancel = true;
    }
}
