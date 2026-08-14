using System.Text.Json.Serialization;

namespace Clock.Windows.Models;

public enum TimerState
{
    RESET = 4,
    RUNNING = 1,
    PAUSED = 2,
    EXPIRED = 3,
    MISSED = 5,
}

public class TimerModel
{
    public string Uuid { get; set; } = Guid.NewGuid().ToString("N");

    public TimerState State { get; set; } = TimerState.RESET;

    /// <summary>Original length in ms at creation.</summary>
    public long Length { get; set; }

    /// <summary>Length including time added by the user.</summary>
    public long TotalLength { get; set; }

    public long RemainingTime { get; set; }

    /// <summary>Seconds shown on the "add time" button.</summary>
    public string ButtonTime { get; set; } = "1";

    public string Label { get; set; } = "";
    public string Ringtone { get; set; } = "default";
    public int AutoSilence { get; set; } = 600;
    public int CrescendoDuration { get; set; }
    public bool Vibrate { get; set; } = true;
    public string VibrationPattern { get; set; } = "default";
    public bool FlashOn { get; set; } = true;
    public bool TurnOffMedia { get; set; }
    public bool DeleteAfterUse { get; set; }

    [JsonIgnore]
    public DateTime? DueAt { get; set; }

    [JsonIgnore]
    public long LocalLastStartElapsed { get; set; }

    /// <summary>Sync metadata (last-modified wall clock, epoch ms).</summary>
    public long UpdatedAt { get; set; }

    public TimerModel Clone() => (TimerModel)MemberwiseClone();

    public string GetRemainingDisplay()
    {
        var rem = GetRemaining();
        if (rem <= 0) return "00:00";
        return Format(rem);
    }

    public string GetTotalDisplay() => Format(TotalLength);

    public static string Format(long ms)
    {
        var ts = TimeSpan.FromMilliseconds(Math.Max(0, ms));
        return ts.TotalHours >= 1
            ? $"{(int)ts.TotalHours:00}:{ts.Minutes:00}:{ts.Seconds:00}"
            : $"{ts.Minutes:00}:{ts.Seconds:00}";
    }

    /// <summary>Remaining ms for a running timer, relative to local real time.</summary>
    public long GetRemaining()
    {
        if (State == TimerState.PAUSED || State == TimerState.RESET) return RemainingTime;
        if (DueAt.HasValue) return (long)(DueAt.Value - DateTime.UtcNow).TotalMilliseconds;
        return RemainingTime;
    }

    /// <summary>Restarts the clock so GetRemaining reflects elapsed time.</summary>
    public void ResumeFromRemaining(long remaining, DateTime wallClockNowUtc)
    {
        State = TimerState.RUNNING;
        RemainingTime = remaining;
        DueAt = wallClockNowUtc.AddMilliseconds(remaining);
    }

    public void Start(DateTime wallClockNowUtc)
    {
        if (State == TimerState.RUNNING) return;
        State = TimerState.RUNNING;
        DueAt = wallClockNowUtc.AddMilliseconds(GetRemaining());
    }

    public void Pause()
    {
        if (State != TimerState.RUNNING) return;
        RemainingTime = GetRemaining();
        State = TimerState.PAUSED;
        DueAt = null;
    }

    public void Reset()
    {
        State = TimerState.RESET;
        RemainingTime = Length;
        TotalLength = Length;
        DueAt = null;
    }

    public void AddTime(int minutes)
    {
        if (State == TimerState.EXPIRED || State == TimerState.MISSED)
        {
            RemainingTime = minutes * 60_000L;
            TotalLength = RemainingTime;
            Start(DateTime.UtcNow);
        }
        else
        {
            RemainingTime = GetRemaining() + minutes * 60_000L;
            TotalLength += minutes * 60_000L;
            if (State == TimerState.RUNNING && DueAt.HasValue) DueAt = DueAt.Value.AddMinutes(minutes);
        }
    }

    [JsonIgnore]
    public string LabelText => string.IsNullOrWhiteSpace(Label) ? "Timer" : Label;

    [JsonIgnore]
    public string MetaText => $"{State} · {GetTotalDisplay()}";
}
