/*
 * SPDX-License-Identifier: GPL-3.0-only
 */

package com.best.deskclock.sync;

import android.content.Context;
import android.content.SharedPreferences;

import com.best.deskclock.DeskClockApplication;
import com.best.deskclock.data.City;
import com.best.deskclock.data.DataModel;
import com.best.deskclock.data.Lap;
import com.best.deskclock.data.Stopwatch;
import com.best.deskclock.data.Timer;
import com.best.deskclock.provider.Alarm;
import com.best.deskclock.settings.PreferencesKeys;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * Builds a {@link SyncModels.SyncSnapshot} describing the current local state. Each local alarm
 * and timer receives a stable {@code uuid} (persisted in {@link SyncState}) and its
 * {@code updatedAt} timestamp is bumped only when the record's fingerprint changes. Records that
 * have been deleted locally are emitted as tombstones.
 */
public final class SyncSnapshotBuilder {

    private SyncSnapshotBuilder() {
    }

    public static SyncModels.SyncSnapshot build(Context context) {
        final SyncState state = new SyncState(context);
        final long now = System.currentTimeMillis();

        final SyncModels.SyncSnapshot snapshot = new SyncModels.SyncSnapshot();
        snapshot.deviceId = SyncSettings.getDeviceId(context);
        snapshot.deviceName = SyncSettings.getDeviceName(context);
        snapshot.sentAt = now;

        buildAlarms(context, state, snapshot, now);
        buildTimers(context, state, snapshot, now);
        buildStopwatch(context, state, snapshot, now);
        buildCities(context, state, snapshot, now);
        buildSettings(context, state, snapshot, now);

        state.persist();
        return snapshot;
    }

    private static void buildAlarms(Context context, SyncState state, SyncModels.SyncSnapshot snapshot, long now) {
        final List<Alarm> alarms = Alarm.getAlarms(context.getContentResolver(), null);
        final Set<Long> presentIds = new HashSet<>();
        for (Alarm alarm : alarms) {
            presentIds.add(alarm.id);
            String uuid = state.getAlarmUuid(alarm.id);
            if (uuid == null) {
                uuid = newUuid();
                state.putAlarmUuid(alarm.id, uuid);
            }
            snapshot.alarms.add(toAlarmRecord(alarm, uuid, state, now));
        }

        // Tombstone any previously-mapped alarm that no longer exists locally.
        for (Map.Entry<String, Long> entry : state.getAllAlarmUuids().entrySet()) {
            final long dbId = entry.getValue();
            if (presentIds.contains(dbId)) {
                continue;
            }
            final String uuid = state.getAlarmUuid(dbId);
            if (uuid == null) {
                continue;
            }
            final long ts = Math.max(now, state.getAlarmTombstoneTs(uuid));
            state.putAlarmTombstone(uuid, ts);

            final SyncModels.Tombstone tombstone = new SyncModels.Tombstone();
            tombstone.uuid = uuid;
            tombstone.updatedAt = ts;
            snapshot.alarmTombstones.add(tombstone);

            state.removeAlarmUuid(dbId);
            state.removeAlarmUpdatedAt(uuid);
            state.removeAlarmFingerprint(uuid);
        }
    }

