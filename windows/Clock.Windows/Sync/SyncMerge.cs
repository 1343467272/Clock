using System.Text.Json;
using Clock.Windows.Data;
using Clock.Windows.Localization;
using Clock.Windows.Models;

namespace Clock.Windows.Sync;

/// <summary>
/// Builds a full-state snapshot from the local AppState and merges remote snapshots
/// into the local AppState using last-write-wins per record.
/// </summary>
public static class SyncMerge
{
    /// <summary>Builds the local snapshot "as of now".</summary>
    public static SyncSnapshot BuildSnapshot(AppState state)
    {
        lock (state)
        {
            return BuildSnapshotLocked(state);
        }
    }

    private static SyncSnapshot BuildSnapshotLocked(AppState state)
    {
        var sentAt = AppState.NowMs;

        var snapshot = new SyncSnapshot
        {
            DeviceId = state.Settings.DeviceId,
            DeviceName = state.Settings.SyncDeviceName,
            SentAt = sentAt,
        };

        foreach (var a in state.Alarms)
        {
            snapshot.Alarms.Add(new AlarmRecord
            {
                Uuid = a.Uuid,
                UpdatedAt = a.UpdatedAt,
                Enabled = a.Enabled,
                Year = a.Year, Month = a.Month, Day = a.Day,
                Hour = a.Hour, Minute = a.Minute,
                DaysOfWeek = a.DaysOfWeek,
                Label = a.Label,
                Vibrate = a.Vibrate,
                VibrationPattern = a.VibrationPattern,
                Flash = a.Flash,
                Ringtone = a.Ringtone,
                DeleteAfterUse = a.DeleteAfterUse,
                AutoSilenceDuration = a.AutoSilenceDuration,
                SnoozeDuration = a.SnoozeDuration,
                MissedAlarmRepeatLimit = a.MissedAlarmRepeatLimit,
                CrescendoDuration = a.CrescendoDuration,
                AlarmVolume = a.AlarmVolume,
                ManualSortOrder = a.ManualSortOrder,
                PauseStartDate = a.PauseStartDate,
                PauseEndDate = a.PauseEndDate,
                BackgroundImage = a.BackgroundImage,
                BlurIntensity = a.BlurIntensity,
            });
        }

        snapshot.AlarmTombstones = state.AlarmTombstones.Select(t => new Tombstone { Uuid = t.Uuid, UpdatedAt = t.UpdatedAt }).ToList();

        foreach (var t in state.Timers)
        {
            snapshot.Timers.Add(new TimerRecord
            {
                Uuid = t.Uuid,
                UpdatedAt = t.UpdatedAt,
                State = t.State.ToString(),
                Length = t.Length,
                TotalLength = t.TotalLength,
                RemainingTime = t.State == TimerState.RUNNING ? Math.Max(0, t.GetRemaining()) : t.RemainingTime,
                Label = t.Label,
                ButtonTime = t.ButtonTime,
                Ringtone = t.Ringtone,
                AutoSilence = t.AutoSilence,
                CrescendoDuration = t.CrescendoDuration,
                Vibrate = t.Vibrate,
                VibrationPattern = t.VibrationPattern,
                FlashOn = t.FlashOn,
                TurnOffMedia = t.TurnOffMedia,
                DeleteAfterUse = t.DeleteAfterUse,
            });
        }

        snapshot.TimerTombstones = state.TimerTombstones.Select(t => new Tombstone { Uuid = t.Uuid, UpdatedAt = t.UpdatedAt }).ToList();

        snapshot.Stopwatch = new StopwatchRecord
        {
            UpdatedAt = state.Stopwatch.UpdatedAt,
            State = state.Stopwatch.State.ToString(),
            AccumulatedTime = state.Stopwatch.GetElapsed(),
            Laps = state.Stopwatch.Laps.Select(l => new LapRecord { Number = l.Number, AccumulatedTime = l.AccumulatedTime }).ToList(),
        };

        snapshot.Cities = new CitiesRecord
        {
            UpdatedAt = state.CitiesUpdatedAt,
            Ids = state.Cities.ToList(),
            Notes = new Dictionary<string, string>(state.CityNotes),
        };

        snapshot.Settings = new SettingsRecord
        {
            UpdatedAt = state.SyncSettings.Values.Count > 0 ? state.SyncSettings.Values.Max(v => v.Ts) : 0,
            Values = state.SyncSettings.ToDictionary(kv => kv.Key, kv => new SettingValue { Ts = kv.Value.Ts, Value = kv.Value.Value }),
        };

        return snapshot;
    }

