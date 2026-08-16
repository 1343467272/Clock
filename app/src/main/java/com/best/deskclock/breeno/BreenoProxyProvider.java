/*
 * Copyright (C) 2026 The Clock Project
 * modified
 * SPDX-License-Identifier: Apache-2.0 AND GPL-3.0-only
 */

package com.best.deskclock.breeno;

import static com.best.deskclock.DeskClockApplication.getDefaultSharedPreferences;
import static com.best.deskclock.settings.PreferencesDefaultValues.DEFAULT_SPECIFIC_ALARM_BACKGROUND_IMAGE;

import android.content.ContentProvider;
import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.media.AudioManager;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;

import com.best.deskclock.BuildConfig;
import com.best.deskclock.R;
import com.best.deskclock.alarms.AlarmStateManager;
import com.best.deskclock.data.DataModel;
import com.best.deskclock.data.SettingsDAO;
import com.best.deskclock.data.Timer;
import com.best.deskclock.data.Weekdays;
import com.best.deskclock.events.Events;
import com.best.deskclock.provider.Alarm;
import com.best.deskclock.provider.AlarmInstance;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * RPC surface used by the LSPosed "BreenoProxy" module (running inside com.coloros.alarmclock).
 * Translates Breeno's proprietary AI-provider calls into this app's real alarm/timer logic.
 * The module calls {@code content://com.best.deskclock.breeno} with the shared {@link #TOKEN}.
 */
public class BreenoProxyProvider extends ContentProvider {

    public static final String AUTHORITY = BuildConfig.APPLICATION_ID + ".breeno";
    public static final Uri CONTENT_URI = Uri.parse("content://" + AUTHORITY);

    /**
     * Shared secret between this provider and the Xposed module. Change both together.
     */
    static final String TOKEN = "BreenoProxy#2026#com.best.deskclock";

    // OPPO result codes (see com.oplus.alarmclock.ai.AiSupportContentProvider)
    private static final int RESULT_SUCCESS = 1;
    private static final int RESULT_ERROR = -1;
    private static final int RESULT_NO_ALARM_FOUND = -3;
    private static final int RESULT_FOUND_MUL_ALARMS = -4;
    private static final int RESULT_TIMER_IS_RUNNING = -5;
    private static final int RESULT_NO_TIMER = -6;
    private static final int RESULT_TIMER_ALREADY_IN_STATE = -7;

    private static final int AI_TIMER_ID = 11;

    // bundle keys used by Breeno / the OPPO clock
    private static final String KEY_RESULT = "result";
    private static final String KEY_HOUR = "alarm_hour";
    private static final String KEY_MINUTE = "alarm_minute";
    private static final String KEY_ALARM_ID = "alarm_id";
    private static final String KEY_CLOSE_TYPE = "close_type";
    private static final String KEY_MORNING_SWITCH = "morning_switch_status";
    private static final String KEY_TIMER_ID = "timer_id";
    private static final String KEY_TIMER_DURATION = "duration";
    private static final String KEY_TIMER_LEFT_TIME = "left_time";
    private static final String KEY_TIMER_TIME_STAMP = "time_stamp";
    private static final String KEY_TIMER_STATUS = "timer_status";
    private static final String KEY_TIMER_DESCRIPTION = "description";
    private static final String KEY_OVERRIDE_CURRENT_TIMER = "override_current_timer";
    private static final String KEY_START_TIMER_UI = "start_timer_ui";

    private Context mAppContext;

    @Override
    public boolean onCreate() {
        mAppContext = getContext().getApplicationContext();
        return true;
    }

    @Override
    public android.database.Cursor query(Uri uri, String[] projection, String selection,
                                         String[] selectionArgs, String sortOrder) {
        return null;
    }

    @Override
    public Uri insert(Uri uri, ContentValues values) {
        return null;
    }

    @Override
    public int update(Uri uri, ContentValues values, String selection, String[] selectionArgs) {
        return 0;
    }

    @Override
    public int delete(Uri uri, String selection, String[] selectionArgs) {
        return 0;
    }

    @Override
    public String getType(Uri uri) {
        return null;
    }

    @Override
    public Bundle call(String method, String arg, Bundle extras) {
        if (!TOKEN.equals(arg)) {
            final Bundle out = new Bundle();
            out.putInt(KEY_RESULT, RESULT_ERROR);
            return out;
        }
        return handle(mAppContext, method, extras);
    }

    /**
     * Static entry point shared by the ContentProvider and the broadcast receiver,
     * so the LSPosed module can invoke the same logic over either transport.
     */
    public static Bundle handle(Context appContext, String method, Bundle extras) {
        final BreenoProxyProvider p = new BreenoProxyProvider();
        p.mAppContext = appContext.getApplicationContext();
        return p.doCall(method, extras);
    }

    private Bundle doCall(String method, Bundle extras) {
        final Bundle out = new Bundle();
        if (method == null) {
            out.putInt(KEY_RESULT, RESULT_ERROR);
            return out;
        }
        try {
            switch (method) {
                case "get_morning_switch" -> {
                    out.putInt(KEY_RESULT, RESULT_SUCCESS);
                    out.putBoolean(KEY_MORNING_SWITCH, false);
                }
                case "get_alarm_list" -> getAlarmList(out);
                case "add_alarm" -> addAlarm(extras, out);
                case "close_alarm" -> closeAlarm(extras, out);
                case "close_all_alarms" -> closeAllAlarms(out);
                case "delete_alarm" -> deleteAlarm(extras, out);
                case "del_all_alarms" -> deleteAllAlarms(out);
                case "enable_alarm" -> enableAlarm(extras, out);
                case "snooze_alarm" -> snoozeAlarm(extras, out);
                case "stop_alarm" -> stopAlarm(out);
                case "start_timer" -> startTimer(extras, out);
                case "pause_timer" -> pauseTimer(out);
                case "resume_timer" -> resumeTimer(out);
                case "cancel_timer" -> cancelTimer(out);
                case "check_timer" -> checkTimer(out);
                default -> out.putInt(KEY_RESULT, RESULT_ERROR);
            }
        } catch (Exception e) {
            android.util.Log.e("BreenoProxyProvider", "doCall(" + method + ") failed", e);
            out.putInt(KEY_RESULT, RESULT_ERROR);
        }
        return out;
    }

    // ------------------------------------------------------------------
    // Alarm operations
    // ------------------------------------------------------------------

    private void getAlarmList(Bundle out) {
        runOnMain(() -> {
            final List<Alarm> alarms = Alarm.getAlarms(mAppContext.getContentResolver(), null, (String[]) null);
            putAlarmsIntoResult(out, alarms);
            out.putInt(KEY_RESULT, RESULT_SUCCESS);
        });
    }

    private void addAlarm(Bundle args, Bundle out) {
        runOnMain(() -> {
            if (args == null) {
                out.putInt(KEY_RESULT, RESULT_ERROR);
                return;
            }
            final int hour = args.getInt("android.intent.extra.alarm.HOUR", -1);
            final int minutes = args.getInt("android.intent.extra.alarm.MINUTES", 0);
            if (hour < 0 || hour > 23 || minutes < 0 || minutes > 59) {
                out.putInt(KEY_RESULT, RESULT_ERROR);
                return;
            }
            String label = args.getString("label");
            if (label == null) {
                label = args.getString("android.intent.extra.alarm.MESSAGE");
            }
            if (label == null) {
                label = "";
            }
            Weekdays days = Weekdays.NONE;
            ArrayList<Integer> dayList = args.getIntegerArrayList("android.intent.extra.alarm.DAYS");
            if (dayList != null && !dayList.isEmpty()) {
                final int[] cal = new int[dayList.size()];
                for (int i = 0; i < cal.length; i++) {
                    cal[i] = dayList.get(i);
                }
                days = Weekdays.fromCalendarDays(cal);
            }
            final String ringtone = args.getString("android.intent.extra.alarm.RINGTONE");

            final Alarm alarm = new Alarm();
            alarm.label = label;
            alarm.hour = hour;
            alarm.minutes = minutes;
            alarm.daysOfWeek = days;
            if (ringtone == null || ringtone.isEmpty()) {
                alarm.alert = DataModel.getDataModel().getDefaultAlarmRingtoneUriFromSettings();
            } else {
                alarm.alert = Uri.parse(ringtone);
            }
            applyDefaultSettings(alarm);

            final ContentResolver cr = mAppContext.getContentResolver();
            alarm.addAlarm(cr);

            final Calendar now = Calendar.getInstance();
            final AlarmInstance instance = alarm.createInstanceAfter(now);
            instance.addInstance(cr);
            AlarmStateManager.registerInstance(mAppContext, instance, true);

            Events.sendAlarmEvent(R.string.action_create, R.string.label_intent);

            final List<Alarm> alarms = Alarm.getAlarms(cr, null, (String[]) null);
            putAlarmsIntoResult(out, alarms);
            out.putInt(KEY_RESULT, RESULT_SUCCESS);
        });
    }

    private void closeAlarm(Bundle args, Bundle out) {
        runOnMain(() -> {
            if (args == null) {
                out.putInt(KEY_RESULT, RESULT_ERROR);
                return;
            }
            final long alarmId = args.getLong(KEY_ALARM_ID, -1L);
            final List<Alarm> targets = new ArrayList<>();
            if (alarmId >= 0) {
                final Alarm alarm = Alarm.getAlarm(mAppContext.getContentResolver(), alarmId);
                if (alarm != null) {
                    targets.add(alarm);
                }
            } else {
                final int hour = args.getInt(KEY_HOUR, -1);
                final int minute = args.getInt(KEY_MINUTE, -1);
                if (hour >= 0 && minute >= 0) {
                    targets.addAll(findByTime(hour, minute));
                }
            }

            if (targets.isEmpty()) {
                out.putInt(KEY_RESULT, RESULT_NO_ALARM_FOUND);
                return;
            }
            if (targets.size() > 1) {
                out.putInt(KEY_RESULT, RESULT_FOUND_MUL_ALARMS);
                putAlarmsIntoResult(out, targets);
                return;
            }

            final Alarm alarm = targets.get(0);
            alarm.enabled = false;
            alarm.updateAlarm(mAppContext.getContentResolver());
            AlarmStateManager.deleteAllInstances(mAppContext, alarm.id);
            Events.sendAlarmEvent(R.string.action_disable, R.string.label_intent);

            out.putInt(KEY_RESULT, RESULT_SUCCESS);
        });
    }

    private void closeAllAlarms(Bundle out) {
        runOnMain(() -> {
            final ContentResolver cr = mAppContext.getContentResolver();
            final List<Alarm> alarms = Alarm.getAlarms(cr, null, (String[]) null);
            if (alarms.isEmpty()) {
                out.putInt(KEY_RESULT, RESULT_NO_ALARM_FOUND);
                return;
            }
            for (Alarm alarm : alarms) {
                alarm.enabled = false;
                alarm.updateAlarm(cr);
                AlarmStateManager.deleteAllInstances(mAppContext, alarm.id);
            }
            out.putInt(KEY_RESULT, RESULT_SUCCESS);
        });
    }

    private void deleteAlarm(Bundle args, Bundle out) {
        runOnMain(() -> {
            if (args == null) {
                out.putInt(KEY_RESULT, RESULT_ERROR);
                return;
            }
            final long alarmId = args.getLong(KEY_ALARM_ID, -1L);
            final List<Alarm> targets = new ArrayList<>();
            if (alarmId >= 0) {
                final Alarm alarm = Alarm.getAlarm(mAppContext.getContentResolver(), alarmId);
                if (alarm != null) {
                    targets.add(alarm);
                }
            } else {
                final int hour = args.getInt(KEY_HOUR, -1);
                final int minute = args.getInt(KEY_MINUTE, -1);
                if (hour >= 0 && minute >= 0) {
                    targets.addAll(findByTime(hour, minute));
                }
            }

            if (targets.isEmpty()) {
                out.putInt(KEY_RESULT, RESULT_NO_ALARM_FOUND);
                return;
            }
            if (targets.size() > 1) {
                out.putInt(KEY_RESULT, RESULT_FOUND_MUL_ALARMS);
                putAlarmsIntoResult(out, targets);
                return;
            }

            final Alarm alarm = targets.get(0);
            AlarmStateManager.deleteAllInstances(mAppContext, alarm.id);
            Alarm.deleteAlarm(mAppContext.getContentResolver(), alarm.id);
            Events.sendAlarmEvent(R.string.action_delete, R.string.label_intent);

            out.putInt(KEY_RESULT, RESULT_SUCCESS);
        });
    }

    private void deleteAllAlarms(Bundle out) {
        runOnMain(() -> {
            final ContentResolver cr = mAppContext.getContentResolver();
            final List<Alarm> alarms = Alarm.getAlarms(cr, null, (String[]) null);
            if (alarms.isEmpty()) {
                out.putInt(KEY_RESULT, RESULT_NO_ALARM_FOUND);
                return;
            }
            for (Alarm alarm : alarms) {
                AlarmStateManager.deleteAllInstances(mAppContext, alarm.id);
                Alarm.deleteAlarm(cr, alarm.id);
            }
            out.putInt(KEY_RESULT, RESULT_SUCCESS);
        });
    }

    private void enableAlarm(Bundle args, Bundle out) {
        runOnMain(() -> {
            if (args == null) {
                out.putInt(KEY_RESULT, RESULT_ERROR);
                return;
            }
            final long alarmId = args.getLong(KEY_ALARM_ID, -1L);
            final Alarm alarm = Alarm.getAlarm(mAppContext.getContentResolver(), alarmId);
            if (alarm == null) {
                out.putInt(KEY_RESULT, RESULT_NO_ALARM_FOUND);
                return;
            }
            alarm.enabled = true;
            alarm.updateAlarm(mAppContext.getContentResolver());
            AlarmStateManager.deleteAllInstances(mAppContext, alarm.id);
            final Calendar now = Calendar.getInstance();
            final AlarmInstance instance = alarm.createInstanceAfter(now);
            instance.addInstance(mAppContext.getContentResolver());
            AlarmStateManager.registerInstance(mAppContext, instance, true);
            out.putInt(KEY_RESULT, RESULT_SUCCESS);
        });
    }

    private void snoozeAlarm(Bundle args, Bundle out) {
        runOnMain(() -> {
            final long alarmId = args == null ? -1L : args.getLong(KEY_ALARM_ID, -1L);
            final AlarmInstance firing = AlarmInstance.getNextUpcomingInstanceByAlarmId(
                mAppContext.getContentResolver(), alarmId);
            if (firing != null && (firing.mAlarmState == AlarmInstance.FIRED_STATE
                || firing.mAlarmState == AlarmInstance.SNOOZE_STATE)) {
                AlarmStateManager.setSnoozeState(mAppContext, firing, true);
                out.putInt(KEY_RESULT, RESULT_SUCCESS);
            } else {
                out.putInt(KEY_RESULT, RESULT_NO_ALARM_FOUND);
            }
        });
    }

    private void stopAlarm(Bundle out) {
        runOnMain(() -> {
            final List<AlarmInstance> firing = AlarmInstance.getInstancesByState(
                mAppContext.getContentResolver(), AlarmInstance.FIRED_STATE);
            if (firing.isEmpty()) {
                out.putInt(KEY_RESULT, RESULT_NO_ALARM_FOUND);
                return;
            }
            for (AlarmInstance instance : firing) {
                AlarmStateManager.deleteInstanceAndUpdateParent(mAppContext, instance, true);
            }
            out.putInt(KEY_RESULT, RESULT_SUCCESS);
        });
    }

    // ------------------------------------------------------------------
    // Timer operations
    // ------------------------------------------------------------------

    private void startTimer(Bundle args, Bundle out) {
        runOnMain(() -> {
            if (args == null) {
                out.putInt(KEY_RESULT, RESULT_ERROR);
                return;
            }
            final long lengthSeconds = args.getInt("android.intent.extra.alarm.LENGTH", 0);
            if (lengthSeconds <= 0) {
                out.putInt(KEY_RESULT, RESULT_ERROR);
                return;
            }
            final long lengthMillis = lengthSeconds * 1000L;
            if (lengthMillis < Timer.MIN_LENGTH) {
                out.putInt(KEY_RESULT, RESULT_ERROR);
                return;
            }

            final DataModel model = DataModel.getDataModel();
            final SharedPreferences prefs = getDefaultSharedPreferences(mAppContext);
            final String defaultTimeToAddToTimer = String.valueOf(SettingsDAO.getDefaultTimeToAddToTimer(prefs));
            final String vibrationPattern = SettingsDAO.getTimerVibrationPattern(prefs);
            final Uri ringtoneUri = model.getTimerRingtoneUri();
            final int autoSilenceDuration = SettingsDAO.getTimerAutoSilenceDuration(prefs);
            final int volumeCrescendoDuration = SettingsDAO.getTimerVolumeCrescendoDuration(prefs);
            final boolean isVibrate = SettingsDAO.isTimerVibrate(prefs);
            final boolean isFlashOn = SettingsDAO.shouldTurnOnBackFlashForExpiredTimer(prefs);

            Timer timer = null;
            for (Timer t : model.getTimers()) {
                if (t.isReset() && t.getLength() == lengthMillis) {
                    timer = t;
                    break;
                }
            }
            if (timer == null) {
                timer = model.addTimer(lengthMillis, "", defaultTimeToAddToTimer, ringtoneUri,
                    autoSilenceDuration, volumeCrescendoDuration, isVibrate, vibrationPattern,
                    isFlashOn, false, false);
            }
            model.startTimer(timer);

            out.putInt(KEY_TIMER_ID, AI_TIMER_ID);
            out.putInt(KEY_RESULT, RESULT_SUCCESS);
        });
    }

    private void pauseTimer(Bundle out) {
        runOnMain(() -> {
            final Timer timer = getActiveTimer();
            if (timer == null) {
                out.putInt(KEY_RESULT, RESULT_NO_TIMER);
                return;
            }
            if (timer.isPaused()) {
                out.putInt(KEY_RESULT, RESULT_TIMER_ALREADY_IN_STATE);
                return;
            }
            DataModel.getDataModel().pauseTimer(timer);
            out.putInt(KEY_RESULT, RESULT_SUCCESS);
        });
    }

    private void resumeTimer(Bundle out) {
        runOnMain(() -> {
            final Timer timer = getActiveTimer();
            if (timer == null) {
                out.putInt(KEY_RESULT, RESULT_NO_TIMER);
                return;
            }
            if (!timer.isPaused()) {
                out.putInt(KEY_RESULT, RESULT_TIMER_ALREADY_IN_STATE);
                return;
            }
            DataModel.getDataModel().startTimer(timer);
            out.putInt(KEY_RESULT, RESULT_SUCCESS);
        });
    }

    private void cancelTimer(Bundle out) {
        runOnMain(() -> {
            final Timer timer = getActiveTimer();
            if (timer == null) {
                out.putInt(KEY_RESULT, RESULT_NO_TIMER);
                return;
            }
            DataModel.getDataModel().resetTimer(timer, R.string.label_intent);
            out.putInt(KEY_RESULT, RESULT_SUCCESS);
        });
    }

    private void checkTimer(Bundle out) {
        runOnMain(() -> {
            final Timer timer = getActiveTimer();
            if (timer == null) {
                out.putInt(KEY_RESULT, RESULT_SUCCESS);
                return;
            }
            out.putInt(KEY_TIMER_ID, AI_TIMER_ID);
            out.putLong(KEY_TIMER_DURATION, timer.getLength());
            out.putLong(KEY_TIMER_LEFT_TIME, timer.getRemainingTime() / 1000L);
            out.putLong(KEY_TIMER_TIME_STAMP, timer.getRemainingTime());
            out.putInt(KEY_TIMER_STATUS, timer.isPaused() ? 1 : 0);
            out.putString(KEY_TIMER_DESCRIPTION, timer.getLabel());
            out.putInt(KEY_RESULT, RESULT_TIMER_IS_RUNNING);
        });
    }

    private Timer getActiveTimer() {
        final List<Timer> timers = DataModel.getDataModel().getTimers();
        for (int i = timers.size() - 1; i >= 0; i--) {
            final Timer timer = timers.get(i);
            if (timer.isRunning() || timer.isPaused()) {
                return timer;
            }
        }
        return null;
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    private List<Alarm> findByTime(int hour, int minute) {
        final List<Alarm> result = new ArrayList<>();
        final ContentResolver cr = mAppContext.getContentResolver();
        for (Alarm alarm : Alarm.getAlarms(cr, null, (String[]) null)) {
            if (alarm.hour == hour && alarm.minutes == minute) {
                result.add(alarm);
            }
        }
        return result;
    }

    private void applyDefaultSettings(Alarm alarm) {
        final SharedPreferences prefs = getDefaultSharedPreferences(mAppContext);
        final AudioManager audioManager = mAppContext.getSystemService(AudioManager.class);

        alarm.enabled = true;
        alarm.vibrate = SettingsDAO.areAlarmVibrationsEnabledByDefault(prefs);
        alarm.vibrationPattern = SettingsDAO.getVibrationPattern(prefs);
        alarm.flash = SettingsDAO.shouldTurnOnBackFlashForTriggeredAlarm(prefs);
        alarm.deleteAfterUse = SettingsDAO.isOccasionalAlarmDeletedByDefault(prefs);
        alarm.autoSilenceDuration = SettingsDAO.getAlarmTimeout(prefs);
        alarm.snoozeDuration = SettingsDAO.getSnoozeLength(prefs);
        alarm.missedAlarmRepeatLimit = SettingsDAO.getMissedAlarmRepeatLimit(prefs);
        alarm.crescendoDuration = SettingsDAO.getAlarmVolumeCrescendoDuration(prefs);
        alarm.alarmVolume = audioManager.getStreamVolume(AudioManager.STREAM_ALARM);
        alarm.backgroundImage = DEFAULT_SPECIFIC_ALARM_BACKGROUND_IMAGE;
        alarm.blurIntensity = SettingsDAO.getAlarmBlurIntensity(prefs);
    }

    /**
     * Fills the OPPO-format result bundle from a list of this app's alarms.
     */
    private static void putAlarmsIntoResult(Bundle out, List<Alarm> alarms) {
        final int size = alarms.size();
        final long[] ids = new long[size];
        final int[] hours = new int[size];
        final int[] minutes = new int[size];
        final String[] labels = new String[size];
        final boolean[] states = new boolean[size];
        final String[] repeats = new String[size];
        final int[] repeatSets = new int[size];
        final long[] times = new long[size];
        final String[] uuids = new String[size];

        for (int i = 0; i < size; i++) {
            final Alarm alarm = alarms.get(i);
            ids[i] = alarm.id;
            hours[i] = alarm.hour;
            minutes[i] = alarm.minutes;
            labels[i] = alarm.label == null ? "" : alarm.label;
            states[i] = alarm.enabled;
            repeatSets[i] = alarm.daysOfWeek.getBits();
            repeats[i] = repeatText(alarm.daysOfWeek);
            times[i] = nextTriggerTime(alarm);
            uuids[i] = java.util.UUID.nameUUIDFromBytes(("breeno:" + alarm.id).getBytes()).toString();
        }

        out.putLongArray("alarm_id_list", ids);
        out.putIntArray("alarm_hour_list", hours);
        out.putIntArray("alarm_min_list", minutes);
        out.putStringArray("alarm_label_list", labels);
        out.putBooleanArray("alarm_state_list", states);
        // Breeno's parser reads "alarm_status" (FeatureOption.r() branch) for the enabled state,
        // not only "alarm_state_list"; without it enabled alarms are filtered out during close.
        out.putBooleanArray("alarm_status", states);
        out.putStringArray("alarm_repeat_list", repeats);
        out.putIntArray("alarm_repeat_set_list", repeatSets);
        out.putLongArray("alarm_time_list", times);
        out.putStringArray("alarm_uuid_list", uuids);

        // Fill every OPPO array with per-alarm values so Breeno sees a complete native alarm list.
        final int[] zeroInts = new int[size];
        final long[] zeroLongs = new long[size];
        final String[] hashStrings = new String[size];
        for (int i = 0; i < size; i++) {
            hashStrings[i] = "#";
        }
        out.putIntArray("alarm_enableAssociate_list", zeroInts);
        out.putIntArray("workday_switch_list", zeroInts);
        out.putIntArray("holiday_switch_list", zeroInts);
        out.putIntArray("alarm_snoonze_items_list", zeroInts);
        out.putIntArray("alarm_snoonze_time_list", zeroInts);
        out.putIntArray("alarm_snooze_time_list", zeroInts);
        out.putIntArray("alarm_workday_type_list", zeroInts);
        out.putLongArray("alarm_workday_update_time_list", zeroLongs);
        out.putStringArray("alarm_special_alarm_days_list", hashStrings);
        out.putIntArray("alarm_default_alarm_list", zeroInts);
        out.putIntArray("alarm_ring_num_list", zeroInts);
        out.putIntArray("alarm_update_type", zeroInts);
        out.putIntArray("alarm_loop_switch_list", zeroInts);
        out.putIntArray("alarm_loop_cycle_days_list", zeroInts);
        out.putIntArray("alarm_loop_id_list", zeroInts);
        out.putIntArray("alarm_loop_work_days_list", zeroInts);
        out.putIntArray("alarm_loop_alarm_number_list", zeroInts);
        out.putIntArray("alarm_loop_day_list", zeroInts);
        out.putStringArray("alarm_loop_reset_days_list", hashStrings);
    }

    private static String repeatText(Weekdays days) {
        final int bits = days.getBits();
        if (bits == 0) {
            return "仅一次";
        }
        if (bits == 0x7F) {
            return "每天";
        }
        if (bits == 0x1F) { // Mon..Fri
            return "工作日";
        }
        final StringBuilder sb = new StringBuilder();
        appendDay(sb, days, java.util.Calendar.MONDAY, "周一");
        appendDay(sb, days, java.util.Calendar.TUESDAY, "周二");
        appendDay(sb, days, java.util.Calendar.WEDNESDAY, "周三");
        appendDay(sb, days, java.util.Calendar.THURSDAY, "周四");
        appendDay(sb, days, java.util.Calendar.FRIDAY, "周五");
        appendDay(sb, days, java.util.Calendar.SATURDAY, "周六");
        appendDay(sb, days, java.util.Calendar.SUNDAY, "周日");
        return sb.toString();
    }

    private static void appendDay(StringBuilder sb, Weekdays days, int calendarDay, String text) {
        if (days.isBitOn(calendarDay)) {
            if (sb.length() > 0) {
                sb.append(',');
            }
            sb.append(text);
        }
    }

    private static long nextTriggerTime(Alarm alarm) {
        final AlarmInstance instance = alarm.createInstanceAfter(Calendar.getInstance());
        return instance.getAlarmTime().getTimeInMillis();
    }

    private void runOnMain(Runnable runnable) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            try {
                runnable.run();
            } catch (Throwable t) {
                android.util.Log.e("BreenoProxyProvider", "runOnMain failed", t);
            }
            return;
        }
        final CountDownLatch latch = new CountDownLatch(1);
        new Handler(Looper.getMainLooper()).post(() -> {
            try {
                runnable.run();
            } catch (Throwable t) {
                android.util.Log.e("BreenoProxyProvider", "runOnMain failed", t);
            } finally {
                latch.countDown();
            }
        });
        try {
            latch.await(10, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
