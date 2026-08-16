/*
 * Copyright (C) 2015 The Android Open Source Project
 * modified
 * SPDX-License-Identifier: Apache-2.0 AND GPL-3.0-only
 */

package com.best.deskclock;

import static com.best.deskclock.settings.PreferencesDefaultValues.DARK_THEME;
import static com.best.deskclock.settings.PreferencesDefaultValues.DEBUG_LANGUAGE_CODE;
import static com.best.deskclock.settings.PreferencesDefaultValues.LIGHT_THEME;
import static com.best.deskclock.settings.PreferencesDefaultValues.PURPLE_ACCENT_COLOR;
import static com.best.deskclock.settings.PreferencesDefaultValues.RED_ACCENT_COLOR;
import static com.best.deskclock.settings.PreferencesDefaultValues.SYSTEM_THEME;
import static com.best.deskclock.settings.PreferencesKeys.KEY_ACCENT_COLOR;
import static com.best.deskclock.settings.PreferencesKeys.KEY_LANGUAGE_CODE;

import android.app.Activity;
import android.app.Application;
import android.content.Context;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Bundle;
import android.util.Xml;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatDelegate;
import androidx.core.os.LocaleListCompat;
import androidx.preference.PreferenceManager;

import com.best.deskclock.controller.Controller;
import com.best.deskclock.data.DataModel;
import com.best.deskclock.data.SettingsDAO;
import com.best.deskclock.events.LogEventTracker;
import com.best.deskclock.holiday.HolidayDataStore;
import com.best.deskclock.sync.SyncEngine;
import com.best.deskclock.sync.SyncSettings;
import com.best.deskclock.uidata.UiDataModel;
import com.best.deskclock.utils.LogUtils;
import com.best.deskclock.utils.NotificationUtils;
import com.best.deskclock.utils.SdkUtils;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.util.HashSet;
import java.util.Objects;
import java.util.Set;

import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserException;

public class DeskClockApplication extends Application implements Application.ActivityLifecycleCallbacks {

    private static DeskClockApplication sInstance;

    private int mStartedActivities = 0;
    private boolean mIsChangingConfiguration = false;
    private SyncEngine mSyncEngine;

    @Override
    public void onCreate() {
        super.onCreate();

        sInstance = this;

        importDeviceDefaults();

        initDebugAndNightlyDefaults();

        String theme = SettingsDAO.getTheme(getDefaultSharedPreferences(this));
        applySystemNightMode(theme);

        DataModel.getDataModel().init();
        UiDataModel.getUiDataModel().init();
        Controller.getController().init();
        Controller.getController().addEventTracker(new LogEventTracker());
        Controller.getController().updateShortcuts();

        // Warm the holiday cache so "workday" alarms can schedule even before the first lookup.
        HolidayDataStore.getInstance().refresh();

        if (SdkUtils.isAtLeastAndroid8()) {
            NotificationUtils.updateNotificationChannels(this);
        }

        if (SyncSettings.isSyncEnabled(this)) {
            mSyncEngine = new SyncEngine(this);
            mSyncEngine.start();
        }

        registerActivityLifecycleCallbacks(this);
    }

    @Override
    public void onActivityStarted(@NonNull Activity activity) {
        if (mStartedActivities == 0 && !mIsChangingConfiguration) {
            DataModel.getDataModel().setApplicationInForeground(true);
        }

        mIsChangingConfiguration = false;
        mStartedActivities++;
    }

    @Override
    public void onActivityStopped(@NonNull Activity activity) {
        mStartedActivities--;

        if (mStartedActivities == 0) {
            if (!activity.isChangingConfigurations()) {
                DataModel.getDataModel().setApplicationInForeground(false);
            } else {
                mIsChangingConfiguration = true;
            }
        }
    }

    @Override public void onActivityCreated(@NonNull Activity activity, Bundle savedInstanceState) {}
    @Override public void onActivityResumed(@NonNull Activity activity) {}
    @Override public void onActivityPaused(@NonNull Activity activity) {}
    @Override public void onActivitySaveInstanceState(@NonNull Activity activity, @NonNull Bundle outState) {}
    @Override public void onActivityDestroyed(@NonNull Activity activity) {}

    /**
     * On a fresh install, seeds the bundled default preferences (settings, timers, tabs) that were
     * exported from a reference device. The file only takes effect when no preferences exist yet,
     * so upgrades never overwrite user data.
     */
    private void importDeviceDefaults() {
        final SharedPreferences prefs = getDefaultSharedPreferences(this);
        if (prefs.getAll().isEmpty()) {
            importPreferencesFromAssets();
        }
    }

