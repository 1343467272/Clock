using System.Globalization;
using System.Windows;
using System.Windows.Controls;
using System.Windows.Controls.Primitives;
using Clock.Windows.Controls;
using Clock.Windows.Data;
using Clock.Windows.Localization;
using Clock.Windows.Models;
using Clock.Windows.Services;
using Clock.Windows.Sync;

namespace Clock.Windows;

public partial class MainWindow : Window
{
    private readonly AppState _state;
    private AlarmService? _alarmService;
    private SyncEngine? _syncEngine;
    private readonly List<AlertWindow> _openAlerts = new();
    private bool _loading;

    private class LapView
    {
        public string LapLabel { get; set; } = "";
        public string LapTime { get; set; } = "";
    }

    private sealed class WeekStartItem
    {
        public string Value { get; init; } = "";
        public string Display { get; init; } = "";
    }

    public MainWindow()
    {
        InitializeComponent();
        _state = App.State;
        _state.UiDispatcher = Dispatcher;
        Loaded += OnLoaded;
        Closed += (_, _) =>
        {
            _syncEngine?.Dispose();
            _state.Save();
        };
    }

    private void OnLoaded(object sender, RoutedEventArgs e)
    {
        _alarmService = new AlarmService(_state);
        _alarmService.AlarmDue += alarm =>
        {
            Dispatcher.Invoke(() =>
            {
                var w = AlertWindow.ShowAlarm(alarm.LabelText, alarm.TimeText,
                    () => DismissAlarm(alarm),
                    () => SnoozeAlarm(alarm));
                _openAlerts.Add(w);
                w.Closed += (_, _) => _openAlerts.Remove(w);
            });
        };
        _alarmService.TimerExpired += timer =>
        {
            Dispatcher.Invoke(() =>
            {
                var w = AlertWindow.ShowTimer(timer.LabelText, () => { });
                _openAlerts.Add(w);
                w.Closed += (_, _) => _openAlerts.Remove(w);
            });
        };

        LoadSettingsUi();
        RefreshAlarmList();
        RefreshTimerList();
        RefreshStopwatch();
        RefreshCityUi();
        RefreshPeers();
        UpdateClockText();

        _state.Changed += () =>
        {
            RefreshAlarmList();
            RefreshTimerList();
            RefreshStopwatch();
            RefreshCityUi();
            RefreshPeers();
        };

        _state.Ticked += () =>
        {
            UpdateStopwatchText();
            RefreshTimerList();
            UpdateCityTimes();
            UpdateClockText();
        };

        if (_state.Settings.SyncEnabled)
        {
            StartSync();
        }

        SyncEnabledBox.IsChecked = _state.Settings.SyncEnabled;
        SyncStatusText.Text = _state.LastSyncSummary;
    }

    // ---------- Alarms ----------

    private void RefreshAlarmList()
    {
        AlarmList.ItemsSource = _state.Alarms.OrderBy(a => a.Hour * 60 + a.Minute).ToList();
    }

    private void OnAddAlarm(object sender, RoutedEventArgs e)
    {
        var w = new AlarmEditWindow { Owner = this };
        if (w.ShowDialog() == true && w.Result != null)
        {
            var alarm = w.Result;
            alarm.Enabled = true;
            _state.Alarms.Add(alarm);
            _state.TouchAlarm(alarm);
        }
    }

    private void OnEditAlarm(object sender, RoutedEventArgs e)
    {
        var uuid = ((Button)sender).Tag as string;
        var alarm = _state.Alarms.FirstOrDefault(a => a.Uuid == uuid);
        if (alarm == null) return;

        var w = new AlarmEditWindow(alarm) { Owner = this };
        if (w.ShowDialog() == true && w.Result != null)
        {
            var merged = w.Result;
            alarm.Hour = merged.Hour;
            alarm.Minute = merged.Minute;
            alarm.DaysOfWeek = merged.DaysOfWeek;
            alarm.Vibrate = merged.Vibrate;
            alarm.Flash = merged.Flash;
            alarm.DeleteAfterUse = merged.DeleteAfterUse;
            alarm.Label = merged.Label;
            alarm.SnoozeDuration = merged.SnoozeDuration;
            alarm.AutoSilenceDuration = merged.AutoSilenceDuration;
            alarm.SnoozedUntil = null;
            _state.TouchAlarm(alarm);
        }
    }

