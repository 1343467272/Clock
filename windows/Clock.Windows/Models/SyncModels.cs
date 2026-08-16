using System.Text.Json;
using System.Text.Json.Serialization;

namespace Clock.Windows.Models;

/// <summary>A peer (device) discovered on the LAN.</summary>
public class SyncPeerInfo
{
    public string DeviceId { get; set; } = "";
    public string DeviceName { get; set; } = "";
    public string Address { get; set; } = "";
    public int Port { get; set; }
    public DateTime LastSeen { get; set; } = DateTime.UtcNow;

    /// <summary>
    /// Local trust marker (one-way, like the Android app): only paired peers are auto-connected
    /// while the settings panel is hidden.
    /// </summary>
    public bool Paired { get; set; }
}

/// <summary>Wire record for one alarm.</summary>
public class AlarmRecord
{
    public string Uuid { get; set; } = "";
    public long UpdatedAt { get; set; }
    public bool Enabled { get; set; }
    public int Year { get; set; }
    public int Month { get; set; }
    public int Day { get; set; }
    public int Hour { get; set; }
    public int Minute { get; set; }
    public int DaysOfWeek { get; set; }
    public string Label { get; set; } = "";
    public bool Vibrate { get; set; }
    public string VibrationPattern { get; set; } = "default";
    public bool Flash { get; set; }
    public string Ringtone { get; set; } = "default";
    public bool DeleteAfterUse { get; set; }
    public int AutoSilenceDuration { get; set; }
    public int SnoozeDuration { get; set; }
    public int MissedAlarmRepeatLimit { get; set; }
    public int CrescendoDuration { get; set; }
    public int AlarmVolume { get; set; }
    public int ManualSortOrder { get; set; }
    public long PauseStartDate { get; set; }
    public long PauseEndDate { get; set; }
    public string BackgroundImage { get; set; } = "";
    public int BlurIntensity { get; set; }
    public int RepeatType { get; set; }
    public int ShiftWorkDays { get; set; }
    public int ShiftRestDays { get; set; }
    public long ShiftStartDate { get; set; }
}

/// <summary>Wire record for one countdown timer.</summary>
public class TimerRecord
{
    public string Uuid { get; set; } = "";
    public long UpdatedAt { get; set; }
    public string State { get; set; } = "RESET";
    public long Length { get; set; }
    public long TotalLength { get; set; }
    public long RemainingTime { get; set; }
    public string Label { get; set; } = "";
    public string ButtonTime { get; set; } = "1";
    public string Ringtone { get; set; } = "default";
    public int AutoSilence { get; set; }
    public int CrescendoDuration { get; set; }
    public bool Vibrate { get; set; }
    public string VibrationPattern { get; set; } = "default";
    public bool FlashOn { get; set; }
    public bool TurnOffMedia { get; set; }
    public bool DeleteAfterUse { get; set; }
}

public class Tombstone
{
    public string Uuid { get; set; } = "";
    public long UpdatedAt { get; set; }
}

public class StopwatchRecord
{
    public long UpdatedAt { get; set; }
    public string State { get; set; } = "RESET";
    public long AccumulatedTime { get; set; }
    public List<LapRecord> Laps { get; set; } = new();
}

public class CitiesRecord
{
    public long UpdatedAt { get; set; }
    public List<string> Ids { get; set; } = new();
    public Dictionary<string, string> Notes { get; set; } = new();
}

public class SettingValue
{
    public long Ts { get; set; }
    public JsonElement? Value { get; set; }
}

public class SettingsRecord
{
    public long UpdatedAt { get; set; }
    public Dictionary<string, SettingValue> Values { get; set; } = new();
}

/// <summary>Full state snapshot exchanged between peers.</summary>
public class SyncSnapshot
{
    public string Type { get; set; } = "state";
    public int Version { get; set; } = 1;
    public string DeviceId { get; set; } = "";
    public string DeviceName { get; set; } = "";
    public long SentAt { get; set; }
    public List<AlarmRecord> Alarms { get; set; } = new();
    public List<Tombstone> AlarmTombstones { get; set; } = new();
    public List<TimerRecord> Timers { get; set; } = new();
    public List<Tombstone> TimerTombstones { get; set; } = new();
    public StopwatchRecord? Stopwatch { get; set; }
    public CitiesRecord? Cities { get; set; }
    public SettingsRecord? Settings { get; set; }
}

/// <summary>Handles the shared JSON wire format.</summary>
public static class SyncWire
{
    public static readonly JsonSerializerOptions Options = new()
    {
        PropertyNamingPolicy = JsonNamingPolicy.CamelCase,
        PropertyNameCaseInsensitive = true,
    };

    public static string Serialize<T>(T value) => JsonSerializer.Serialize(value, Options);

    public static T? Deserialize<T>(string json) => JsonSerializer.Deserialize<T>(json, Options);
}