    /**
     * Parses the bundled {@code default_preferences.xml} asset and writes every entry into the
     * default {@link SharedPreferences}. The asset uses the exact format produced by
     * {@code SharedPreferences}: {@code <map>} with {@code <string>}, {@code <boolean>},
     * {@code <int>}, {@code <long>} and {@code <set>} children.
     */
    private void importPreferencesFromAssets() {
        try (InputStream inputStream = getAssets().open("default_preferences.xml")) {
            final SharedPreferences.Editor editor = getDefaultSharedPreferences(this).edit();

            final XmlPullParser parser = Xml.newPullParser();
            parser.setInput(inputStream, null);

            Set<String> currentSet = null;
            String currentSetKey = null;

            int eventType = parser.getEventType();
            while (eventType != XmlPullParser.END_DOCUMENT) {
                if (eventType == XmlPullParser.START_TAG) {
                    final String tag = parser.getName();
                    if ("map".equals(tag)) {
                        currentSet = null;
                        currentSetKey = null;
                    } else if (currentSet != null) {
                        // String elements inside a <set>
                        currentSet.add(parser.nextText());
                    } else if ("set".equals(tag)) {
                        currentSetKey = parser.getAttributeValue(null, "name");
                        currentSet = new HashSet<>();
                    } else {
                        final String name = parser.getAttributeValue(null, "name");
                        switch (tag) {
                            case "string" -> editor.putString(name, parser.nextText());
                            case "boolean" -> editor.putBoolean(name, Boolean.parseBoolean(parser.getAttributeValue(null, "value")));
                            case "int" -> editor.putInt(name, Integer.parseInt(parser.getAttributeValue(null, "value")));
                            case "long" -> editor.putLong(name, Long.parseLong(parser.getAttributeValue(null, "value")));
                        }
                    }
                } else if (eventType == XmlPullParser.END_TAG && "set".equals(parser.getName()) && currentSet != null) {
                    editor.putStringSet(currentSetKey, currentSet);
                    currentSet = null;
                    currentSetKey = null;
                }
                eventType = parser.next();
            }

            editor.apply();
        } catch (IOException | XmlPullParserException e) {
            LogUtils.e("Failed to import bundled default preferences: " + e);
        }
    }

    private void initDebugAndNightlyDefaults() {
        SharedPreferences prefs = getDefaultSharedPreferences(this);
        if (!prefs.contains(KEY_ACCENT_COLOR)) {
            if (BuildConfig.IS_DEBUG_BUILD) {
                prefs.edit().putString(KEY_ACCENT_COLOR, RED_ACCENT_COLOR).apply();
            } else if (BuildConfig.IS_NIGHTLY_BUILD) {
                prefs.edit().putString(KEY_ACCENT_COLOR, PURPLE_ACCENT_COLOR).apply();
            }
        }

        if (!prefs.contains(KEY_LANGUAGE_CODE)) {
            if (BuildConfig.IS_DEBUG_BUILD || BuildConfig.IS_NIGHTLY_BUILD) {
                prefs.edit().putString(KEY_LANGUAGE_CODE, DEBUG_LANGUAGE_CODE).apply();
                AppCompatDelegate.setApplicationLocales(LocaleListCompat.forLanguageTags(DEBUG_LANGUAGE_CODE));
            }
        }
    }

    private void applySystemNightMode(String theme) {
        switch (theme) {
            case SYSTEM_THEME -> AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM);
            case LIGHT_THEME -> AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_NO);
            case DARK_THEME -> AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_YES);
        }
    }

    public static Context getAppContext() {
        return sInstance;
    }

    /**
     * Starts or stops the LAN sync engine; used by the settings screen when the user toggles sync.
     */
    public void setSyncEnabled(boolean enabled) {
        if (enabled && mSyncEngine == null) {
            mSyncEngine = new SyncEngine(this);
            mSyncEngine.start();
        } else if (!enabled && mSyncEngine != null) {
            mSyncEngine.stop();
            mSyncEngine = null;
        }
    }

    public SyncEngine getSyncEngine() {
        return mSyncEngine;
    }

    /**
     * Returns the default {@link SharedPreferences} instance from the underlying storage context.
     */
    public static SharedPreferences getDefaultSharedPreferences(Context context) {
        final Context appContext = context.getApplicationContext();
        final Context storageContext;

        if (SdkUtils.isAtLeastAndroid7()) {
            // All N devices have split storage areas. Migrate the existing preferences into the new
            // device encrypted storage area if that has not yet occurred.
            storageContext = appContext.createDeviceProtectedStorageContext();
            final String name = appContext.getPackageName() + "_preferences";
            final String prefsFilename = storageContext.getDataDir() + "/shared_prefs/" + name + ".xml";
            final File prefs = new File(Objects.requireNonNull(Uri.parse(prefsFilename).getPath()));

            if (!prefs.exists()) {
                if (!storageContext.moveSharedPreferencesFrom(appContext, name)) {
                    LogUtils.wtf("Failed to migrate shared preferences");
                }
            }
        } else {
            storageContext = appContext;
        }

        return PreferenceManager.getDefaultSharedPreferences(storageContext);
    }

}
