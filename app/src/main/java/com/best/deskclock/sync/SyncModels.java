/*
 * SPDX-License-Identifier: GPL-3.0-only
 */

package com.best.deskclock.sync;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Wire-format models exchanged between peers over the LAN sync protocol.
 * Field names are camelCase and match the Windows desktop implementation.
 */
public final class SyncModels {

    private SyncModels() {
    }

    public static final class AlarmRecord {
        public String uuid;
        public long updatedAt;
        public boolean enabled;
        public int year;
        public int month;
        public int day;
        public int hour;
        public int minute;
        public int daysOfWeek;
        public String label = "";
        public boolean vibrate;
        public String vibrationPattern = "default";
        public boolean flash;
        public String ringtone = "default";
        public boolean deleteAfterUse;
        public int autoSilenceDuration;
        public int snoozeDuration;
        public int missedAlarmRepeatLimit;
        public int crescendoDuration;
        public int alarmVolume;
        public int manualSortOrder;
        public long pauseStartDate;
        public long pauseEndDate;
        public String backgroundImage = "";
        public int blurIntensity;

        public JSONObject toJson() throws JSONException {
            JSONObject o = new JSONObject();
            o.put("uuid", uuid);
            o.put("updatedAt", updatedAt);
            o.put("enabled", enabled);
            o.put("year", year);
            o.put("month", month);
            o.put("day", day);
            o.put("hour", hour);
            o.put("minute", minute);
            o.put("daysOfWeek", daysOfWeek);
            o.put("label", label);
            o.put("vibrate", vibrate);
            o.put("vibrationPattern", vibrationPattern);
            o.put("flash", flash);
            o.put("ringtone", ringtone);
            o.put("deleteAfterUse", deleteAfterUse);
            o.put("autoSilenceDuration", autoSilenceDuration);
            o.put("snoozeDuration", snoozeDuration);
            o.put("missedAlarmRepeatLimit", missedAlarmRepeatLimit);
            o.put("crescendoDuration", crescendoDuration);
            o.put("alarmVolume", alarmVolume);
            o.put("manualSortOrder", manualSortOrder);
            o.put("pauseStartDate", pauseStartDate);
            o.put("pauseEndDate", pauseEndDate);
            o.put("backgroundImage", backgroundImage);
            o.put("blurIntensity", blurIntensity);
            return o;
        }

        public static AlarmRecord fromJson(JSONObject o) {
            AlarmRecord r = new AlarmRecord();
            r.uuid = o.optString("uuid", "");
            r.updatedAt = o.optLong("updatedAt", 0);
            r.enabled = o.optBoolean("enabled", false);
            r.year = o.optInt("year", 0);
            r.month = o.optInt("month", 0);
            r.day = o.optInt("day", 0);
            r.hour = o.optInt("hour", 0);
            r.minute = o.optInt("minute", 0);
            r.daysOfWeek = o.optInt("daysOfWeek", 0);
            r.label = o.optString("label", "");
            r.vibrate = o.optBoolean("vibrate", true);
            r.vibrationPattern = o.optString("vibrationPattern", "default");
            r.flash = o.optBoolean("flash", true);
            r.ringtone = o.optString("ringtone", "default");
            r.deleteAfterUse = o.optBoolean("deleteAfterUse", false);
            r.autoSilenceDuration = o.optInt("autoSilenceDuration", 600);
            r.snoozeDuration = o.optInt("snoozeDuration", 10);
            r.missedAlarmRepeatLimit = o.optInt("missedAlarmRepeatLimit", -1);
            r.crescendoDuration = o.optInt("crescendoDuration", 0);
            r.alarmVolume = o.optInt("alarmVolume", 5);
            r.manualSortOrder = o.optInt("manualSortOrder", 0);
            r.pauseStartDate = o.optLong("pauseStartDate", 0);
            r.pauseEndDate = o.optLong("pauseEndDate", 0);
            r.backgroundImage = o.optString("backgroundImage", "");
            r.blurIntensity = o.optInt("blurIntensity", 0);
            return r;
        }
    }

    public static final class TimerRecord {
        public String uuid;
        public long updatedAt;
        public String state = "RESET";
        public long length;
        public long totalLength;
        public long remainingTime;
        public String label = "";
        public String buttonTime = "1";
        public String ringtone = "default";
        public int autoSilence;
        public int crescendoDuration;
        public boolean vibrate;
        public String vibrationPattern = "default";
        public boolean flashOn;
        public boolean turnOffMedia;
        public boolean deleteAfterUse;

        public JSONObject toJson() throws JSONException {
            JSONObject o = new JSONObject();
            o.put("uuid", uuid);
            o.put("updatedAt", updatedAt);
            o.put("state", state);
            o.put("length", length);
            o.put("totalLength", totalLength);
            o.put("remainingTime", remainingTime);
            o.put("label", label);
            o.put("buttonTime", buttonTime);
            o.put("ringtone", ringtone);
            o.put("autoSilence", autoSilence);
            o.put("crescendoDuration", crescendoDuration);
            o.put("vibrate", vibrate);
            o.put("vibrationPattern", vibrationPattern);
            o.put("flashOn", flashOn);
            o.put("turnOffMedia", turnOffMedia);
            o.put("deleteAfterUse", deleteAfterUse);
            return o;
        }

