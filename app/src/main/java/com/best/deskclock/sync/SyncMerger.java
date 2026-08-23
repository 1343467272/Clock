/*
 * SPDX-License-Identifier: GPL-3.0-only
 */

package com.best.deskclock.sync;

import android.content.ContentResolver;
import android.content.Context;
import android.content.SharedPreferences;
import android.net.Uri;

import com.best.deskclock.DeskClockApplication;
import com.best.deskclock.alarms.AlarmStateManager;
import com.best.deskclock.data.City;
import com.best.deskclock.data.DataModel;
import com.best.deskclock.data.Lap;
import com.best.deskclock.data.Stopwatch;
import com.best.deskclock.data.Timer;
import com.best.deskclock.data.Weekdays;
import com.best.deskclock.provider.Alarm;
import com.best.deskclock.provider.AlarmInstance;
import com.best.deskclock.settings.PreferencesKeys;
import com.best.deskclock.utils.Utils;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Applies a remote {@link SyncModels.SyncSnapshot} to the local device using last-write-wins
 * semantics keyed by each record's {@code updatedAt}. Running timers are re-based against the
 * snapshot's {@code sentAt} timestamp so they continue to count down correctly. Every applied
 * change is mirrored into {@link SyncState} so the local snapshot builder does not re-broadcast
 * the same content.
 */
public final class SyncMerger {

    private SyncMerger() {
    }

    public static void merge(Context context, SyncModels.SyncSnapshot remote) {
        if (remote == null || remote.version != 1) {
            return;
        }
        if (remote.deviceId == null || remote.deviceId.isEmpty()
            || remote.deviceId.equals(SyncSettings.getDeviceId(context))) {
            return;
        }

        final SyncState state = new SyncState(context);
        mergeAlarms(context, state, remote);
        mergeTimers(context, state, remote);
        mergeStopwatch(context, state, remote);
        mergeCities(context, state, remote);
        mergeSettings(context, state, remote);

        state.persist();
    }

    // ---------------------------------------------------------------- alarms

    private static void mergeAlarms(Context context, SyncState state, SyncModels.SyncSnapshot remote) {
        final ContentResolver cr = context.getContentResolver();
        final List<Alarm> localAlarms = Alarm.getAlarms(cr, null);
        final Map<String, Alarm> byUuid = new HashMap<>();
        for (Alarm alarm : localAlarms) {
            final String uuid = state.getAlarmUuid(alarm.id);
            if (uuid != null) {
                byUuid.put(uuid, alarm);
            }
        }

        boolean changed = false;

        for (SyncModels.Tombstone tombstone : remote.alarmTombstones) {
            final Alarm local = byUuid.get(tombstone.uuid);
            if (local != null) {
                if (tombstone.updatedAt >= state.getAlarmUpdatedAt(tombstone.uuid)) {
                    Alarm.deleteAlarm(cr, local.id);
                    state.removeAlarmUuid(local.id);
                    state.removeAlarmUpdatedAt(tombstone.uuid);
                    state.removeAlarmFingerprint(tombstone.uuid);
                    changed = true;
                }
            }
            if (tombstone.updatedAt > state.getAlarmTombstoneTs(tombstone.uuid)) {
                state.putAlarmTombstone(tombstone.uuid, tombstone.updatedAt);
            }
        }

        // Rebuild the uuid lookup so records in this snapshot cannot resurrect a just-deleted alarm.
        byUuid.clear();
        for (Alarm alarm : Alarm.getAlarms(cr, null)) {
            final String uuid = state.getAlarmUuid(alarm.id);
            if (uuid != null) {
                byUuid.put(uuid, alarm);
            }
        }

        for (SyncModels.AlarmRecord record : remote.alarms) {
            if (record.uuid.isEmpty()) {
                continue;
            }
            final long tombTs = state.getAlarmTombstoneTs(record.uuid);
            if (tombTs > 0 && record.updatedAt <= tombTs) {
                continue; // tombstoned more recently than this record
            }

            final Alarm local = byUuid.get(record.uuid);
            if (record.silencedAt > state.getAlarmSilencedAt(record.uuid)) {
                state.putAlarmSilencedAt(record.uuid, record.silencedAt);
                if (local != null) {
                    silenceFiringAlarm(context, cr, local.id);
                }
            }
            if (local == null) {
                final Alarm alarm = applyAlarmRecord(new Alarm(), record);
                alarm.addAlarm(cr);
                state.putAlarmUuid(alarm.id, record.uuid);
                state.putAlarmUpdatedAt(record.uuid, record.updatedAt);
                state.putAlarmFingerprint(record.uuid, SyncFingerprints.alarmFingerprint(record));
                changed = true;
            } else if (record.updatedAt > state.getAlarmUpdatedAt(record.uuid)) {
                if (SyncFingerprints.alarmFingerprint(record).equals(state.getAlarmFingerprint(record.uuid))) {
                    continue; // content already identical
                }
                applyAlarmRecord(local, record);
                local.updateAlarm(cr);
                state.putAlarmUpdatedAt(record.uuid, record.updatedAt);
                state.putAlarmFingerprint(record.uuid, SyncFingerprints.alarmFingerprint(record));
                changed = true;
            }
        }

        if (changed) {
            AlarmStateManager.updateNextAlarm(context);
        }
    }

