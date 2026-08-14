/*
 * SPDX-License-Identifier: GPL-3.0-only
 */

package com.best.deskclock.sync;

import android.content.Context;
import android.content.SharedPreferences;

import com.best.deskclock.DeskClockApplication;

import org.json.JSONException;
import org.json.JSONObject;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;

/**
 * Persistent metadata that backs the LAN sync engine: the stable {@code uuid} assigned to each
 * local alarm and timer, the last-seen {@code updatedAt} timestamp and content fingerprint per
 * record, tombstones for deleted records, and the last-sent state of synced settings.
 *
 * <p>Everything is stored as a single JSON blob in a private preferences file so that the wire
 * state survives process restarts without touching the application's main preferences.</p>
 */
public final class SyncState {

    private static final String PREFS_NAME = "sync_state";
    private static final String KEY_BLOB = "state";

    private final SharedPreferences mPrefs;
    private JSONObject mData;

    public SyncState(Context context) {
        mPrefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
    }

    private JSONObject data() {
        if (mData == null) {
            final String json = mPrefs.getString(KEY_BLOB, null);
            if (json == null || json.isEmpty()) {
                mData = new JSONObject();
            } else {
                try {
                    mData = new JSONObject(json);
                } catch (JSONException e) {
                    mData = new JSONObject();
                }
            }
        }
        return mData;
    }

    private JSONObject object(String key) {
        JSONObject o = data().optJSONObject(key);
        if (o == null) {
            o = new JSONObject();
            try {
                data().put(key, o);
            } catch (JSONException ignored) {
            }
        }
        return o;
    }

    /** Persists the current blob to disk. */
    public void persist() {
        mPrefs.edit().putString(KEY_BLOB, data().toString()).apply();
    }

    // ---------------------------------------------------------------- alarms

    public String getAlarmUuid(long dbId) {
        return object("alarmUuids").optString(String.valueOf(dbId), null);
    }

    public void putAlarmUuid(long dbId, String uuid) {
        putQuiet(object("alarmUuids"), String.valueOf(dbId), uuid);
    }

    public void removeAlarmUuid(long dbId) {
        object("alarmUuids").remove(String.valueOf(dbId));
    }

    public Map<String, Long> getAllAlarmUuids() {
        final Map<String, Long> result = new HashMap<>();
        final JSONObject o = object("alarmUuids");
        for (Iterator<String> it = o.keys(); it.hasNext(); ) {
            final String dbId = it.next();
            result.put(dbId, Long.parseLong(dbId));
        }
        return result;
    }

    public long getAlarmUpdatedAt(String uuid) {
        return object("alarmTs").optLong(uuid, 0);
    }

    public void putAlarmUpdatedAt(String uuid, long ts) {
        putQuiet(object("alarmTs"), uuid, ts);
    }

    public void removeAlarmUpdatedAt(String uuid) {
        object("alarmTs").remove(uuid);
    }

    public String getAlarmFingerprint(String uuid) {
        return object("alarmFp").optString(uuid, null);
    }

    public void putAlarmFingerprint(String uuid, String fingerprint) {
        putQuiet(object("alarmFp"), uuid, fingerprint);
    }

    public void removeAlarmFingerprint(String uuid) {
        object("alarmFp").remove(uuid);
    }

    public Map<String, Long> getAlarmTombstones() {
        return readLongMap(object("alarmTombs"));
    }

    public long getAlarmTombstoneTs(String uuid) {
        return object("alarmTombs").optLong(uuid, 0);
    }

    public void putAlarmTombstone(String uuid, long ts) {
        putQuiet(object("alarmTombs"), uuid, ts);
    }

    // ---------------------------------------------------------------- timers

    public String getTimerUuid(int timerId) {
        return object("timerUuids").optString(String.valueOf(timerId), null);
    }

    public void putTimerUuid(int timerId, String uuid) {
        putQuiet(object("timerUuids"), String.valueOf(timerId), uuid);
    }

    public void removeTimerUuid(int timerId) {
        object("timerUuids").remove(String.valueOf(timerId));
    }