    private static SyncModels.AlarmRecord toAlarmRecord(Alarm alarm, String uuid, SyncState state, long now) {
        final SyncModels.AlarmRecord r = new SyncModels.AlarmRecord();
        r.uuid = uuid;
        r.enabled = alarm.enabled;
        r.year = alarm.year;
        r.month = alarm.month;
        r.day = alarm.day;
        r.hour = alarm.hour;
        r.minute = alarm.minutes;
        r.daysOfWeek = alarm.daysOfWeek == null ? 0 : alarm.daysOfWeek.getBits();
        r.label = alarm.label == null ? "" : alarm.label;
        r.vibrate = alarm.vibrate;
        r.vibrationPattern = alarm.vibrationPattern == null ? "default" : alarm.vibrationPattern;
        r.flash = alarm.flash;
        r.ringtone = alarm.alert == null ? "default" : alarm.alert.toString();
        r.deleteAfterUse = alarm.deleteAfterUse;
        r.autoSilenceDuration = alarm.autoSilenceDuration;
        r.snoozeDuration = alarm.snoozeDuration;
        r.missedAlarmRepeatLimit = alarm.missedAlarmRepeatLimit;
        r.crescendoDuration = alarm.crescendoDuration;
        r.alarmVolume = alarm.alarmVolume;
        r.manualSortOrder = alarm.manualSortOrder;
        r.pauseStartDate = alarm.pauseStartDate;
        r.pauseEndDate = alarm.pauseEndDate;
        r.backgroundImage = alarm.backgroundImage == null ? "" : alarm.backgroundImage;
        r.blurIntensity = alarm.blurIntensity;

        final String fingerprint = SyncFingerprints.alarmFingerprint(r);
        long updatedAt = state.getAlarmUpdatedAt(uuid);
        if (!Objects.equals(fingerprint, state.getAlarmFingerprint(uuid))) {
            updatedAt = now;
            state.putAlarmFingerprint(uuid, fingerprint);
            state.putAlarmUpdatedAt(uuid, updatedAt);
        }
        r.updatedAt = updatedAt;
        return r;
    }

    private static void buildTimers(Context context, SyncState state, SyncModels.SyncSnapshot snapshot, long now) {
        final DataModel dataModel = DataModel.getDataModel();
        final List<Timer>[] holder = new List[1];
        dataModel.run(() -> holder[0] = new ArrayList<>(dataModel.getTimers()));
        final List<Timer> timers = holder[0];

        final Set<Integer> presentIds = new HashSet<>();
        for (Timer timer : timers) {
            presentIds.add(timer.getId());
            String uuid = state.getTimerUuid(timer.getId());
            if (uuid == null) {
                uuid = newUuid();
                state.putTimerUuid(timer.getId(), uuid);
            }
            snapshot.timers.add(toTimerRecord(timer, uuid, state, now));
        }

        // Tombstone any previously-mapped timer that no longer exists locally.
        for (Map.Entry<String, Long> entry : state.getAllTimerUuids().entrySet()) {
            final int timerId = entry.getValue().intValue();
            if (presentIds.contains(timerId)) {
                continue;
            }
            final String uuid = state.getTimerUuid(timerId);
            if (uuid == null) {
                continue;
            }
            final long ts = Math.max(now, state.getTimerTombstoneTs(uuid));
            state.putTimerTombstone(uuid, ts);

            final SyncModels.Tombstone tombstone = new SyncModels.Tombstone();
            tombstone.uuid = uuid;
            tombstone.updatedAt = ts;
            snapshot.timerTombstones.add(tombstone);

            state.removeTimerUuid(timerId);
            state.removeTimerUpdatedAt(uuid);
            state.removeTimerFingerprint(uuid);
        }
    }

    private static SyncModels.TimerRecord toTimerRecord(Timer timer, String uuid, SyncState state, long now) {
        final SyncModels.TimerRecord r = new SyncModels.TimerRecord();
        r.uuid = uuid;
        r.state = timer.getState().name();
        r.length = timer.getLength();
        r.totalLength = timer.getTotalLength();
        r.remainingTime = timer.getRemainingTime();
        if (timer.isRunning() && r.remainingTime < 0) {
            r.remainingTime = 0;
        }
        r.label = timer.getLabel() == null ? "" : timer.getLabel();
        r.buttonTime = timer.getButtonTime() == null ? "1" : timer.getButtonTime();
        r.ringtone = timer.getRingtoneUri() == null ? "default" : timer.getRingtoneUri().toString();
        r.autoSilence = timer.getAutoSilence();
        r.crescendoDuration = timer.getVolumeCrescendoDuration();
        r.vibrate = timer.isVibrate();
        r.vibrationPattern = timer.getVibrationPattern() == null ? "default" : timer.getVibrationPattern();
        r.flashOn = timer.isFlashOn();
        r.turnOffMedia = timer.getTurnOffMedia();
        r.deleteAfterUse = timer.getDeleteAfterUse();

        final String fingerprint = SyncFingerprints.timerFingerprint(r);
        long updatedAt = state.getTimerUpdatedAt(uuid);
        if (!Objects.equals(fingerprint, state.getTimerFingerprint(uuid))) {
            updatedAt = now;
            state.putTimerFingerprint(uuid, fingerprint);
            state.putTimerUpdatedAt(uuid, updatedAt);
        }
        r.updatedAt = updatedAt;
        return r;
    }