    /** Stops only the currently firing instance for this alarm; future repeating instances stay scheduled. */
    private static void silenceFiringAlarm(Context context, ContentResolver cr, long alarmId) {
        final AlarmInstance instance = AlarmInstance.getFiredOrSnoozedInstanceForAlarm(cr, alarmId);
        if (instance != null && instance.mAlarmState == AlarmInstance.FIRED_STATE) {
            AlarmStateManager.deleteInstanceAndUpdateParent(context, instance, true);
        }
    }

    private static Alarm applyAlarmRecord(Alarm alarm, SyncModels.AlarmRecord r) {
        alarm.enabled = r.enabled;
        alarm.year = r.year;
        alarm.month = r.month;
        alarm.day = r.day;
        alarm.hour = r.hour;
        alarm.minutes = r.minute;
        alarm.daysOfWeek = new Weekdays(r.daysOfWeek);
        alarm.label = r.label == null ? "" : r.label;
        alarm.vibrate = r.vibrate;
        alarm.vibrationPattern = r.vibrationPattern == null || r.vibrationPattern.isEmpty() ? "default" : r.vibrationPattern;
        alarm.flash = r.flash;
        alarm.alert = r.ringtone == null || r.ringtone.isEmpty() || "default".equalsIgnoreCase(r.ringtone)
            ? null : Uri.parse(r.ringtone);
        alarm.deleteAfterUse = r.deleteAfterUse;
        alarm.autoSilenceDuration = r.autoSilenceDuration;
        alarm.snoozeDuration = r.snoozeDuration;
        alarm.missedAlarmRepeatLimit = r.missedAlarmRepeatLimit;
        alarm.crescendoDuration = r.crescendoDuration;
        alarm.alarmVolume = r.alarmVolume;
        alarm.manualSortOrder = r.manualSortOrder;
        alarm.pauseStartDate = r.pauseStartDate;
        alarm.pauseEndDate = r.pauseEndDate;
        alarm.backgroundImage = r.backgroundImage == null ? "" : r.backgroundImage;
        alarm.blurIntensity = r.blurIntensity;
        alarm.repeatType = r.repeatType;
        alarm.shiftWorkDays = r.shiftWorkDays;
        alarm.shiftRestDays = r.shiftRestDays;
        alarm.shiftStartDate = r.shiftStartDate;
        return alarm;
    }

    // ---------------------------------------------------------------- timers

