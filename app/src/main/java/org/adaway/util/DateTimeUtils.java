package org.adaway.util;

import android.content.Context;
import android.text.format.DateFormat;

import java.text.SimpleDateFormat;
import java.time.ZonedDateTime;
import java.util.Date;
import java.util.Locale;

/**
 * This class provides date and time formatting helpers respecting the device locale and the
 * device 12/24-hour time format setting.
 *
 * @author Bruce BUJON (bruce.bujon(at)gmail(dot)com)
 */
public final class DateTimeUtils {
    /**
     * Private constructor of utility class.
     */
    private DateTimeUtils() {
        // Prevent instantiation
    }

    /**
     * Format a date and time using the device locale and the device 12/24-hour format setting.
     *
     * @param context  The context used to read the time format setting.
     * @param dateTime The date and time to format.
     * @return The formatted date and time.
     */
    public static String formatDateTime(Context context, ZonedDateTime dateTime) {
        Locale locale = Locale.getDefault();
        // Use the device 12/24-hour setting to select the appropriate hour field in the pattern
        boolean is24Hour = DateFormat.is24HourFormat(context);
        String skeleton = is24Hour ? "yMMMdHm" : "yMMMdhm";
        String pattern = DateFormat.getBestDateTimePattern(locale, skeleton);
        return new SimpleDateFormat(pattern, locale).format(Date.from(dateTime.toInstant()));
    }
}