    private static void buildStopwatch(Context context, SyncState state, SyncModels.SyncSnapshot snapshot, long now) {
        final DataModel dataModel = DataModel.getDataModel();
        final Stopwatch[] swHolder = new Stopwatch[1];
        final List<Lap>[] lapsHolder = new List[1];
        dataModel.run(() -> {
            swHolder[0] = dataModel.getStopwatch();
            lapsHolder[0] = new ArrayList<>(dataModel.getLaps());
        });

        final Stopwatch stopwatch = swHolder[0];
        final List<Lap> laps = lapsHolder[0];

        final SyncModels.StopwatchRecord record = new SyncModels.StopwatchRecord();
        record.state = stopwatch.getState().name();
        record.accumulatedTime = stopwatch.getTotalTime();
        for (Lap lap : laps) {
            final SyncModels.LapRecord lapRecord = new SyncModels.LapRecord();
            lapRecord.number = lap.getLapNumber();
            lapRecord.accumulatedTime = lap.getAccumulatedTime();
            record.laps.add(lapRecord);
        }

        final String fingerprint = SyncFingerprints.stopwatchFingerprint(stopwatch.getState().name(),
            stopwatch.getAccumulatedTime(), laps);
        long updatedAt = state.getStopwatchUpdatedAt();
        if (!Objects.equals(fingerprint, state.getStopwatchFingerprint())) {
            updatedAt = now;
            state.putStopwatchFingerprint(fingerprint);
            state.putStopwatchUpdatedAt(updatedAt);
        }
        record.updatedAt = updatedAt;
        snapshot.stopwatch = record;
    }

    private static void buildCities(Context context, SyncState state, SyncModels.SyncSnapshot snapshot, long now) {
        final DataModel dataModel = DataModel.getDataModel();
        final List<City>[] holder = new List[1];
        dataModel.run(() -> holder[0] = new ArrayList<>(dataModel.getSelectedCities()));
        final List<City> selected = holder[0];

        final SyncModels.CitiesRecord record = new SyncModels.CitiesRecord();
        final SharedPreferences prefs = DeskClockApplication.getDefaultSharedPreferences(context);
        for (City city : selected) {
            record.ids.add(city.getId());
            final String note = prefs.getString(PreferencesKeys.KEY_CITY_NOTE + city.getId(), null);
            if (note != null && !note.isEmpty()) {
                record.notes.put(city.getId(), note);
            }
        }

        final String fingerprint = SyncFingerprints.citiesFingerprint(record);
        long updatedAt = state.getCitiesUpdatedAt();
        if (!Objects.equals(fingerprint, state.getCitiesFingerprint())) {
            updatedAt = now;
            state.putCitiesFingerprint(fingerprint);
            state.putCitiesUpdatedAt(updatedAt);
        }
        record.updatedAt = updatedAt;
        snapshot.cities = record;
    }

    private static void buildSettings(Context context, SyncState state, SyncModels.SyncSnapshot snapshot, long now) {
        final SyncModels.SettingsRecord record = new SyncModels.SettingsRecord();
        long maxUpdatedAt = 0;
        for (String key : SyncSettingsCodec.SYNCED_KEYS) {
            final Object value = SyncSettingsCodec.readValue(context, key);
            if (value == null) {
                continue;
            }
            final String valueJson = SyncSettingsCodec.valueToJson(value);
            long updatedAt = state.getSettingTs(key);
            if (!Objects.equals(valueJson, state.getSettingValue(key))) {
                updatedAt = now;
                state.putSetting(key, updatedAt, valueJson);
            }

            final SyncModels.SettingValue settingValue = new SyncModels.SettingValue();
            settingValue.ts = updatedAt;
            settingValue.value = value;
            record.values.put(key, settingValue);
            if (updatedAt > maxUpdatedAt) {
                maxUpdatedAt = updatedAt;
            }
        }
        record.updatedAt = maxUpdatedAt;
        snapshot.settings = record;
    }

    private static String newUuid() {
        return UUID.randomUUID().toString().replace("-", "");
    }
}
