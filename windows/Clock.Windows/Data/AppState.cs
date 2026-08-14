using System.IO;
using System.Text.Json;
using System.Windows.Threading;
using Clock.Windows.Models;

namespace Clock.Windows.Data;

/// <summary>
/// Central application state: settings, alarms, timers, stopwatch, world-clock cities.
/// Persisted as JSON in %AppData%\Clock\windows.json.
/// </summary>
public class AppState
{
    public static long NowMs => DateTimeOffset.UtcNow.ToUnixTimeMilliseconds();

    /// <summary>Dispatcher used to raise <see cref="Changed"/> on the UI thread.</summary>
    public Dispatcher UiDispatcher { get; set; } = Dispatcher.CurrentDispatcher;

    private static readonly string Dir =
        Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.ApplicationData), "Clock");

    private static readonly string FilePath = Path.Combine(Dir, "windows.json");

    /// <summary>Raised on the UI thread whenever local data changes (user or remote).</summary>
    public event Action? Changed;

    /// <summary>Raised on the UI thread only for local, user-initiated changes. Remote-apply
    /// notifications are suppressed so the sync engine does not push back what it just received.</summary>
    public event Action? UserChanged;

    public event Action? Ticked;

    /// <summary>True while a remote snapshot is being applied, so the merge's own refresh does not
    /// count as a user change.</summary>
    public bool ApplyingRemote { get; private set; }

    private readonly object _lock = new();
    private DispatcherTimer? _ticker;

    public AppSettings Settings { get; set; } = new();
    public List<AlarmModel> Alarms { get; set; } = new();
    public List<TimerModel> Timers { get; set; } = new();
    public StopwatchModel Stopwatch { get; set; } = new();
    public List<string> Cities { get; set; } = new();
    public List<SyncPeerInfo> Peers { get; set; } = new();

    // Sync bookkeeping (persisted)
    public List<Tombstone> AlarmTombstones { get; set; } = new();
    public List<Tombstone> TimerTombstones { get; set; } = new();
    public long CitiesUpdatedAt { get; set; }
    public Dictionary<string, string> CityNotes { get; set; } = new();
    public Dictionary<string, SettingValue> SyncSettings { get; set; } = new();

    public DateTime LastSaved { get; set; } = DateTime.UtcNow;
    public DateTime LastSync { get; set; } = DateTime.MinValue;
    public string LastSyncSummary { get; set; } = "";

    public void Start()
    {
        _ticker = new DispatcherTimer { Interval = TimeSpan.FromSeconds(1) };
        _ticker.Tick += (_, _) => Ticked?.Invoke();
        _ticker.Start();
    }

    public void Stop()
    {
        _ticker?.Stop();
        Save();
    }

    public void BeginRemoteApply() => ApplyingRemote = true;
    public void EndRemoteApply() => ApplyingRemote = false;

    public void NotifyChanged()
    {
        var isRemote = ApplyingRemote;
        UiDispatcher.BeginInvoke(() =>
        {
            Changed?.Invoke();
            if (!isRemote) UserChanged?.Invoke();
        });
        Save();
    }

    public void TouchAlarm(AlarmModel a)
    {
        a.UpdatedAt = NowMs;
        NotifyChanged();
    }

    public void TouchTimer(TimerModel t)
    {
        t.UpdatedAt = NowMs;
        NotifyChanged();
    }

    public void TouchStopwatch()
    {
        Stopwatch.UpdatedAt = NowMs;
        NotifyChanged();
    }

    public void TouchCities()
    {
        CitiesUpdatedAt = NowMs;
        NotifyChanged();
    }

    public void Save()
    {
        lock (_lock)
        {
            try
            {
                Directory.CreateDirectory(Dir);
                var snapshot = BuildSnapshot();
                var json = JsonSerializer.Serialize(snapshot, new JsonSerializerOptions { WriteIndented = false });
                var tmp = FilePath + ".tmp";
                File.WriteAllText(tmp, json);
                File.Move(tmp, FilePath, overwrite: true);
                LastSaved = DateTime.UtcNow;
            }
            catch
            {
                // Ignore persistence errors (e.g. locked file); data stays in memory.
            }
        }
    }

    public static AppState Load()
    {
        var state = new AppState();
        try
        {
            if (File.Exists(FilePath))
            {
                var json = File.ReadAllText(FilePath);
                var loaded = JsonSerializer.Deserialize<DiskState>(json);
                if (loaded != null)
                {
                    state.Settings = loaded.Settings ?? new AppSettings();
                    state.Alarms = loaded.Alarms ?? new List<AlarmModel>();
                    state.Timers = loaded.Timers ?? new List<TimerModel>();
                    state.Stopwatch = loaded.Stopwatch ?? new StopwatchModel();
                    state.Cities = loaded.Cities ?? new List<string>();
                    state.Peers = loaded.Peers ?? new List<SyncPeerInfo>();
                    state.AlarmTombstones = loaded.AlarmTombstones ?? new List<Tombstone>();
                    state.TimerTombstones = loaded.TimerTombstones ?? new List<Tombstone>();
                    state.CitiesUpdatedAt = loaded.CitiesUpdatedAt;
                    state.CityNotes = loaded.CityNotes ?? new Dictionary<string, string>();
                    state.SyncSettings = loaded.SyncSettings ?? new Dictionary<string, SettingValue>();
                    state.LastSync = loaded.LastSync;
                    state.LastSyncSummary = loaded.LastSyncSummary;
                }
            }
        }
        catch
        {
            // Fall back to defaults on any parse error.
        }

        state.RehydrateRuntime();
        return state;
    }

    /// <summary>Restores derived runtime fields (timer due times, stopwatch start time).</summary>
    private void RehydrateRuntime()
    {
        var now = DateTime.UtcNow;
        foreach (var t in Timers)
        {
            if (t.State == TimerState.RUNNING)
            {
                t.DueAt = now.AddMilliseconds(t.RemainingTime);
                t.LocalLastStartElapsed = Environment.TickCount64;
            }
        }
        if (Stopwatch.State == StopwatchState.RUNNING)
        {
            Stopwatch.StartedAtUtc = now;
        }
    }

    private DiskState BuildSnapshot()
    {
        // Freeze running timers' remaining time so reload is accurate.
        var now = DateTime.UtcNow;
        foreach (var t in Timers)
        {
            if (t.State == TimerState.RUNNING)
            {
                t.RemainingTime = Math.Max(0, t.GetRemaining());
                t.DueAt = now.AddMilliseconds(t.RemainingTime);
            }
        }
        if (Stopwatch.State == StopwatchState.RUNNING)
        {
            Stopwatch.AccumulatedTime = Stopwatch.GetElapsed();
            Stopwatch.StartedAtUtc = now;
        }

        return new DiskState
        {
            Settings = Settings,
            Alarms = Alarms,
            Timers = Timers,
            Stopwatch = Stopwatch,
            Cities = Cities,
            Peers = Peers,
            AlarmTombstones = AlarmTombstones,
            TimerTombstones = TimerTombstones,
            CitiesUpdatedAt = CitiesUpdatedAt,
            CityNotes = CityNotes,
            SyncSettings = SyncSettings,
            LastSync = LastSync,
            LastSyncSummary = LastSyncSummary,
        };
    }

    private class DiskState
    {
        public AppSettings? Settings { get; set; }
        public List<AlarmModel>? Alarms { get; set; }
        public List<TimerModel>? Timers { get; set; }
        public StopwatchModel? Stopwatch { get; set; }
        public List<string>? Cities { get; set; }
        public List<SyncPeerInfo>? Peers { get; set; }
        public List<Tombstone>? AlarmTombstones { get; set; }
        public List<Tombstone>? TimerTombstones { get; set; }
        public long CitiesUpdatedAt { get; set; }
        public Dictionary<string, string>? CityNotes { get; set; }
        public Dictionary<string, SettingValue>? SyncSettings { get; set; }
        public DateTime LastSync { get; set; }
        public string LastSyncSummary { get; set; } = "";
    }
}
