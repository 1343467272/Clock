/*
 * SPDX-License-Identifier: GPL-3.0-only
 */

package com.best.deskclock.holiday;

import android.content.Context;
import android.content.SharedPreferences;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.best.deskclock.DeskClockApplication;
import com.best.deskclock.utils.LogUtils;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Calendar;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Provides the Chinese statutory workday/rest-day calendar used by "workday" alarms.
 *
 * <p>Data is sourced from the free {@code timor.tech} holiday API
 * ({@code https://timor.tech/api/holiday/year/<year>}), which lists the official rest days
 * ({@code holiday == true}) and the make-up workdays ({@code holiday == false}). Any date not
 * listed is a regular day: a workday on weekdays, a rest day on weekends.</p>
 *
 * <p>Responses are cached per year in {@link SharedPreferences} so that a missing network
 * connection does not break alarm scheduling. When no data is available yet, the weekday rule is
 * used as a fallback and a background refresh is triggered.</p>
 */
public final class HolidayDataStore {

    private static final String TAG = "HolidayDataStore";
    private static final String API_BASE = "https://timor.tech/api/holiday/year/";
    private static final String PREFS_NAME = "holiday_cache";
    private static final String PREF_KEY_PREFIX = "year_";

    private static final HolidayDataStore INSTANCE = new HolidayDataStore();

    private final ExecutorService mNetworkExecutor = Executors.newCachedThreadPool();
    private final Map<Integer, HolidayYear> mCache = new HashMap<>();
    private final Set<Integer> mFetchingYears = new HashSet<>();

    private HolidayDataStore() {
    }

    public static HolidayDataStore getInstance() {
        return INSTANCE;
    }

    /**
     * @param date the date to test
     * @return {@code true} if {@code date} is a Chinese statutory workday.
     */
    public boolean isWorkday(@NonNull Calendar date) {
        final int year = date.get(Calendar.YEAR);

        if (!ensureLoaded(year)) {
            // No cached data yet: fall back to the plain weekday rule and fetch in the background.
            final int dayOfWeek = date.get(Calendar.DAY_OF_WEEK);
            return dayOfWeek != Calendar.SATURDAY && dayOfWeek != Calendar.SUNDAY;
        }

        final String monthDay = key(date);
        final Holiday holiday = mCache.get(year).get(monthDay);
        if (holiday == null) {
            // Not a listed date: regular day.
            final int dayOfWeek = date.get(Calendar.DAY_OF_WEEK);
            return dayOfWeek != Calendar.SATURDAY && dayOfWeek != Calendar.SUNDAY;
        }

        return !holiday.isRestDay;
    }

    /**
     * Fetches the holiday calendar for the current and next year in the background.
     */
    public void refresh() {
        final int currentYear = Calendar.getInstance().get(Calendar.YEAR);
        fetchYear(currentYear);
        fetchYear(currentYear + 1);
    }

    private boolean ensureLoaded(int year) {
        synchronized (mCache) {
            if (mCache.containsKey(year)) {
                return true;
            }
            if (parseInto(year, loadFromPrefs(year))) {
                return true;
            }
        }

        refresh();
        return false;
    }

    private void fetchYear(final int year) {
        synchronized (mCache) {
            if (mCache.containsKey(year) || mFetchingYears.contains(year)) {
                return;
            }
            mFetchingYears.add(year);
        }

        mNetworkExecutor.execute(() -> {
            final String raw = fetch(API_BASE + year);
            synchronized (mCache) {
                mFetchingYears.remove(year);
                if (raw == null || raw.isEmpty()) {
                    return;
                }

                if (parseInto(year, raw)) {
                    getPreferences().edit().putString(PREF_KEY_PREFIX + year, raw).apply();
                }
            }
        });
    }

    private boolean parseInto(int year, @Nullable String raw) {
        if (raw == null) {
            return false;
        }

        try {
            final JSONObject body = new JSONObject(raw);
            final JSONObject holidays = body.optJSONObject("holiday");
            if (holidays == null) {
                return false;
            }

            final HolidayYear holidayYear = new HolidayYear();
            final Iterator<String> keys = holidays.keys();
            while (keys.hasNext()) {
                final String monthDay = keys.next();
                final JSONObject entry = holidays.optJSONObject(monthDay);
                if (entry == null) {
                    continue;
                }

                final boolean restDay = entry.optBoolean("holiday", false);
                final boolean after = entry.optBoolean("after", false);
                final String name = entry.optString("name", "");
                holidayYear.put(monthDay, new Holiday(name, restDay, after));
            }

            mCache.put(year, holidayYear);
            return true;
        } catch (JSONException e) {
            LogUtils.e(TAG + " - Failed to parse holiday data for year " + year, e);
            return false;
        }
    }

    private SharedPreferences getPreferences() {
        final Context context = DeskClockApplication.getAppContext();
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
    }

    @Nullable
    private String loadFromPrefs(int year) {
        return getPreferences().getString(PREF_KEY_PREFIX + year, null);
    }

    @Nullable
    private String fetch(String urlString) {
        HttpURLConnection connection = null;
        try {
            connection = (HttpURLConnection) new URL(urlString).openConnection();
            connection.setRequestMethod("GET");
            connection.setConnectTimeout(10_000);
            connection.setReadTimeout(10_000);
            connection.connect();

            final int code = connection.getResponseCode();
            if (code != HttpURLConnection.HTTP_OK) {
                LogUtils.w(TAG, "Holiday API returned HTTP " + code);
                return null;
            }

            try (InputStream input = connection.getInputStream();
                 BufferedReader reader = new BufferedReader(new InputStreamReader(input, StandardCharsets.UTF_8))) {
                final StringBuilder builder = new StringBuilder();
                String line;
                while ((line = reader.readLine()) != null) {
                    builder.append(line);
                }
                return builder.toString();
            }
        } catch (IOException e) {
            LogUtils.e(TAG + " - Failed to fetch holiday data", e);
            return null;
        } finally {
            if (connection != null) {
                connection.disconnect();
            }
        }
    }

    private static String key(Calendar date) {
        return String.format("%02d-%02d", date.get(Calendar.MONTH) + 1, date.get(Calendar.DAY_OF_MONTH));
    }

    private static final class Holiday {
        final String name;
        final boolean isRestDay;
        final boolean isMakeUpWorkday;

        Holiday(String name, boolean restDay, boolean after) {
            this.name = name;
            this.isRestDay = restDay;
            this.isMakeUpWorkday = !restDay && after;
        }
    }

    private static final class HolidayYear {
        private final Map<String, Holiday> mEntries = new HashMap<>();

        void put(String monthDay, Holiday holiday) {
            mEntries.put(monthDay, holiday);
        }

        @Nullable
        Holiday get(String monthDay) {
            return mEntries.get(monthDay);
        }
    }
}