    private void OnDeleteAlarm(object sender, RoutedEventArgs e)
    {
        var uuid = ((Button)sender).Tag as string;
        var alarm = _state.Alarms.FirstOrDefault(a => a.Uuid == uuid);
        if (alarm == null) return;

        _state.Alarms.Remove(alarm);
        _state.AlarmTombstones.RemoveAll(t => t.Uuid == uuid);
        _state.AlarmTombstones.Add(new Tombstone { Uuid = uuid, UpdatedAt = AppState.NowMs });
        _state.NotifyChanged();
    }

    private void OnToggleAlarm(object sender, RoutedEventArgs e)
    {
        var uuid = ((ToggleButton)sender).Tag as string;
        var alarm = _state.Alarms.FirstOrDefault(a => a.Uuid == uuid);
        if (alarm == null) return;

        alarm.Enabled = ((ToggleButton)sender).IsChecked == true;
        alarm.SnoozedUntil = null;
        _state.TouchAlarm(alarm);
    }

    private void DismissAlarm(AlarmModel alarm) => _alarmService?.AlertClosed(alarm, snoozed: false);
    private void SnoozeAlarm(AlarmModel alarm) => _alarmService?.AlertClosed(alarm, snoozed: true);

    // ---------- Navigation & layout ----------

    private void OnNavChanged(object sender, RoutedEventArgs e)
    {
        if (sender is not RadioButton rb || rb.IsChecked != true) return;
        var tab = rb.Tag as string;

        PanelAlarm.Visibility = tab == "alarm" ? Visibility.Visible : Visibility.Collapsed;
        PanelClock.Visibility = tab == "clock" ? Visibility.Visible : Visibility.Collapsed;
        PanelTimer.Visibility = tab == "timer" ? Visibility.Visible : Visibility.Collapsed;
        PanelStopwatch.Visibility = tab == "stopwatch" ? Visibility.Visible : Visibility.Collapsed;

        ToolbarTitle.Text = tab switch
        {
            "clock" => Text.TabClock,
            "timer" => Text.TabTimer,
            "stopwatch" => Text.TabStopwatch,
            _ => Text.TabAlarms,
        };

        FabButton.Visibility = tab == "stopwatch" ? Visibility.Collapsed : Visibility.Visible;
        FabButton.ToolTip = tab switch
        {
            "clock" => Text.AddCity,
            "timer" => Text.Minutes,
            _ => Text.AddAlarm,
        };
    }

    private string GetSelectedTab()
    {
        if (PanelClock.Visibility == Visibility.Visible) return "clock";
        if (PanelTimer.Visibility == Visibility.Visible) return "timer";
        if (PanelStopwatch.Visibility == Visibility.Visible) return "stopwatch";
        return "alarm";
    }

    private void OnFabClick(object sender, RoutedEventArgs e)
    {
        switch (GetSelectedTab())
        {
            case "clock":
                CitySearchBox.Focus();
                break;
            case "timer":
                TimerMinutesBox.Focus();
                break;
            default:
                OnAddAlarm(sender, e);
                break;
        }
    }

    private void OnSettingsToggle(object sender, RoutedEventArgs e) => SettingsOverlay.Visibility = Visibility.Visible;
    private void OnSettingsClose(object sender, RoutedEventArgs e) => SettingsOverlay.Visibility = Visibility.Collapsed;

    private void UpdateClockText()
    {
        var now = DateTime.Now;
        var culture = CultureInfo.GetCultureInfo("zh-CN");
        ClockText.Text = _state.Settings.Is24Hour
            ? now.ToString("HH:mm:ss", culture)
            : now.ToString("h:mm:ss tt", culture);
        ClockDateText.Text = now.ToString("yyyy年M月d日 dddd", culture);
    }

    // ---------- Timers ----------

    private void RefreshTimerList()
    {
        var items = _state.Timers.OrderByDescending(t => t.UpdatedAt).ToList();
        TimerList.ItemsSource = items.ToList();
    }

