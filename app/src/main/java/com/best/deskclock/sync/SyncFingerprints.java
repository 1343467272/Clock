/*
 * SPDX-License-Identifier: GPL-3.0-only
 */

package com.best.deskclock.sync;

import com.best.deskclock.data.Lap;

import java.util.List;
import java.util.Map;

/**
 * Computes stable content fingerprints over wire records. The sync engine uses them to detect
 * local changes: {@code updatedAt} is bumped only when a record's fingerprint changes, which
 * keeps already-synchronized, untouched data from being re-broadcast.
 */
public final class SyncFingerprints {

    private SyncFingerprints() {
    }

    /**
     * @return a fingerprint over all user-visible alarm fields. Time-based fields such as
     * {@code hour}/{@code minute} are included so that "tomorrow" alarms stay in sync.
     */
    public static String alarmFingerprint(SyncModels.AlarmRecord r) {
        return join(
            r.enabled,
            r.year, r.month, r.day, r.hour, r.minute, r.daysOfWeek,
            r.label,
            r.vibrate, r.vibrationPattern, r.flash, r.ringtone,
            r.deleteAfterUse,
            r.autoSilenceDuration, r.snoozeDuration, r.missedAlarmRepeatLimit,
            r.crescendoDuration, r.alarmVolume, r.manualSortOrder,
            r.pauseStartDate, r.pauseEndDate,
            r.backgroundImage, r.blurIntensity
        );
    }

    /**
     * @return a fingerprint over all timer fields except {@code remainingTime}, which advances
     * while a running timer counts down and must not trigger re-synchronization on its own.
     */
    public static String timerFingerprint(SyncModels.TimerRecord r) {
        return join(
            r.state, r.length, r.totalLength,
            r.label, r.buttonTime, r.ringtone,
            r.autoSilence, r.crescendoDuration,
            r.vibrate, r.vibrationPattern, r.flashOn, r.turnOffMedia, r.deleteAfterUse
        );
    }

    /**
     * @param state            the stopwatch state
     * @param accumulatedTime  the stored (non-live) accumulated time, so a running stopwatch does
     *                         not produce a new fingerprint every second
     * @param laps             the recorded laps, newest first
     * @return a fingerprint over the stopwatch state, accumulated time and laps
     */
    public static String stopwatchFingerprint(String state, long accumulatedTime, List<Lap> laps) {
        final StringBuilder sb = new StringBuilder();
        sb.append(state).append('|').append(accumulatedTime).append('|');
        if (laps != null) {
            for (Lap lap : laps) {
                sb.append(lap.getLapNumber()).append(':').append(lap.getAccumulatedTime()).append(';');
            }
        }
        return sb.toString();
    }

    /**
     * @return a fingerprint over the selected city ids and their notes.
     */
    public static String citiesFingerprint(SyncModels.CitiesRecord r) {
        final StringBuilder sb = new StringBuilder();
        for (String id : r.ids) {
            sb.append(id).append(';');
        }
        sb.append('|');
        for (Map.Entry<String, String> entry : r.notes.entrySet()) {
            sb.append(entry.getKey()).append('=').append(entry.getValue()).append(';');
        }
        return sb.toString();
    }

    private static String join(Object... parts) {
        final StringBuilder sb = new StringBuilder();
        for (Object part : parts) {
            sb.append(part == null ? "" : part).append('|');
        }
        return sb.toString();
    }
}
