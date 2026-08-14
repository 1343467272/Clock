using Clock.Windows.Models;

namespace Clock.Windows.Localization;

/// <summary>
/// Simplified Chinese UI strings. Wording follows the Android app's values-zh-rCN so both
/// platforms stay consistent.
/// </summary>
public static class Text
{
    // Window titles
    public const string AppTitle = "时钟";
    public const string AlarmWindowTitle = "闹钟";

    // Main tabs
    public const string TabAlarms = "闹钟";
    public const string TabTimer = "定时器";
    public const string TabStopwatch = "秒表";
    public const string TabWorldClock = "世界时钟";
    public const string TabSettings = "设置";
    public const string TabSync = "同步";

    // Alarm card
    public const string Edit = "编辑";
    public const string Delete = "删除";
    public const string EnabledOn = "开";
    public const string EnabledOff = "关";
    public const string EveryDay = "每天";
    public const string DefaultAlarmLabel = "闹钟";
    public const string NextPrefix = "下次：";

    // Timer card
    public const string StartPause = "开始/暂停";
    public const string AddOneMinute = "加 1 分钟";
    public const string Reset = "重置";
    public const string Start = "开始";
    public const string Pause = "暂停";
    public const string DefaultTimerLabel = "定时器";

    // City card
    public const string Remove = "移除";

    // Alarms tab
    public const string AlarmsTitle = "闹钟";
    public const string AddAlarm = "添加闹钟";

    // Timer tab
    public const string TimerTitle = "定时器";
    public const string Minutes = "分钟";
    public const string Seconds = "秒";
    public const string LabelOptional = "标签（可选）";
    public const string StartTimer = "开始定时器";

    // Stopwatch tab
    public const string StopwatchTitle = "秒表";
    public const string Lap = "一圈";
    public const string LapFormat = "第 {0} 圈";

    // World clock tab
    public const string WorldClockTitle = "世界时钟";
    public const string AddCity = "添加城市";
    public const string SearchCity = "搜索城市";

    // Settings tab
    public const string SettingsTitle = "设置";
    public const string Use24Hour = "使用 24 小时格式";
    public const string WeekStartsOn = "一周的第一天";
    public const string WeekStartSunday = "星期日";
    public const string WeekStartMonday = "星期一";
    public const string WeekStartSaturday = "星期六";
    public const string Theme = "主题";
    public const string ThemeSystem = "系统";
    public const string ThemeLight = "浅色";
    public const string ThemeDark = "深色";
    public const string AlarmSnoozeMin = "闹钟暂停（分钟）";
    public const string AlarmAutoSilenceSec = "闹钟自动静音（秒）";
    public const string TimerDefaults = "定时器默认设置";
    public const string VibrateWhenTimerExpires = "定时器到期时振动";
    public const string FlashOnExpiry = "到期时闪光";
    public const string AutoSilenceAfter10Min = "10 分钟后自动静音";
    public const string Data = "数据";
    public const string SaveNow = "立即保存";

    // Sync tab
    public const string LanSyncTitle = "局域网同步";
    public const string EnableLanSync = "启用局域网同步";
    public const string DeviceName = "设备名称";
    public const string Port = "端口";
    public const string SyncNow = "立即同步";
    public const string DiscoveredDevices = "检测到的设备";
    public const string NoDevicesDiscovered = "未检测到设备。请确保手机和此电脑位于同一网络。";
    public const string SyncingWith = "正在与 {0} 同步…";
    public const string LastSeen = " · 上次出现于 {0}";
    public const string SyncedWith = "已与 {0} 在 {1} 同步";

    // Alarm edit window
    public const string Time = "时间";
    public const string Repeat = "重复";
    public const string Options = "选项";
    public const string Vibrate = "振动";
    public const string Flash = "闪光";
    public const string DeleteAfterUse = "使用后删除（仅一次）";
    public const string Label = "标签";
    public const string SnoozeMinutes = "暂停（分钟）";
    public const string AutoSilenceSeconds = "自动静音（秒）";
    public const string Cancel = "取消";
    public const string Save = "保存";

    // Alert window
    public const string AlertAlarmTitle = "闹钟";
    public const string AlertTimerTitle = "定时器";
    public const string TimesUp = "时间到";
    public const string Snooze = "暂停";
    public const string Dismiss = "关闭";

    public static string ShortDayName(DayOfWeek day) => day switch
    {
        DayOfWeek.Monday => "周一",
        DayOfWeek.Tuesday => "周二",
        DayOfWeek.Wednesday => "周三",
        DayOfWeek.Thursday => "周四",
        DayOfWeek.Friday => "周五",
        DayOfWeek.Saturday => "周六",
        _ => "周日",
    };

    public static string FullDayName(DayOfWeek day) => day switch
    {
        DayOfWeek.Monday => "星期一",
        DayOfWeek.Tuesday => "星期二",
        DayOfWeek.Wednesday => "星期三",
        DayOfWeek.Thursday => "星期四",
        DayOfWeek.Friday => "星期五",
        DayOfWeek.Saturday => "星期六",
        _ => "星期日",
    };

    public static string TimerStateName(TimerState state) => state switch
    {
        TimerState.RUNNING => "运行中",
        TimerState.PAUSED => "已暂停",
        TimerState.EXPIRED => "已结束",
        TimerState.RESET => "已重置",
        _ => "已错过",
    };
}
