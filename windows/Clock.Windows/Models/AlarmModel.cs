using System.Globalization;
using System.Text.Json.Serialization;
using Clock.Windows.Localization;

namespace Clock.Windows.Models;

/// <summary>Bit flags matching the Android Weekdays encoding: Mon=1 .. Sun=64.</summary>
public enum WeekdayBits
{
    Monday = 0x01,
    Tuesday = 0x02,
    Wednesday = 0x04,
    Thursday = 0x08,
    Friday = 0x10,
    Saturday = 0x20,
    Sunday = 0x40,
}

public class AlarmModel
{
    public string Uuid { get; set; } = Guid.NewGuid().ToString("N");

    public bool Enabled { get; set; }

    // One-time alarm date (0/0/0 means "not a specific date" like the AOSP default).
    public int Year { get; set; }
    public int Month { get; set; }
    public int Day { get; set; }

    public int Hour { get; set; }
    public int Minute { get; set; }

    /// <summary>Bitset: Monday=1, Tuesday=2, ..., Sunday=64. 0 = once-only.</summary>
    public int DaysOfWeek { get; set; }

    public string Label { get; set; } = "";

    public bool Vibrate { get; set; } = true;
    public string VibrationPattern { get; set; } = "default";
    public bool Flash { get; set; } = true;

    /// <summary>Opaque ringtone reference; mapped per-platform (content:// uri vs tone name).</summary>
    public string Ringtone { get; set; } = "default";

    public bool DeleteAfterUse { get; set; }

    public int AutoSilenceDuration { get; set; } = 600;
    public int SnoozeDuration { get; set; } = 10;
    public int MissedAlarmRepeatLimit { get; set; } = -1;
    public int CrescendoDuration { get; set; }
    public int AlarmVolume { get; set; } = 5;
    public int ManualSortOrder { get; set; }
    public long PauseStartDate { get; set; }
    public long PauseEndDate { get; set; }
    public string BackgroundImage { get; set; } = "";
    public int BlurIntensity { get; set; }

    /// <summary>Sync metadata (last-modified wall clock, epoch ms).</summary>
    public long UpdatedAt { get; set; }

    [JsonIgnore]
    public DateTime? SnoozedUntil { get; set; }

    public AlarmModel Clone() => (AlarmModel)MemberwiseClone();

    /// <summary>True when this alarm repeats on a weekly schedule.</summary>
    [JsonIgnore]
    public bool IsRepeating => DaysOfWeek != 0;

    public IEnumerable<DayOfWeek> GetRepeatDays()
    {
        if ((DaysOfWeek & (int)WeekdayBits.Monday) != 0) yield return DayOfWeek.Monday;
        if ((DaysOfWeek & (int)WeekdayBits.Tuesday) != 0) yield return DayOfWeek.Tuesday;
        if ((DaysOfWeek & (int)WeekdayBits.Wednesday) != 0) yield return DayOfWeek.Wednesday;
        if ((DaysOfWeek & (int)WeekdayBits.Thursday) != 0) yield return DayOfWeek.Thursday;
        if ((DaysOfWeek & (int)WeekdayBits.Friday) != 0) yield return DayOfWeek.Friday;
        if ((DaysOfWeek & (int)WeekdayBits.Saturday) != 0) yield return DayOfWeek.Saturday;
        if ((DaysOfWeek & (int)WeekdayBits.Sunday) != 0) yield return DayOfWeek.Sunday;
    }

    public void SetDay(DayOfWeek day, bool on)
    {
        int bit = day switch
        {
            DayOfWeek.Monday => (int)WeekdayBits.Monday,
            DayOfWeek.Tuesday => (int)WeekdayBits.Tuesday,
            DayOfWeek.Wednesday => (int)WeekdayBits.Wednesday,
            DayOfWeek.Thursday => (int)WeekdayBits.Thursday,
            DayOfWeek.Friday => (int)WeekdayBits.Friday,
            DayOfWeek.Saturday => (int)WeekdayBits.Saturday,
            _ => (int)WeekdayBits.Sunday,
        };
        DaysOfWeek = on ? DaysOfWeek | bit : DaysOfWeek & ~bit;
    }

    /// <summary>Computes the next firing instant (in the system local timezone).</summary>
    public DateTime GetNextFireTime(DateTime now)
    {
        var snoozed = SnoozedUntil;
        if (snoozed.HasValue && snoozed > now) return snoozed.Value;

        if (IsRepeating)
        {
            var t = new DateTime(now.Year, now.Month, now.Day, Hour, Minute, 0);
            if (t <= now) t = t.AddDays(1);
            for (int i = 0; i < 8; i++)
            {
                if (GetRepeatDays().Contains(t.DayOfWeek)) return t;
                t = t.AddDays(1);
            }
            return t;
        }

        // One-time alarm: use the specific date if given, else today (roll to tomorrow if passed).
        // The month is 0-based on the wire and as stored (matching Android's Calendar.MONTH).
        DateTime d;
        if (Year > 0)
        {
            d = new DateTime(Year, Math.Clamp(Month + 1, 1, 12), Math.Max(1, Day), Hour, Minute, 0);
            if (d <= now) d = d.AddDays(1);
        }
        else
        {
            d = new DateTime(now.Year, now.Month, now.Day, Hour, Minute, 0);
            if (d <= now) d = d.AddDays(1);
        }
        return d;
    }

    /// <summary>True if this alarm is scheduled to fire on the given day (for repeat display).</summary>
    public bool ScheduledOn(DateTime day) => !IsRepeating || GetRepeatDays().Contains(day.DayOfWeek);

    [JsonIgnore]
    public string TimeText => $"{Hour:00}:{Minute:00}";

    [JsonIgnore]
    public string DaysText
    {
        get
        {
            if (!IsRepeating) return "";
            if (DaysOfWeek == 0x7F) return Text.EveryDay;
            var order = new[] { DayOfWeek.Monday, DayOfWeek.Tuesday, DayOfWeek.Wednesday, DayOfWeek.Thursday, DayOfWeek.Friday, DayOfWeek.Saturday, DayOfWeek.Sunday };
            return string.Join(" ", order.Where(GetRepeatDays().Contains).Select(Text.ShortDayName));
        }
    }

    [JsonIgnore]
    public string LabelText => string.IsNullOrWhiteSpace(Label) ? Text.DefaultAlarmLabel : Label;

    [JsonIgnore]
    public string EnabledText => Enabled ? Text.EnabledOn : Text.EnabledOff;

    [JsonIgnore]
    public string NextFireText
    {
        get
        {
            if (!Enabled) return Text.EnabledOff;
            var next = GetNextFireTime(DateTime.Now);
            var formatted = next.ToString("M月d日 dddd HH:mm", CultureInfo.GetCultureInfo("zh-CN"));
            return Text.NextPrefix + formatted;
        }
    }
}