    private void OnStartTimer(object sender, RoutedEventArgs e)
    {
        if (!int.TryParse(TimerMinutesBox.Text, out var minutes)) minutes = 0;
        if (!int.TryParse(TimerSecondsBox.Text, out var seconds)) seconds = 0;
        var total = Math.Max(1, minutes * 60 + seconds);
        if (minutes <= 0 && seconds <= 0) return;

        var timer = new TimerModel
        {
            Length = total * 1000L,
            TotalLength = total * 1000L,
            RemainingTime = total * 1000L,
            Label = TimerLabelBox.Text.Trim(),
        };
        timer.Start(DateTime.UtcNow);
        _state.Timers.Add(timer);
        _state.TouchTimer(timer);
        TimerLabelBox.Clear();
    }

    private void OnTimerStartPause(object sender, RoutedEventArgs e)
    {
        var uuid = ((Button)sender).Tag as string;
        var t = _state.Timers.FirstOrDefault(x => x.Uuid == uuid);
        if (t == null) return;

        if (t.State == TimerState.RUNNING) t.Pause();
        else t.Start(DateTime.UtcNow);
        _state.TouchTimer(t);
    }

    private void OnTimerAdd(object sender, RoutedEventArgs e)
    {
        var uuid = ((Button)sender).Tag as string;
        var t = _state.Timers.FirstOrDefault(x => x.Uuid == uuid);
        if (t == null) return;
        if (!int.TryParse(t.ButtonTime, out var secs) || secs <= 0) secs = 60;
        t.AddTime(secs / 60.0 < 1 ? 1 : secs / 60);
        _state.TouchTimer(t);
    }

    private void OnTimerReset(object sender, RoutedEventArgs e)
    {
        var uuid = ((Button)sender).Tag as string;
        var t = _state.Timers.FirstOrDefault(x => x.Uuid == uuid);
        if (t == null) return;
        t.Reset();
        _state.TouchTimer(t);
    }

    private void OnTimerDelete(object sender, RoutedEventArgs e)
    {
        var uuid = ((Button)sender).Tag as string;
        var t = _state.Timers.FirstOrDefault(x => x.Uuid == uuid);
        if (t == null) return;

        _state.Timers.Remove(t);
        _state.TimerTombstones.RemoveAll(x => x.Uuid == uuid);
        _state.TimerTombstones.Add(new Tombstone { Uuid = uuid, UpdatedAt = AppState.NowMs });
        _state.NotifyChanged();
    }

    // ---------- Stopwatch ----------

    private void RefreshStopwatch() => UpdateStopwatchText();

    private void UpdateStopwatchText()
    {
        var elapsed = TimeSpan.FromMilliseconds(_state.Stopwatch.GetElapsed());
        StopwatchText.Text = $"{(int)elapsed.TotalHours:00}:{elapsed.Minutes:00}:{elapsed.Seconds:00}.{elapsed.Milliseconds / 10:00}";
        StopwatchStartPauseBtn.Content = _state.Stopwatch.State == StopwatchState.RUNNING ? Text.Pause : Text.Start;
        RefreshLapList();
    }

    private void RefreshLapList()
    {
        var laps = _state.Stopwatch.Laps
            .OrderByDescending(l => l.Number)
            .Select(l =>
            {
                var ts = TimeSpan.FromMilliseconds(l.AccumulatedTime);
                return new LapView { LapLabel = string.Format(Text.LapFormat, l.Number), LapTime = $"{ts.Minutes:00}:{ts.Seconds:00}.{ts.Milliseconds / 10:00}" };
            })
            .ToList();
        LapList.ItemsSource = laps;
    }

    private void OnStopwatchStartPause(object sender, RoutedEventArgs e)
    {
        if (_state.Stopwatch.State == StopwatchState.RUNNING) _state.Stopwatch.Pause();
        else _state.Stopwatch.Start();
        _state.TouchStopwatch();
    }

    private void OnStopwatchLap(object sender, RoutedEventArgs e)
    {
        if (_state.Stopwatch.State != StopwatchState.RUNNING) return;
        _state.Stopwatch.AddLap();
        _state.TouchStopwatch();
    }

    private void OnStopwatchReset(object sender, RoutedEventArgs e)
    {
        _state.Stopwatch.Reset();
        _state.TouchStopwatch();
    }

    // ---------- World clock ----------

