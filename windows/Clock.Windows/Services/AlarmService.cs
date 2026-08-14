using Clock.Windows.Data;
using Clock.Windows.Models;

namespace Clock.Windows.Services;

/// <summary>
/// Watches the ticking AppState and raises events when an alarm is due, a timer expires,
/// or a running timer should move to the expired state.
/// </summary>
public class AlarmService
{
    private readonly AppState _state;
    private readonly Dictionary<string, DateTime> _lastAlarmFired = new();
    private readonly HashSet<string> _activeAlerts = new();

    public event Action<AlarmModel>? AlarmDue;
    public event Action<TimerModel>? TimerExpired;
    public event Action? SnoozeDismissed;

    public AlarmService(AppState state)
    {
        _state = state;
        _state.Ticked += OnTick;
    }

    private void OnTick()
    {
        CheckAlarms();
        CheckTimers();
    }

    private void CheckAlarms()
    {
        var now = DateTime.Now;
        foreach (var alarm in _state.Alarms)
        {
            if (!alarm.Enabled) continue;
            if (_activeAlerts.Contains(alarm.Uuid)) continue;

            var fireTime = alarm.GetNextFireTime(now);

            // Skip if already fired for this occurrence.
            if (_lastAlarmFired.TryGetValue(alarm.Uuid, out var lastFired) && lastFired == fireTime) continue;

            if (now >= fireTime)
            {
                if (alarm.SnoozedUntil.HasValue && alarm.SnoozedUntil <= now) alarm.SnoozedUntil = null;
                _lastAlarmFired[alarm.Uuid] = fireTime;
                _activeAlerts.Add(alarm.Uuid);
                AlarmDue?.Invoke(alarm);
            }
        }
    }

    private void CheckTimers()
    {
        var now = DateTime.UtcNow;
        foreach (var timer in _state.Timers)
        {
            if (timer.State != TimerState.RUNNING) continue;
            if (timer.DueAt.HasValue && now >= timer.DueAt.Value)
            {
                timer.State = TimerState.EXPIRED;
                timer.RemainingTime = 0;
                timer.DueAt = null;
                timer.UpdatedAt = AppState.NowMs;
                TimerExpired?.Invoke(timer);
                _state.Save();
            }
        }
    }

    public void AlertClosed(AlarmModel alarm, bool snoozed)
    {
        _activeAlerts.Remove(alarm.Uuid);
        if (snoozed)
        {
            alarm.SnoozedUntil = DateTime.Now.AddMinutes(Math.Max(1, _state.Settings.AlarmSnoozeMinutes));
            _lastAlarmFired[alarm.Uuid] = DateTime.MinValue; // allow refiring at snooze time
            SnoozeDismissed?.Invoke();
        }
        else
        {
            // Dismissed.
            if (alarm.IsRepeating)
            {
                _lastAlarmFired[alarm.Uuid] = alarm.GetNextFireTime(DateTime.Now); // schedule next day
            }
            else if (alarm.DeleteAfterUse)
            {
                _state.Alarms.Remove(alarm);
            }
            else
            {
                alarm.Enabled = false;
                alarm.UpdatedAt = AppState.NowMs;
            }
        }
        _state.NotifyChanged();
    }
}