        public static TimerRecord fromJson(JSONObject o) {
            TimerRecord r = new TimerRecord();
            r.uuid = o.optString("uuid", "");
            r.updatedAt = o.optLong("updatedAt", 0);
            r.state = o.optString("state", "RESET");
            r.length = o.optLong("length", 0);
            r.totalLength = o.optLong("totalLength", 0);
            r.remainingTime = o.optLong("remainingTime", 0);
            r.label = o.optString("label", "");
            r.buttonTime = o.optString("buttonTime", "1");
            r.ringtone = o.optString("ringtone", "default");
            r.autoSilence = o.optInt("autoSilence", 600);
            r.crescendoDuration = o.optInt("crescendoDuration", 0);
            r.vibrate = o.optBoolean("vibrate", true);
            r.vibrationPattern = o.optString("vibrationPattern", "default");
            r.flashOn = o.optBoolean("flashOn", true);
            r.turnOffMedia = o.optBoolean("turnOffMedia", false);
            r.deleteAfterUse = o.optBoolean("deleteAfterUse", false);
            return r;
        }
    }

    public static final class Tombstone {
        public String uuid;
        public long updatedAt;

        public JSONObject toJson() throws JSONException {
            JSONObject o = new JSONObject();
            o.put("uuid", uuid);
            o.put("updatedAt", updatedAt);
            return o;
        }

        public static Tombstone fromJson(JSONObject o) {
            Tombstone t = new Tombstone();
            t.uuid = o.optString("uuid", "");
            t.updatedAt = o.optLong("updatedAt", 0);
            return t;
        }
    }

    public static final class LapRecord {
        public int number;
        public long accumulatedTime;

        public JSONObject toJson() throws JSONException {
            JSONObject o = new JSONObject();
            o.put("number", number);
            o.put("accumulatedTime", accumulatedTime);
            return o;
        }

        public static LapRecord fromJson(JSONObject o) {
            LapRecord l = new LapRecord();
            l.number = o.optInt("number", 0);
            l.accumulatedTime = o.optLong("accumulatedTime", 0);
            return l;
        }
    }

    public static final class StopwatchRecord {
        public long updatedAt;
        public String state = "RESET";
        public long accumulatedTime;
        public List<LapRecord> laps = new ArrayList<>();

        public JSONObject toJson() throws JSONException {
            JSONObject o = new JSONObject();
            o.put("updatedAt", updatedAt);
            o.put("state", state);
            o.put("accumulatedTime", accumulatedTime);
            JSONArray arr = new JSONArray();
            for (LapRecord lap : laps) {
                arr.put(lap.toJson());
            }
            o.put("laps", arr);
            return o;
        }

        public static StopwatchRecord fromJson(JSONObject o) {
            StopwatchRecord r = new StopwatchRecord();
            r.updatedAt = o.optLong("updatedAt", 0);
            r.state = o.optString("state", "RESET");
            r.accumulatedTime = o.optLong("accumulatedTime", 0);
            JSONArray arr = o.optJSONArray("laps");
            if (arr != null) {
                for (int i = 0; i < arr.length(); i++) {
                    r.laps.add(LapRecord.fromJson(arr.optJSONObject(i)));
                }
            }
            return r;
        }
    }

    public static final class CitiesRecord {
        public long updatedAt;
        public List<String> ids = new ArrayList<>();
        public Map<String, String> notes = new HashMap<>();

        public JSONObject toJson() throws JSONException {
            JSONObject o = new JSONObject();
            o.put("updatedAt", updatedAt);
            JSONArray arr = new JSONArray();
            for (String id : ids) {
                arr.put(id);
            }
            o.put("ids", arr);
            JSONObject notesJson = new JSONObject();
            for (Map.Entry<String, String> e : notes.entrySet()) {
                notesJson.put(e.getKey(), e.getValue());
            }
            o.put("notes", notesJson);
            return o;
        }

        public static CitiesRecord fromJson(JSONObject o) {
            CitiesRecord r = new CitiesRecord();
            r.updatedAt = o.optLong("updatedAt", 0);
            JSONArray arr = o.optJSONArray("ids");
            if (arr != null) {
                for (int i = 0; i < arr.length(); i++) {
                    String id = arr.optString(i);
                    if (!id.isEmpty()) r.ids.add(id);
                }
            }
            JSONObject notesJson = o.optJSONObject("notes");
            if (notesJson != null) {
                for (java.util.Iterator<String> it = notesJson.keys(); it.hasNext(); ) {
                    String key = it.next();
                    r.notes.put(key, notesJson.optString(key, ""));
                }
            }
            return r;
        }
    }

    public static final class SettingValue {
        public long ts;
        public Object value;

        public JSONObject toJson() throws JSONException {
            JSONObject o = new JSONObject();
            o.put("ts", ts);
            o.put("value", value);
            return o;
        }

