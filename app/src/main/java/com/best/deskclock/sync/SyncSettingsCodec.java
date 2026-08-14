/*
 * SPDX-License-Identifier: GPL-3.0-only
 */

package com.best.deskclock.sync;

import static com.best.deskclock.settings.PreferencesDefaultValues.DARK_THEME;
import static com.best.deskclock.settings.PreferencesDefaultValues.DEFAULT_SORT_TIMER_MANUALLY;
import static com.best.deskclock.settings.PreferencesDefaultValues.DEFAULT_TIMER_AUTO_SILENCE_DURATION;
import static com.best.deskclock.settings.PreferencesDefaultValues.DEFAULT_TIMER_VIBRATE;
import static com.best.deskclock.settings.PreferencesDefaultValues.DEFAULT_TURN_ON_BACK_FLASH_FOR_EXPIRED_TIMER;
import static com.best.deskclock.settings.PreferencesDefaultValues.DEFAULT_VIBRATION_PATTERN;
import static com.best.deskclock.settings.PreferencesDefaultValues.DEFAULT_VOLUME_CRESCENDO_DURATION;
import static com.best.deskclock.settings.PreferencesDefaultValues.DEFAULT_WEEK_START;
import static com.best.deskclock.settings.PreferencesDefaultValues.LIGHT_THEME;
import static com.best.deskclock.settings.PreferencesDefaultValues.SORT_TIMER_BY_ASCENDING_DURATION;
import static com.best.deskclock.settings.PreferencesDefaultValues.SORT_TIMER_BY_DESCENDING_DURATION;
import static com.best.deskclock.settings.PreferencesDefaultValues.SORT_TIMER_BY_NAME;
import static com.best.deskclock.settings.PreferencesDefaultValues.SYSTEM_THEME;
import static com.best.deskclock.settings.PreferencesKeys.KEY_SORT_TIMER;
import static com.best.deskclock.settings.PreferencesKeys.KEY_THEME;
import static com.best.deskclock.settings.PreferencesKeys.KEY_TIMER_AUTO_SILENCE_DURATION;
import static com.best.deskclock.settings.PreferencesKeys.KEY_TIMER_RINGTONE;
import static com.best.deskclock.settings.PreferencesKeys.KEY_TIMER_VIBRATE;
import static com.best.deskclock.settings.PreferencesKeys.KEY_TIMER_VIBRATION_PATTERN;
import static com.best.deskclock.settings.PreferencesKeys.KEY_TIMER_VOLUME_CRESCENDO_DURATION;
import static com.best.deskclock.settings.PreferencesKeys.KEY_TURN_ON_BACK_FLASH_FOR_EXPIRED_TIMER;
import static com.best.deskclock.settings.PreferencesKeys.KEY_WEEK_START;

import android.content.Context;
import android.content.SharedPreferences;
import android.provider.Settings;
import android.text.format.DateFormat;

import com.best.deskclock.DeskClockApplication;

import org.json.JSONObject;

import java.util.Calendar;

/**
 * Translates synced settings between their wire representation (as understood by the Windows
 * desktop app) and Android preferences. Both directions use the wire names defined by the
 * Windows {@code AppSettings} members.
 */
public final class SyncSettingsCodec {

    /** The setting keys that are synchronized, in the order they are broadcast. */
    static final String[] SYNCED_KEYS = {
        "is24Hour", "weekStart", "theme",
        "timerRingtone", "timerVibrate", "timerVibrationPattern", "timerFlashOn",
        "timerAutoSilence", "timerCrescendo", "timerSort"
    };

    private SyncSettingsCodec() {
    }

    /**
     * @return the current value of the given setting in its wire representation, or {@code null}
     * when the setting has no Android equivalent (e.g. {@code stopwatchTimeFormat}).
     */
    static Object readValue(Context context, String key) {
        final SharedPreferences prefs = DeskClockApplication.getDefaultSharedPreferences(context);
        switch (key) {
            case "is24Hour":
                return DateFormat.is24HourFormat(context);
            case "weekStart":
                return weekStartToWire(readIntPref(prefs, KEY_WEEK_START, Integer.parseInt(DEFAULT_WEEK_START)));
            case "theme":
                return themeToWire(prefs.getString(KEY_THEME, SYSTEM_THEME));
            case "timerRingtone": {
                final String ringtone = prefs.getString(KEY_TIMER_RINGTONE, null);
                return ringtone == null ? "default" : ringtone;
            }
            case "timerVibrate":
                return prefs.getBoolean(KEY_TIMER_VIBRATE, DEFAULT_TIMER_VIBRATE);
            case "timerVibrationPattern":
                return prefs.getString(KEY_TIMER_VIBRATION_PATTERN, DEFAULT_VIBRATION_PATTERN);
            case "timerFlashOn":
                return prefs.getBoolean(KEY_TURN_ON_BACK_FLASH_FOR_EXPIRED_TIMER, DEFAULT_TURN_ON_BACK_FLASH_FOR_EXPIRED_TIMER);
            case "timerAutoSilence":
                return prefs.getInt(KEY_TIMER_AUTO_SILENCE_DURATION, DEFAULT_TIMER_AUTO_SILENCE_DURATION);
            case "timerCrescendo":
                return prefs.getInt(KEY_TIMER_VOLUME_CRESCENDO_DURATION, DEFAULT_VOLUME_CRESCENDO_DURATION);
            case "timerSort":
                return timerSortToWire(prefs.getString(KEY_SORT_TIMER, DEFAULT_SORT_TIMER_MANUALLY));
            default:
                return null;
        }
    }