    /// <summary>
    /// Merges a remote snapshot into local state (LWW per record).
    /// </summary>
    public static string ApplySnapshot(AppState state, SyncSnapshot remote)
    {
        lock (state)
        {
            return ApplySnapshotLocked(state, remote);
        }
    }

    private static string ApplySnapshotLocked(AppState state, SyncSnapshot remote)
    {
        var changed = false;

        changed |= ApplyAlarms(state, remote);
        changed |= ApplyTimers(state, remote);
        changed |= ApplyStopwatch(state, remote);
        changed |= ApplyCities(state, remote);
        changed |= ApplySettings(state, remote);

        if (changed)
        {
            state.LastSync = DateTime.UtcNow;
            state.LastSyncSummary = string.Format(Text.SyncedWith, remote.DeviceName, DateTime.Now.ToString("HH:mm:ss"));
            state.NotifyChanged();
        }

        return state.LastSyncSummary;
    }

    private static bool ApplyAlarms(AppState state, SyncSnapshot remote)
    {
        var changed = false;

        foreach (var r in remote.Alarms)
        {
            var local = state.Alarms.FirstOrDefault(a => a.Uuid == r.Uuid);
            if (local == null)
            {
                var alarm = new AlarmModel { Uuid = r.Uuid };
                CopyAlarmRecord(alarm, r);
                alarm.UpdatedAt = r.UpdatedAt;
                state.Alarms.Add(alarm);
                changed = true;
            }
            else if (r.UpdatedAt > local.UpdatedAt)
            {
                CopyAlarmRecord(local, r);
                local.UpdatedAt = r.UpdatedAt;
                changed = true;
            }
        }

        foreach (var t in remote.AlarmTombstones)
        {
            var local = state.Alarms.FirstOrDefault(a => a.Uuid == t.Uuid);
            if (local != null)
            {
                if (t.UpdatedAt >= local.UpdatedAt)
                {
                    state.Alarms.Remove(local);
                    changed = true;
                }
            }
            var existing = state.AlarmTombstones.FirstOrDefault(x => x.Uuid == t.Uuid);
            if (existing == null) state.AlarmTombstones.Add(new Tombstone { Uuid = t.Uuid, UpdatedAt = t.UpdatedAt });
            else if (t.UpdatedAt > existing.UpdatedAt) existing.UpdatedAt = t.UpdatedAt;
        }

        return changed;
    }

    private static void CopyAlarmRecord(AlarmModel a, AlarmRecord r)
    {
        a.Enabled = r.Enabled;
        a.Year = r.Year; a.Month = r.Month; a.Day = r.Day;
        a.Hour = r.Hour; a.Minute = r.Minute;
        a.DaysOfWeek = r.DaysOfWeek;
        a.Label = r.Label;
        a.Vibrate = r.Vibrate;
        a.VibrationPattern = r.VibrationPattern;
        a.Flash = r.Flash;
        a.Ringtone = r.Ringtone;
        a.DeleteAfterUse = r.DeleteAfterUse;
        a.AutoSilenceDuration = r.AutoSilenceDuration;
        a.SnoozeDuration = r.SnoozeDuration;
        a.MissedAlarmRepeatLimit = r.MissedAlarmRepeatLimit;
        a.CrescendoDuration = r.CrescendoDuration;
        a.AlarmVolume = r.AlarmVolume;
        a.ManualSortOrder = r.ManualSortOrder;
        a.PauseStartDate = r.PauseStartDate;
        a.PauseEndDate = r.PauseEndDate;
        a.BackgroundImage = r.BackgroundImage;
        a.BlurIntensity = r.BlurIntensity;
    }