    private void RefreshCityUi()
    {
        var selected = _state.Cities.Select(CityCatalog.ById).Where(c => c != null).Select(c => c!).ToList();
        CityList.ItemsSource = selected;

        var search = CitySearchBox.Text.Trim().ToLowerInvariant();
        var shown = string.IsNullOrEmpty(search)
            ? CityCatalog.All
            : CityCatalog.All.Where(c => c.Name.ToLowerInvariant().Contains(search) || c.DisplayName.ToLowerInvariant().Contains(search) || c.Tz.Contains(search)).ToList();
        CityCatalogList.ItemsSource = shown;
        CityCatalogList.SelectedItems.Clear();
    }

    private void UpdateCityTimes()
    {
        if (CityList.ItemsSource is List<City> list) CityList.ItemsSource = list.ToList();
    }

    private void OnAddCity(object sender, RoutedEventArgs e) => CitySearchBox.Focus();

    private void CitySearchBox_TextChanged(object sender, TextChangedEventArgs e)
    {
        if (!IsLoaded) return;
        var search = CitySearchBox.Text.Trim().ToLowerInvariant();
        var shown = string.IsNullOrEmpty(search)
            ? CityCatalog.All
            : CityCatalog.All.Where(c => c.Name.ToLowerInvariant().Contains(search) || c.DisplayName.ToLowerInvariant().Contains(search) || c.Tz.Contains(search)).ToList();
        CityCatalogList.ItemsSource = shown;
    }

    private void CityCatalogList_SelectionChanged(object sender, SelectionChangedEventArgs e)
    {
        var added = false;
        foreach (City city in e.AddedItems)
        {
            if (!_state.Cities.Contains(city.Id))
            {
                _state.Cities.Add(city.Id);
                added = true;
            }
        }
        if (added) _state.TouchCities();
        RefreshCityUi();
    }

    private void OnRemoveCity(object sender, RoutedEventArgs e)
    {
        var id = ((Button)sender).Tag as string;
        if (id == null) return;
        _state.Cities.Remove(id);
        _state.TouchCities();
    }

    // ---------- Settings ----------

    private void LoadSettingsUi()
    {
        _loading = true;
        Is24HourBox.IsChecked = _state.Settings.Is24Hour;
        var weekStarts = new[]
        {
            new WeekStartItem { Value = "sunday", Display = Text.WeekStartSunday },
            new WeekStartItem { Value = "monday", Display = Text.WeekStartMonday },
            new WeekStartItem { Value = "saturday", Display = Text.WeekStartSaturday },
        };
        WeekStartBox.ItemsSource = weekStarts;
        WeekStartBox.SelectedItem = weekStarts.FirstOrDefault(w => w.Value == _state.Settings.WeekStart);
        ThemeBox.SelectedIndex = _state.Settings.Theme switch
        {
            "light" => 1,
            "dark" => 2,
            _ => 0,
        };
        AlarmSnoozeBox.Text = _state.Settings.AlarmSnoozeMinutes.ToString();
        AlarmAutoSilenceBox.Text = _state.Settings.AlarmAutoSilenceSeconds.ToString();
        TimerVibrateBox.IsChecked = _state.Settings.TimerVibrate;
        TimerFlashBox.IsChecked = _state.Settings.TimerFlashOn;
        TimerAutoSilenceBox.IsChecked = _state.Settings.TimerAutoSilence > 0;
        SyncNameBox.Text = _state.Settings.SyncDeviceName;
        SyncPortBox.Text = _state.Settings.SyncPort.ToString();
        _loading = false;
    }

