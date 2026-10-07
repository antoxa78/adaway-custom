package org.adaway.util;

import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.PowerManager;
import android.provider.Settings;

import timber.log.Timber;

/**
 * This class provides helpers about the system battery optimization of the application.
 *
 * @author Bruce BUJON (bruce.bujon(at)gmail(dot)com)
 */
public final class BatteryOptimizationUtils {
    /**
     * Private constructor of utility class.
     */
    private BatteryOptimizationUtils() {
        // Prevent instantiation
    }

    /**
     * Check whether the application is exempted from battery optimization.
     *
     * @param context The application context.
     * @return {@code true} if the application is exempted, {@code false} otherwise.
     */
    public static boolean isIgnoringBatteryOptimizations(Context context) {
        PowerManager powerManager = (PowerManager) context.getSystemService(Context.POWER_SERVICE);
        return powerManager != null && powerManager.isIgnoringBatteryOptimizations(context.getPackageName());
    }

    /**
     * Ask the user to exempt the application from battery optimization.<br>
     * Some devices do not provide the request dialog: fall back to the battery optimization
     * settings list, and do nothing if neither screen exists.
     *
     * @param context The context used to start the settings activity.
     * @return {@code true} if a settings screen was opened, {@code false} otherwise.
     */
    public static boolean requestIgnoreBatteryOptimizations(Context context) {
        Intent requestIntent = new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
                .setData(Uri.parse("package:" + context.getPackageName()));
        try {
            context.startActivity(requestIntent);
            return true;
        } catch (ActivityNotFoundException | SecurityException e) {
            Timber.w(e, "Failed to open the battery optimization request dialog.");
        }
        try {
            context.startActivity(new Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS));
            return true;
        } catch (ActivityNotFoundException | SecurityException e) {
            Timber.w(e, "Failed to open the battery optimization settings.");
            return false;
        }
    }
}