    private static bool ApplyTimers(AppState state, SyncSnapshot remote)
    {
        var changed = false;

        foreach (var r in remote.Timers)
        {
            var local = state.Timers.FirstOrDefault(t => t.Uuid == r.Uuid);
            if (local == null)
            {
                var timer = new TimerModel { Uuid = r.Uuid };
                ApplyTimerRecord(state, timer, r, remote.SentAt);
                timer.UpdatedAt = r.UpdatedAt;
                state.Timers.Add(timer);
                changed = true;
            }
            else if (r.UpdatedAt > local.UpdatedAt)
            {
                ApplyTimerRecord(state, local, r, remote.SentAt);
                local.UpdatedAt = r.UpdatedAt;
                changed = true;
            }
        }

        foreach (var t in remote.TimerTombstones)
        {
            var local = state.Timers.FirstOrDefault(x => x.Uuid == t.Uuid);
            if (local != null && t.UpdatedAt >= local.UpdatedAt)
            {
                state.Timers.Remove(local);
                changed = true;
            }
            var existing = state.TimerTombstones.FirstOrDefault(x => x.Uuid == t.Uuid);
            if (existing == null) state.TimerTombstones.Add(new Tombstone { Uuid = t.Uuid, UpdatedAt = t.UpdatedAt });
            else if (t.UpdatedAt > existing.UpdatedAt) existing.UpdatedAt = t.UpdatedAt;
        }

        return changed;
    }

    private static void ApplyTimerRecord(AppState state, TimerModel t, TimerRecord r, long sentAt)
    {
        t.Length = r.Length;
        t.TotalLength = r.TotalLength;
        t.Label = r.Label;
        t.ButtonTime = r.ButtonTime;
        t.Ringtone = r.Ringtone;
        t.AutoSilence = r.AutoSilence;
        t.CrescendoDuration = r.CrescendoDuration;
        t.Vibrate = r.Vibrate;
        t.VibrationPattern = r.VibrationPattern;
        t.FlashOn = r.FlashOn;
        t.TurnOffMedia = r.TurnOffMedia;
        t.DeleteAfterUse = r.DeleteAfterUse;

        if (!Enum.TryParse<TimerState>(r.State, out var st)) st = TimerState.RESET;
        t.State = st;

        switch (st)
        {
            case TimerState.RUNNING:
            {
                // remaining is relative to sentAt; translate to local clock.
                var elapsedSinceSent = Math.Max(0, AppState.NowMs - sentAt);
                var remaining = Math.Max(0, r.RemainingTime - elapsedSinceSent);
                t.ResumeFromRemaining(remaining, DateTime.UtcNow);
                break;
            }
            case TimerState.PAUSED:
            case TimerState.RESET:
                t.RemainingTime = r.RemainingTime;
                t.DueAt = null;
                break;
            case TimerState.EXPIRED:
            case TimerState.MISSED:
                t.RemainingTime = 0;
                t.DueAt = null;
                break;
        }
    }

    private static bool ApplyStopwatch(AppState state, SyncSnapshot remote)
    {
        var r = remote.Stopwatch;
        if (r == null || r.UpdatedAt <= state.Stopwatch.UpdatedAt) return false;

        var elapsedSinceSent = Math.Max(0, AppState.NowMs - r.SentAtFallback(remote));
        if (!Enum.TryParse<StopwatchState>(r.State, out var st)) st = StopwatchState.RESET;

        switch (st)
        {
            case StopwatchState.RUNNING:
                state.Stopwatch.AccumulatedTime = r.AccumulatedTime + elapsedSinceSent;
                state.Stopwatch.State = StopwatchState.RUNNING;
                state.Stopwatch.StartedAtUtc = DateTime.UtcNow;
                break;
            case StopwatchState.PAUSED:
                state.Stopwatch.AccumulatedTime = r.AccumulatedTime;
                state.Stopwatch.State = StopwatchState.PAUSED;
                state.Stopwatch.StartedAtUtc = null;
                break;
            default:
                state.Stopwatch.Reset();
                break;
        }

        state.Stopwatch.Laps = r.Laps.Select(l => new LapRecord { Number = l.Number, AccumulatedTime = l.AccumulatedTime }).ToList();
        state.Stopwatch.UpdatedAt = r.UpdatedAt;
        return true;
    }