    private void OnSettingChanged(object sender, RoutedEventArgs e)
    {
        if (!IsLoaded || _loading) return;

        if (sender == Is24HourBox && _state.Settings.Is24Hour != Is24HourBox.IsChecked)
        {
            _state.Settings.Is24Hour = Is24HourBox.IsChecked == true;
            SyncMerge.RecordSetting(_state, "is24Hour", System.Text.Json.JsonSerializer.SerializeToElement(_state.Settings.Is24Hour));
        }

        if (sender == WeekStartBox && WeekStartBox.SelectedItem is WeekStartItem ws)
        {
            _state.Settings.WeekStart = ws.Value;
            SyncMerge.RecordSetting(_state, "weekStart", System.Text.Json.JsonSerializer.SerializeToElement(ws.Value));
        }

        if (sender == ThemeBox)
        {
            var theme = ThemeBox.SelectedIndex switch { 1 => "light", 2 => "dark", _ => "system" };
            _state.Settings.Theme = theme;
            SyncMerge.RecordSetting(_state, "theme", System.Text.Json.JsonSerializer.SerializeToElement(theme));
            ThemeManager.Apply(theme);
        }

        if (sender == AlarmSnoozeBox && int.TryParse(AlarmSnoozeBox.Text, out var snooze) && snooze > 0)
        {
            _state.Settings.AlarmSnoozeMinutes = snooze;
        }

        if (sender == AlarmAutoSilenceBox && int.TryParse(AlarmAutoSilenceBox.Text, out var silence) && silence >= 0)
        {
            _state.Settings.AlarmAutoSilenceSeconds = silence;
        }

        if (sender == TimerVibrateBox)
        {
            _state.Settings.TimerVibrate = TimerVibrateBox.IsChecked == true;
            SyncMerge.RecordSetting(_state, "timerVibrate", System.Text.Json.JsonSerializer.SerializeToElement(_state.Settings.TimerVibrate));
        }

        if (sender == TimerFlashBox)
        {
            _state.Settings.TimerFlashOn = TimerFlashBox.IsChecked == true;
            SyncMerge.RecordSetting(_state, "timerFlashOn", System.Text.Json.JsonSerializer.SerializeToElement(_state.Settings.TimerFlashOn));
        }

        if (sender == TimerAutoSilenceBox)
        {
            _state.Settings.TimerAutoSilence = TimerAutoSilenceBox.IsChecked == true ? 600 : 0;
            SyncMerge.RecordSetting(_state, "timerAutoSilence", System.Text.Json.JsonSerializer.SerializeToElement(_state.Settings.TimerAutoSilence));
        }

        if (sender == SyncEnabledBox)
        {
            _state.Settings.SyncEnabled = SyncEnabledBox.IsChecked == true;
            if (_state.Settings.SyncEnabled) StartSync();
            else StopSync();
        }

        if (sender == SyncNameBox)
        {
            _state.Settings.SyncDeviceName = string.IsNullOrWhiteSpace(SyncNameBox.Text) ? Environment.MachineName : SyncNameBox.Text.Trim();
        }

        if (sender == SyncPortBox && int.TryParse(SyncPortBox.Text, out var port) && port > 0 && port < 65536)
        {
            var old = _state.Settings.SyncPort;
            _state.Settings.SyncPort = port;
            if (old != port && _state.Settings.SyncEnabled)
            {
                // Port changed while running: restart the listener.
                RestartSync();
            }
        }

        _state.NotifyChanged();
    }

    private void OnSaveNow(object sender, RoutedEventArgs e) => _state.Save();

    // ---------- Sync ----------

    private void StartSync()
    {
        if (_syncEngine != null) return;
        _syncEngine = new SyncEngine(_state);
        _syncEngine.PeersChanged += () => Dispatcher.Invoke(RefreshPeers);
        _syncEngine.Synced += () => Dispatcher.Invoke(() =>
        {
            SyncStatusText.Text = _state.LastSyncSummary;
        });
        _syncEngine.Start();
    }

    private void StopSync()
    {
        _syncEngine?.Dispose();
        _syncEngine = null;
    }

    private void RestartSync()
    {
        StopSync();
        StartSync();
    }

    private void OnSyncNow(object sender, RoutedEventArgs e)
    {
        var peer = _state.Peers.OrderByDescending(p => p.LastSeen).FirstOrDefault();
        if (peer == null)
        {
            SyncStatusText.Text = Text.NoDevicesDiscovered;
            return;
        }

        SyncStatusText.Text = string.Format(Text.SyncingWith, peer.DeviceName);
        _syncEngine?.PeerDiscovered(peer);
    }

    private void RefreshPeers()
    {
        var items = _state.Peers
            .OrderByDescending(p => p.LastSeen)
            .Select(p => new
            {
                p.DeviceName,
                Detail = $"{p.Address}:{p.Port}{string.Format(Text.LastSeen, p.LastSeen.ToLocalTime().ToString("HH:mm:ss"))}",
            })
            .ToList();
        PeerList.ItemsSource = items;
    }
}