    /**
     * Applies a remote setting value. Returns {@code true} when the value was applied locally,
     * {@code false} when it was unknown or could not be applied.
     */
    static boolean applyValue(Context context, String key, Object value) {
        if (value == null) {
            return false;
        }
        final SharedPreferences prefs = DeskClockApplication.getDefaultSharedPreferences(context);
        switch (key) {
            case "is24Hour": {
                final boolean is24 = value instanceof Boolean ? (Boolean) value : Boolean.parseBoolean(String.valueOf(value));
                if (!Settings.System.canWrite(context)) {
                    return false;
                }
                return Settings.System.putString(context.getContentResolver(), Settings.System.TIME_12_24,
                    is24 ? "24" : "12");
            }
            case "weekStart": {
                final Integer calendarDay = weekStartFromWire(String.valueOf(value));
                if (calendarDay == null) {
                    return false;
                }
                prefs.edit().putString(KEY_WEEK_START, String.valueOf(calendarDay)).apply();
                return true;
            }
            case "theme": {
                final String androidValue = themeFromWire(String.valueOf(value));
                if (androidValue == null) {
                    return false;
                }
                prefs.edit().putString(KEY_THEME, androidValue).apply();
                return true;
            }
            case "timerRingtone": {
                final String ringtone = String.valueOf(value);
                if ("default".equalsIgnoreCase(ringtone)) {
                    prefs.edit().remove(KEY_TIMER_RINGTONE).apply();
                } else {
                    prefs.edit().putString(KEY_TIMER_RINGTONE, ringtone).apply();
                }
                return true;
            }
            case "timerVibrate":
                prefs.edit().putBoolean(KEY_TIMER_VIBRATE, toBool(value)).apply();
                return true;
            case "timerVibrationPattern":
                prefs.edit().putString(KEY_TIMER_VIBRATION_PATTERN, String.valueOf(value)).apply();
                return true;
            case "timerFlashOn":
                prefs.edit().putBoolean(KEY_TURN_ON_BACK_FLASH_FOR_EXPIRED_TIMER, toBool(value)).apply();
                return true;
            case "timerAutoSilence":
                prefs.edit().putInt(KEY_TIMER_AUTO_SILENCE_DURATION, toInt(value)).apply();
                return true;
            case "timerCrescendo":
                prefs.edit().putInt(KEY_TIMER_VOLUME_CRESCENDO_DURATION, toInt(value)).apply();
                return true;
            case "timerSort": {
                final String androidValue = timerSortFromWire(String.valueOf(value));
                if (androidValue == null) {
                    return false;
                }
                prefs.edit().putString(KEY_SORT_TIMER, androidValue).apply();
                return true;
            }
            default:
                return false;
        }
    }

    /**
     * @return the canonical JSON text of a value so local and remote values can be compared
     * and stored byte-for-byte.
     */
    static String valueToJson(Object value) {
        if (value == null) {
            return "null";
        }
        if (value instanceof Boolean || value instanceof Number) {
            return value.toString();
        }
        return JSONObject.quote(String.valueOf(value));
    }

    // ---------------------------------------------------------------- helpers

    private static boolean toBool(Object value) {
        if (value instanceof Boolean) {
            return (Boolean) value;
        }
        return "true".equalsIgnoreCase(String.valueOf(value));
    }

    private static int toInt(Object value) {
        if (value instanceof Number) {
            return ((Number) value).intValue();
        }
        try {
            return (int) Double.parseDouble(String.valueOf(value));
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private static int readIntPref(SharedPreferences prefs, String key, int defaultValue) {
        final String value = prefs.getString(key, null);
        if (value == null) {
            return defaultValue;
        }
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }

    private static String themeToWire(String androidValue) {
        if (LIGHT_THEME.equals(androidValue)) {
            return "light";
        }
        if (DARK_THEME.equals(androidValue)) {
            return "dark";
        }
        return "system";
    }

    private static String themeFromWire(String wireValue) {
        switch (wireValue) {
            case "light":
                return LIGHT_THEME;
            case "dark":
                return DARK_THEME;
            default:
                return SYSTEM_THEME;
        }
    }

    private static String weekStartToWire(int firstCalendarDay) {
        switch (firstCalendarDay) {
            case Calendar.MONDAY:
                return "monday";
            case Calendar.SATURDAY:
                return "saturday";
            default:
                return "sunday";
        }
    }

    private static Integer weekStartFromWire(String wireValue) {
        switch (wireValue) {
            case "monday":
                return Calendar.MONDAY;
            case "saturday":
                return Calendar.SATURDAY;
            case "sunday":
                return Calendar.SUNDAY;
            default:
                return null;
        }
    }

    private static String timerSortToWire(String androidValue) {
        if (SORT_TIMER_BY_ASCENDING_DURATION.equals(androidValue)) {
            return "ascending";
        }
        if (SORT_TIMER_BY_DESCENDING_DURATION.equals(androidValue)) {
            return "descending";
        }
        if (SORT_TIMER_BY_NAME.equals(androidValue)) {
            return "name";
        }
        return "manual";
    }

    private static String timerSortFromWire(String wireValue) {
        switch (wireValue) {
            case "ascending":
                return SORT_TIMER_BY_ASCENDING_DURATION;
            case "descending":
                return SORT_TIMER_BY_DESCENDING_DURATION;
            case "name":
                return SORT_TIMER_BY_NAME;
            default:
                return DEFAULT_SORT_TIMER_MANUALLY;
        }
    }
}