    private static bool ApplyCities(AppState state, SyncSnapshot remote)
    {
        var r = remote.Cities;
        if (r == null || r.UpdatedAt <= state.CitiesUpdatedAt) return false;

        state.Cities = r.Ids.Where(CityCatalog.IsValidId).Distinct().ToList();
        state.CityNotes = new Dictionary<string, string>(r.Notes);
        state.CitiesUpdatedAt = r.UpdatedAt;
        return true;
    }

    private static bool ApplySettings(AppState state, SyncSnapshot remote)
    {
        var r = remote.Settings;
        if (r == null || r.Values.Count == 0) return false;

        var changed = false;
        foreach (var kv in r.Values)
        {
            var key = kv.Key;
            var incoming = kv.Value;
            if (state.SyncSettings.TryGetValue(key, out var local) && local.Ts >= incoming.Ts) continue;

            if (TryApplySetting(state.Settings, key, incoming.Value))
            {
                state.SyncSettings[key] = new SettingValue { Ts = incoming.Ts, Value = incoming.Value };
                changed = true;
            }
        }

        return changed;
    }

    private static bool TryApplySetting(AppSettings s, string key, JsonElement? value)
    {
        if (value == null) return false;
        var v = value.Value;

        switch (key)
        {
            case "is24Hour":
                if (v.ValueKind == JsonValueKind.True || v.ValueKind == JsonValueKind.False) { s.Is24Hour = v.GetBoolean(); return true; }
                break;
            case "weekStart":
                if (v.ValueKind == JsonValueKind.String) { s.WeekStart = v.GetString()!; return true; }
                break;
            case "theme":
                if (v.ValueKind == JsonValueKind.String) { s.Theme = v.GetString()!; return true; }
                break;
            case "timerRingtone":
                if (v.ValueKind == JsonValueKind.String) { s.TimerRingtone = v.GetString()!; return true; }
                break;
            case "timerVibrate":
                if (v.ValueKind == JsonValueKind.True || v.ValueKind == JsonValueKind.False) { s.TimerVibrate = v.GetBoolean(); return true; }
                break;
            case "timerVibrationPattern":
                if (v.ValueKind == JsonValueKind.String) { s.TimerVibrationPattern = v.GetString()!; return true; }
                break;
            case "timerFlashOn":
                if (v.ValueKind == JsonValueKind.True || v.ValueKind == JsonValueKind.False) { s.TimerFlashOn = v.GetBoolean(); return true; }
                break;
            case "timerAutoSilence":
                if (v.TryGetInt32(out var tas)) { s.TimerAutoSilence = tas; return true; }
                break;
            case "timerCrescendo":
                if (v.TryGetInt32(out var tc)) { s.TimerCrescendo = tc; return true; }
                break;
            case "timerSort":
                if (v.ValueKind == JsonValueKind.String) { s.TimerSort = v.GetString()!; return true; }
                break;
            case "stopwatchTimeFormat":
                if (v.ValueKind == JsonValueKind.String) { s.StopwatchTimeFormat = v.GetString()!; return true; }
                break;
        }

        return false;
    }

    /// <summary>Registers a local setting change for future sync.</summary>
    public static void RecordSetting(AppState state, string key, JsonElement value)
    {
        state.SyncSettings[key] = new SettingValue { Ts = AppState.NowMs, Value = value };
    }
}

internal static class StopwatchRecordExtensions
{
    public static long SentAtFallback(this StopwatchRecord r, SyncSnapshot remote) => remote.SentAt;
}