    private static void mergeTimers(Context context, SyncState state, SyncModels.SyncSnapshot remote) {
        final DataModel dataModel = DataModel.getDataModel();
        final Map<String, Timer>[] byUuidHolder = new Map[1];
        dataModel.run(() -> {
            final Map<String, Timer> byUuid = new HashMap<>();
            for (Timer timer : dataModel.getTimers()) {
                final String uuid = state.getTimerUuid(timer.getId());
                if (uuid != null) {
                    byUuid.put(uuid, timer);
                }
            }
            byUuidHolder[0] = byUuid;
        });
        final Map<String, Timer> byUuid = byUuidHolder[0];

        for (SyncModels.Tombstone tombstone : remote.timerTombstones) {
            final Timer local = byUuid.get(tombstone.uuid);
            if (local != null) {
                if (tombstone.updatedAt >= state.getTimerUpdatedAt(tombstone.uuid)) {
                    dataModel.removeTimerFromSync(local);
                    state.removeTimerUuid(local.getId());
                    state.removeTimerUpdatedAt(tombstone.uuid);
                    state.removeTimerFingerprint(tombstone.uuid);
                }
            }
            if (tombstone.updatedAt > state.getTimerTombstoneTs(tombstone.uuid)) {
                state.putTimerTombstone(tombstone.uuid, tombstone.updatedAt);
            }
        }

        // Rebuild the uuid lookup so records in this snapshot cannot resurrect a just-deleted timer.
        byUuid.clear();
        dataModel.run(() -> {
            for (Timer timer : dataModel.getTimers()) {
                final String uuid = state.getTimerUuid(timer.getId());
                if (uuid != null) {
                    byUuid.put(uuid, timer);
                }
            }
        });

        for (SyncModels.TimerRecord record : remote.timers) {
            if (record.uuid.isEmpty()) {
                continue;
            }
            final long tombTs = state.getTimerTombstoneTs(record.uuid);
            if (tombTs > 0 && record.updatedAt <= tombTs) {
                continue;
            }

            final Timer local = byUuid.get(record.uuid);
            if (local == null) {
                final Timer timer = buildTimer(record, remote.sentAt, 0);
                final Timer stored = dataModel.addTimerFromSync(timer);
                state.putTimerUuid(stored.getId(), record.uuid);
                state.putTimerUpdatedAt(record.uuid, record.updatedAt);
                state.putTimerFingerprint(record.uuid, SyncFingerprints.timerFingerprint(record));
                // A snapshot can contain the same record more than once after a retried
                // exchange. Keep the lookup current so the next occurrence updates this
                // timer instead of creating another one.
                byUuid.put(record.uuid, stored);
            } else if (record.updatedAt > state.getTimerUpdatedAt(record.uuid)) {
                if (SyncFingerprints.timerFingerprint(record).equals(state.getTimerFingerprint(record.uuid))) {
                    continue; // content already identical
                }
                final Timer updated = buildTimer(record, remote.sentAt, local.getId());
                dataModel.updateTimerFromSync(updated);
                state.putTimerUpdatedAt(record.uuid, record.updatedAt);
                state.putTimerFingerprint(record.uuid, SyncFingerprints.timerFingerprint(record));
            }
        }
    }

    private static Timer buildTimer(SyncModels.TimerRecord r, long sentAt, int id) {
        final Timer.State state = parseTimerState(r.state);
        final long wallClock = Utils.wallClock();
        final long remaining;
        final long lastStartTime;
        final long lastWallClockTime;
        if (state == Timer.State.RUNNING) {
            remaining = Math.max(0, r.remainingTime - (wallClock - sentAt));
            lastStartTime = Utils.now();
            lastWallClockTime = wallClock;
        } else if (state == Timer.State.EXPIRED || state == Timer.State.MISSED) {
            remaining = 0;
            lastStartTime = Long.MIN_VALUE;
            lastWallClockTime = Long.MIN_VALUE;
        } else {
            remaining = r.remainingTime;
            lastStartTime = Long.MIN_VALUE;
            lastWallClockTime = Long.MIN_VALUE;
        }

        final Uri ringtone = r.ringtone == null || r.ringtone.isEmpty() || "default".equalsIgnoreCase(r.ringtone)
            ? null : Uri.parse(r.ringtone);

        return new Timer(id, state, r.length, r.totalLength, lastStartTime, lastWallClockTime, remaining,
            r.label == null ? "" : r.label,
            r.buttonTime == null ? "1" : r.buttonTime,
            ringtone,
            r.autoSilence, r.crescendoDuration, r.vibrate,
            r.vibrationPattern == null ? "default" : r.vibrationPattern,
            r.flashOn, r.turnOffMedia, r.deleteAfterUse
        );
    }

    private static Timer.State parseTimerState(String name) {
        if (name == null) {
            return Timer.State.RESET;
        }
        try {
            return Timer.State.valueOf(name);
        } catch (IllegalArgumentException e) {
            return Timer.State.RESET;
        }
    }

    // ---------------------------------------------------------------- stopwatch

