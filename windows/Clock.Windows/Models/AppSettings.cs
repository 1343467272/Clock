using System.Text.Json.Serialization;

namespace Clock.Windows.Models;

/// <summary>
/// User preferences. Only a curated subset is synced between devices (see SyncModels).
/// </summary>
public class AppSettings
{
    public bool Is24Hour { get; set; } = true;
    public string Theme { get; set; } = "system"; // system | light | dark
    public string WeekStart { get; set; } = "sunday"; // sunday | monday | saturday

    // Default timer behavior
    public string TimerRingtone { get; set; } = "default";
    public bool TimerVibrate { get; set; } = true;
    public string TimerVibrationPattern { get; set; } = "default";
    public bool TimerFlashOn { get; set; } = true;
    public int TimerAutoSilence { get; set; } = 600;
    public int TimerCrescendo { get; set; }
    public string TimerSort { get; set; } = "ascending";

    // Stopwatch
    public string StopwatchTimeFormat { get; set; } = "hh:mm:ss.hh";

    // Sync
    public bool SyncEnabled { get; set; }
    public string SyncDeviceName { get; set; } = Environment.MachineName;
    public int SyncPort { get; set; } = 7846;
    public string DeviceId { get; set; } = Guid.NewGuid().ToString("N");
    public string SyncPeersJson { get; set; } = "";

    [JsonIgnore]
    public DateTime? SnoozeDuration { get; set; }

    public int AlarmSnoozeMinutes { get; set; } = 10;
    public int AlarmAutoSilenceSeconds { get; set; } = 600;

    public AppSettings Clone() => (AppSettings)MemberwiseClone();
}