        public static SettingValue fromJson(JSONObject o) {
            SettingValue s = new SettingValue();
            s.ts = o.optLong("ts", 0);
            s.value = o.opt("value");
            return s;
        }
    }

    public static final class SettingsRecord {
        public long updatedAt;
        public Map<String, SettingValue> values = new HashMap<>();

        public JSONObject toJson() throws JSONException {
            JSONObject o = new JSONObject();
            o.put("updatedAt", updatedAt);
            JSONObject valuesJson = new JSONObject();
            for (Map.Entry<String, SettingValue> e : values.entrySet()) {
                valuesJson.put(e.getKey(), e.getValue().toJson());
            }
            o.put("values", valuesJson);
            return o;
        }

        public static SettingsRecord fromJson(JSONObject o) {
            SettingsRecord r = new SettingsRecord();
            r.updatedAt = o.optLong("updatedAt", 0);
            JSONObject valuesJson = o.optJSONObject("values");
            if (valuesJson != null) {
                for (java.util.Iterator<String> it = valuesJson.keys(); it.hasNext(); ) {
                    String key = it.next();
                    r.values.put(key, SettingValue.fromJson(valuesJson.optJSONObject(key)));
                }
            }
            return r;
        }
    }

    public static final class SyncSnapshot {
        public String type = "state";
        public int version = 1;
        public String deviceId = "";
        public String deviceName = "";
        public long sentAt;
        public List<AlarmRecord> alarms = new ArrayList<>();
        public List<Tombstone> alarmTombstones = new ArrayList<>();
        public List<TimerRecord> timers = new ArrayList<>();
        public List<Tombstone> timerTombstones = new ArrayList<>();
        public StopwatchRecord stopwatch;
        public CitiesRecord cities;
        public SettingsRecord settings;

        public JSONObject toJson() throws JSONException {
            JSONObject o = new JSONObject();
            o.put("type", type);
            o.put("version", version);
            o.put("deviceId", deviceId);
            o.put("deviceName", deviceName);
            o.put("sentAt", sentAt);

            JSONArray alarmsJson = new JSONArray();
            for (AlarmRecord a : alarms) alarmsJson.put(a.toJson());
            o.put("alarms", alarmsJson);

            JSONArray tombAlarmsJson = new JSONArray();
            for (Tombstone t : alarmTombstones) tombAlarmsJson.put(t.toJson());
            o.put("alarmTombstones", tombAlarmsJson);

            JSONArray timersJson = new JSONArray();
            for (TimerRecord t : timers) timersJson.put(t.toJson());
            o.put("timers", timersJson);

            JSONArray tombTimersJson = new JSONArray();
            for (Tombstone t : timerTombstones) tombTimersJson.put(t.toJson());
            o.put("timerTombstones", tombTimersJson);

            if (stopwatch != null) o.put("stopwatch", stopwatch.toJson());
            if (cities != null) o.put("cities", cities.toJson());
            if (settings != null) o.put("settings", settings.toJson());

            return o;
        }

        public static SyncSnapshot fromJson(JSONObject o) {
            SyncSnapshot s = new SyncSnapshot();
            s.type = o.optString("type", "state");
            s.version = o.optInt("version", 1);
            s.deviceId = o.optString("deviceId", "");
            s.deviceName = o.optString("deviceName", "");
            s.sentAt = o.optLong("sentAt", 0);

            JSONArray alarmsJson = o.optJSONArray("alarms");
            if (alarmsJson != null) {
                for (int i = 0; i < alarmsJson.length(); i++) {
                    JSONObject a = alarmsJson.optJSONObject(i);
                    if (a != null) s.alarms.add(AlarmRecord.fromJson(a));
                }
            }
            JSONArray tombAlarmsJson = o.optJSONArray("alarmTombstones");
            if (tombAlarmsJson != null) {
                for (int i = 0; i < tombAlarmsJson.length(); i++) {
                    JSONObject a = tombAlarmsJson.optJSONObject(i);
                    if (a != null) s.alarmTombstones.add(Tombstone.fromJson(a));
                }
            }
            JSONArray timersJson = o.optJSONArray("timers");
            if (timersJson != null) {
                for (int i = 0; i < timersJson.length(); i++) {
                    JSONObject t = timersJson.optJSONObject(i);
                    if (t != null) s.timers.add(TimerRecord.fromJson(t));
                }
            }
            JSONArray tombTimersJson = o.optJSONArray("timerTombstones");
            if (tombTimersJson != null) {
                for (int i = 0; i < tombTimersJson.length(); i++) {
                    JSONObject t = tombTimersJson.optJSONObject(i);
                    if (t != null) s.timerTombstones.add(Tombstone.fromJson(t));
                }
            }
            JSONObject sw = o.optJSONObject("stopwatch");
            if (sw != null) s.stopwatch = StopwatchRecord.fromJson(sw);
            JSONObject cities = o.optJSONObject("cities");
            if (cities != null) s.cities = CitiesRecord.fromJson(cities);
            JSONObject settings = o.optJSONObject("settings");
            if (settings != null) s.settings = SettingsRecord.fromJson(settings);

            return s;
        }
    }
}