    private static void mergeStopwatch(Context context, SyncState state, SyncModels.SyncSnapshot remote) {
        if (remote.stopwatch == null) {
            return;
        }
        final SyncModels.StopwatchRecord record = remote.stopwatch;
        if (record.updatedAt <= state.getStopwatchUpdatedAt()) {
            return;
        }

        final Stopwatch stopwatch = buildStopwatch(record, remote.sentAt);
        final List<Lap> laps = new ArrayList<>();
        for (SyncModels.LapRecord lapRecord : record.laps) {
            laps.add(new Lap(lapRecord.number, 0, lapRecord.accumulatedTime));
        }

        DataModel.getDataModel().applyStopwatchFromSync(stopwatch, laps);

        state.putStopwatchUpdatedAt(record.updatedAt);
        state.putStopwatchFingerprint(SyncFingerprints.stopwatchFingerprint(
            stopwatch.getState().name(), stopwatch.getAccumulatedTime(), laps));
    }

    private static Stopwatch buildStopwatch(SyncModels.StopwatchRecord r, long sentAt) {
        final long elapsedSinceSent = Math.max(0, System.currentTimeMillis() - sentAt);
        final Stopwatch.State state;
        try {
            state = Stopwatch.State.valueOf(r.state);
        } catch (IllegalArgumentException e) {
            return new Stopwatch(Stopwatch.State.RESET, Long.MIN_VALUE, Long.MIN_VALUE, 0);
        }
        if (state == Stopwatch.State.RUNNING) {
            // The accumulated time is live at the moment the snapshot was sent; account for the
            // transit delay so the stopwatch continues counting from the correct value.
            return new Stopwatch(state, Utils.now(), Utils.wallClock(),
                Math.max(0, r.accumulatedTime + elapsedSinceSent));
        }
        return new Stopwatch(state, Long.MIN_VALUE, Long.MIN_VALUE, Math.max(0, r.accumulatedTime));
    }

    // ---------------------------------------------------------------- cities

    private static void mergeCities(Context context, SyncState state, SyncModels.SyncSnapshot remote) {
        if (remote.cities == null) {
            return;
        }
        final SyncModels.CitiesRecord record = remote.cities;
        if (record.updatedAt <= state.getCitiesUpdatedAt()) {
            return;
        }

        // Persist the notes for every selected city; a missing note means it was removed remotely.
        final SharedPreferences prefs = DeskClockApplication.getDefaultSharedPreferences(context);
        for (String id : record.ids) {
            final String note = record.notes.get(id);
            final String key = PreferencesKeys.KEY_CITY_NOTE + id;
            if (note == null || note.isEmpty()) {
                prefs.edit().remove(key).apply();
            } else {
                prefs.edit().putString(key, note).apply();
            }
        }

        // Apply the selection in the remote display order.
        final DataModel dataModel = DataModel.getDataModel();
        final Map<String, City>[] cityMapHolder = new Map[1];
        dataModel.run(() -> {
            final Map<String, City> cityMap = new HashMap<>();
            for (City city : dataModel.getAllCities()) {
                cityMap.put(city.getId(), city);
            }
            cityMapHolder[0] = cityMap;
        });
        final List<City> selected = new ArrayList<>();
        for (String id : record.ids) {
            final City city = cityMapHolder[0].get(id);
            if (city != null) {
                selected.add(city);
            }
        }
        dataModel.applyCitiesFromSync(selected);

        state.putCitiesUpdatedAt(record.updatedAt);
        state.putCitiesFingerprint(SyncFingerprints.citiesFingerprint(record));
    }

    // ---------------------------------------------------------------- settings

    private static void mergeSettings(Context context, SyncState state, SyncModels.SyncSnapshot remote) {
        if (remote.settings == null) {
            return;
        }
        for (Map.Entry<String, SyncModels.SettingValue> entry : remote.settings.values.entrySet()) {
            final String key = entry.getKey();
            final SyncModels.SettingValue incoming = entry.getValue();
            if (incoming.ts <= state.getSettingTs(key)) {
                continue;
            }
            if (SyncSettingsCodec.applyValue(context, key, incoming.value)) {
                state.putSetting(key, incoming.ts, SyncSettingsCodec.valueToJson(incoming.value));
            }
        }
    }
}