    public Map<String, Long> getAllTimerUuids() {
        final Map<String, Long> result = new HashMap<>();
        final JSONObject o = object("timerUuids");
        for (Iterator<String> it = o.keys(); it.hasNext(); ) {
            final String timerId = it.next();
            result.put(timerId, Long.parseLong(timerId));
        }
        return result;
    }

    public long getTimerUpdatedAt(String uuid) {
        return object("timerTs").optLong(uuid, 0);
    }

    public void putTimerUpdatedAt(String uuid, long ts) {
        putQuiet(object("timerTs"), uuid, ts);
    }

    public void removeTimerUpdatedAt(String uuid) {
        object("timerTs").remove(uuid);
    }

    public String getTimerFingerprint(String uuid) {
        return object("timerFp").optString(uuid, null);
    }

    public void putTimerFingerprint(String uuid, String fingerprint) {
        putQuiet(object("timerFp"), uuid, fingerprint);
    }

    public void removeTimerFingerprint(String uuid) {
        object("timerFp").remove(uuid);
    }

    public Map<String, Long> getTimerTombstones() {
        return readLongMap(object("timerTombs"));
    }

    public long getTimerTombstoneTs(String uuid) {
        return object("timerTombs").optLong(uuid, 0);
    }

    public void putTimerTombstone(String uuid, long ts) {
        putQuiet(object("timerTombs"), uuid, ts);
    }

    // ---------------------------------------------------------------- stopwatch

    public long getStopwatchUpdatedAt() {
        return data().optLong("stopwatchTs", 0);
    }

    public void putStopwatchUpdatedAt(long ts) {
        putQuiet(data(), "stopwatchTs", ts);
    }

    public String getStopwatchFingerprint() {
        return data().optString("stopwatchFp", null);
    }

    public void putStopwatchFingerprint(String fingerprint) {
        putQuiet(data(), "stopwatchFp", fingerprint);
    }

    // ---------------------------------------------------------------- cities

    public long getCitiesUpdatedAt() {
        return data().optLong("citiesTs", 0);
    }

    public void putCitiesUpdatedAt(long ts) {
        putQuiet(data(), "citiesTs", ts);
    }

    public String getCitiesFingerprint() {
        return data().optString("citiesFp", null);
    }

    public void putCitiesFingerprint(String fingerprint) {
        putQuiet(data(), "citiesFp", fingerprint);
    }

    // ---------------------------------------------------------------- settings

    /**
     * @return the last-sent {@code ts} for the given setting key, or {@code -1} if unknown.
     */
    public long getSettingTs(String key) {
        final JSONObject o = object("settings").optJSONObject(key);
        return o == null ? -1 : o.optLong("ts", -1);
    }

    /**
     * @return the last-sent value (JSON string) for the given setting key, or {@code null}.
     */
    public String getSettingValue(String key) {
        final JSONObject o = object("settings").optJSONObject(key);
        return o == null ? null : o.optString("value", null);
    }

    public void putSetting(String key, long ts, String valueJson) {
        final JSONObject entry = new JSONObject();
        try {
            entry.put("ts", ts);
            entry.put("value", valueJson);
            object("settings").put(key, entry);
        } catch (JSONException ignored) {
        }
    }

    public void removeSetting(String key) {
        object("settings").remove(key);
    }

    // ---------------------------------------------------------------- helpers

    private static Map<String, Long> readLongMap(JSONObject o) {
        final Map<String, Long> result = new HashMap<>();
        for (Iterator<String> it = o.keys(); it.hasNext(); ) {
            final String key = it.next();
            result.put(key, o.optLong(key, 0));
        }
        return result;
    }

    private static void putQuiet(JSONObject o, String key, long value) {
        try {
            o.put(key, value);
        } catch (JSONException ignored) {
        }
    }

    private static void putQuiet(JSONObject o, String key, String value) {
        try {
            o.put(key, value);
        } catch (JSONException ignored) {
        }
    }
}
