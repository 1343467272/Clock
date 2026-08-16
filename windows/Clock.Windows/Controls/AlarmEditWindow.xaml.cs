using System.Collections.ObjectModel;
using System.Windows;
using Clock.Windows.Localization;
using Clock.Windows.Models;

namespace Clock.Windows.Controls;

public partial class AlarmEditWindow : Window
{
    public AlarmModel? Result { get; private set; }

    private class DayToggle
    {
        public string Label { get; set; } = "";
        public bool IsOn { get; set; }
        public DayOfWeek Day { get; set; }
    }

    private readonly ObservableCollection<DayToggle> _days = new();

    public AlarmEditWindow(AlarmModel? existing = null)
    {
        InitializeComponent();

        for (int h = 0; h < 24; h++) HourBox.Items.Add(h.ToString("00"));
        for (int m = 0; m < 60; m++) MinuteBox.Items.Add(m.ToString("00"));
        HourBox.SelectedIndex = existing?.Hour ?? 7;
        MinuteBox.SelectedIndex = existing?.Minute ?? 0;

        _days.Add(new DayToggle { Label = Text.FullDayName(DayOfWeek.Monday), Day = DayOfWeek.Monday });
        _days.Add(new DayToggle { Label = Text.FullDayName(DayOfWeek.Tuesday), Day = DayOfWeek.Tuesday });
        _days.Add(new DayToggle { Label = Text.FullDayName(DayOfWeek.Wednesday), Day = DayOfWeek.Wednesday });
        _days.Add(new DayToggle { Label = Text.FullDayName(DayOfWeek.Thursday), Day = DayOfWeek.Thursday });
        _days.Add(new DayToggle { Label = Text.FullDayName(DayOfWeek.Friday), Day = DayOfWeek.Friday });
        _days.Add(new DayToggle { Label = Text.FullDayName(DayOfWeek.Saturday), Day = DayOfWeek.Saturday });
        _days.Add(new DayToggle { Label = Text.FullDayName(DayOfWeek.Sunday), Day = DayOfWeek.Sunday });
        if (existing != null)
        {
            foreach (var d in _days) d.IsOn = existing.GetRepeatDays().Contains(d.Day);
        }
        DayToggleList.ItemsSource = _days;

        RepeatTypeBox.Items.Add(Text.RepeatTypeOnce);    // 0 = none
        RepeatTypeBox.Items.Add(Text.RepeatTypeWeekly);  // 1 = weekly
        RepeatTypeBox.Items.Add(Text.RepeatTypeWorkday); // 2 = workday
        RepeatTypeBox.Items.Add(Text.RepeatTypeShift);   // 3 = shift
        RepeatTypeBox.SelectedIndex = existing?.RepeatType ?? AlarmModel.RepeatTypeNone;

        for (int i = 1; i <= 30; i++)
        {
            ShiftWorkBox.Items.Add(i);
            ShiftRestBox.Items.Add(i);
        }
        ShiftWorkBox.SelectedItem = existing?.ShiftWorkDays ?? 5;
        ShiftRestBox.SelectedItem = existing?.ShiftRestDays ?? 2;
        ShiftStartDatePicker.SelectedDate =
            existing != null && existing.ShiftStartDate > 0
                ? DateTimeOffset.FromUnixTimeMilliseconds(existing.ShiftStartDate).UtcDateTime.Date
                : DateTime.Today;

        UpdateRepeatUi();

        VibrateBox.IsChecked = existing?.Vibrate ?? true;
        FlashBox.IsChecked = existing?.Flash ?? true;
        DeleteAfterUseBox.IsChecked = existing?.DeleteAfterUse ?? false;
        LabelBox.Text = existing?.Label ?? "";

        foreach (var v in new[] { 1, 5, 10, 15, 20, 30 }) SnoozeBox.Items.Add(v);
        foreach (var v in new[] { 0, 60, 300, 600, 900, 1800 }) AutoSilenceBox.Items.Add(v);
        SnoozeBox.SelectedItem = existing?.SnoozeDuration ?? 10;
        AutoSilenceBox.SelectedItem = existing?.AutoSilenceDuration ?? 600;
        if (existing != null)
        {
            if (!SnoozeBox.Items.Contains(existing.SnoozeDuration)) SnoozeBox.Items.Add(existing.SnoozeDuration);
            if (!AutoSilenceBox.Items.Contains(existing.AutoSilenceDuration)) AutoSilenceBox.Items.Add(existing.AutoSilenceDuration);
            SnoozeBox.SelectedItem = existing.SnoozeDuration;
            AutoSilenceBox.SelectedItem = existing.AutoSilenceDuration;
        }
    }

    private void OnCancel(object sender, RoutedEventArgs e) => DialogResult = false;

    private void OnRepeatTypeChanged(object sender, System.Windows.Controls.SelectionChangedEventArgs e) => UpdateRepeatUi();

    private void OnShiftCycleChanged(object sender, System.Windows.Controls.SelectionChangedEventArgs e) => UpdateShiftHint();

    private void UpdateRepeatUi()
    {
        var repeatType = RepeatTypeBox.SelectedIndex;
        bool weekly = repeatType == AlarmModel.RepeatTypeWeekly;
        bool shift = repeatType == AlarmModel.RepeatTypeShift;

        DayToggleList.IsEnabled = weekly;
        ShiftPanel.Visibility = shift ? Visibility.Visible : Visibility.Collapsed;
        ShiftDatePanel.Visibility = shift ? Visibility.Visible : Visibility.Collapsed;

        if (repeatType == AlarmModel.RepeatTypeWorkday)
        {
            RepeatTypeHint.Text = Text.StatutoryWorkdays;
        }
        else if (shift)
        {
            UpdateShiftHint();
        }
        else
        {
            RepeatTypeHint.Text = "";
        }
    }

    private void UpdateShiftHint()
    {
        int work = ShiftWorkBox.SelectedItem is int w ? w : 5;
        int rest = ShiftRestBox.SelectedItem is int r ? r : 2;
        RepeatTypeHint.Text = string.Format(Text.ShiftRepeatSummary, work, rest);
    }

    private void OnSave(object sender, RoutedEventArgs e)
    {
        var a = new AlarmModel
        {
            Hour = HourBox.SelectedIndex,
            Minute = MinuteBox.SelectedIndex,
            Vibrate = VibrateBox.IsChecked == true,
            Flash = FlashBox.IsChecked == true,
            DeleteAfterUse = DeleteAfterUseBox.IsChecked == true,
            Label = LabelBox.Text.Trim(),
            SnoozeDuration = SnoozeBox.SelectedItem is int s ? s : 10,
            AutoSilenceDuration = AutoSilenceBox.SelectedItem is int a2 ? a2 : 600,
            RepeatType = RepeatTypeBox.SelectedIndex,
        };
        if (a.RepeatType == AlarmModel.RepeatTypeShift)
        {
            a.ShiftWorkDays = ShiftWorkBox.SelectedItem is int w ? w : 5;
            a.ShiftRestDays = ShiftRestBox.SelectedItem is int r ? r : 2;
            var dt = ShiftStartDatePicker.SelectedDate ?? DateTime.Today;
            a.ShiftStartDate = new DateTimeOffset(dt.Date, TimeSpan.Zero).ToUnixTimeMilliseconds();
        }
        foreach (var d in _days)
        {
            if (d.IsOn) a.SetDay(d.Day, true);
        }
        Result = a;
        DialogResult = true;
    }
}
