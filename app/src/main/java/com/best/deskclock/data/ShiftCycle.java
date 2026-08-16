/*
 * SPDX-License-Identifier: GPL-3.0-only
 */

package com.best.deskclock.data;

import androidx.annotation.NonNull;

import com.best.deskclock.provider.Alarm;

import java.util.Calendar;
import java.util.TimeZone;

/**
 * Computes the work/rest phase of a "work X days, rest Y days" shift cycle.
 */
public final class ShiftCycle {

    /**
     * @return {@code true} if {@code date} falls on a work day of this alarm's shift cycle.
     */
    public static boolean isWorkday(@NonNull Alarm alarm, @NonNull Calendar date) {
        return isWorkday(alarm.shiftWorkDays, alarm.shiftRestDays, alarm.shiftStartDate, date);
    }

    /**
     * @param workDays   number of consecutive work days in the cycle ("上 X 天")
     * @param restDays   number of consecutive rest days in the cycle ("休 Y 天")
     * @param startDate  UTC midnight of the first day of the first work block
     * @param date       the date to test
     * @return {@code true} if {@code date} is a work day of the cycle
     */
    public static boolean isWorkday(int workDays, int restDays, long startDate, @NonNull Calendar date) {
        if (workDays <= 0 || restDays <= 0 || startDate <= 0) {
            return false;
        }

        final long startOfCycle = utcMidnightMillis(date);

        long daysSinceStart = daysBetweenUtcMidnights(startDate, startOfCycle);
        if (daysSinceStart < 0) {
            daysSinceStart = 0;
        }

        final int phase = (int) (daysSinceStart % (workDays + restDays));
        return phase < workDays;
    }

    private static long utcMidnightMillis(Calendar date) {
        Calendar utc = Calendar.getInstance(TimeZone.getTimeZone("UTC"));
        utc.clear();
        utc.set(date.get(Calendar.YEAR), date.get(Calendar.MONTH), date.get(Calendar.DAY_OF_MONTH));
        return utc.getTimeInMillis();
    }

    private static long daysBetweenUtcMidnights(long startMillis, long endMillis) {
        return (endMillis - startMillis) / (24 * 60 * 60 * 1000L);
    }
}
