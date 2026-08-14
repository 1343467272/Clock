using System.Text.Json.Serialization;

namespace Clock.Windows.Models;

public enum StopwatchState
{
    RESET = 0,
    RUNNING = 1,
    PAUSED = 2,
}

public class LapRecord
{
    public int Number { get; set; }
    public long AccumulatedTime { get; set; }
}

public class StopwatchModel
{
    public StopwatchState State { get; set; } = StopwatchState.RESET;

    /// <summary>Accumulated ms while running.</summary>
    public long AccumulatedTime { get; set; }

    [JsonIgnore]
    public DateTime? StartedAtUtc { get; set; }

    public List<LapRecord> Laps { get; set; } = new();

    public long UpdatedAt { get; set; }

    public long GetElapsed()
    {
        if (State == StopwatchState.RUNNING && StartedAtUtc.HasValue)
        {
            var delta = (long)(DateTime.UtcNow - StartedAtUtc.Value).TotalMilliseconds;
            return AccumulatedTime + Math.Max(0, delta);
        }
        return AccumulatedTime;
    }

    public void Start()
    {
        if (State == StopwatchState.RUNNING) return;
        var now = DateTime.UtcNow;
        if (State == StopwatchState.RESET) AccumulatedTime = 0;
        State = StopwatchState.RUNNING;
        StartedAtUtc = now;
    }

    public void Pause()
    {
        if (State != StopwatchState.RUNNING) return;
        AccumulatedTime = GetElapsed();
        State = StopwatchState.PAUSED;
        StartedAtUtc = null;
    }

    public void Reset()
    {
        State = StopwatchState.RESET;
        AccumulatedTime = 0;
        StartedAtUtc = null;
        Laps.Clear();
    }

    public void AddLap()
    {
        var acc = GetElapsed();
        Laps.Add(new LapRecord { Number = Laps.Count + 1, AccumulatedTime = acc });
    }
}
