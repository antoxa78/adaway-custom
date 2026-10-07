package org.adaway.util;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.provider.Settings;

/**
 * This class provides a best-effort helper to open the system autostart settings of common
 * device manufacturers.
 *
 * @author Bruce BUJON (bruce.bujon(at)gmail(dot)com)
 */
public final class AutostartUtils {
    /**
     * The known manufacturer autostart settings intents.
     */
    private static final Intent[] AUTOSTART_SETTINGS_INTENTS = {
            // Xiaomi / MIUI
            new Intent().setComponent(new ComponentName(
                    "com.miui.securitycenter",
                    "com.miui.permcenter.autostart.AutoStartManagementActivity")),
            // Huawei / Honor
            new Intent().setComponent(new ComponentName(
                    "com.huawei.systemmanager",
                    "com.huawei.systemmanager.startupmgr.ui.StartupNormalAppListActivity")),
            new Intent().setComponent(new ComponentName(
                    "com.huawei.systemmanager",
                    "com.huawei.systemmanager.optimize.process.ProtectActivity")),
            // Oppo / Realme
            new Intent().setComponent(new ComponentName(
                    "com.coloros.safecenter",
                    "com.coloros.safecenter.permission.startup.StartupAppListActivity")),
            new Intent().setComponent(new ComponentName(
                    "com.coloros.safecenter",
                    "com.coloros.safecenter.startupapp.StartupAppListActivity")),
            // Vivo
            new Intent().setComponent(new ComponentName(
                    "com.vivo.permissionmanager",
                    "com.vivo.permissionmanager.activity.BgStartUpManagerActivity")),
            // Letv
            new Intent().setComponent(new ComponentName(
                    "com.letv.android.letvsafe",
                    "com.letv.android.letvsafe.AutobootManageActivity")),
            // Asus
            new Intent().setComponent(new ComponentName(
                    "com.asus.mobilemanager",
                    "com.asus.mobilemanager.autostart.AutoStartActivity")),
    };

    /**
     * Private constructor of utility class.
     */
    private AutostartUtils() {
        // Prevent instantiation
    }

    /**
     * Open the autostart settings of the device.<br>
     * Try the known manufacturer settings first, then fallback to the application details settings.
     *
     * @param context The context used to start the settings activity.
     * @return {@code true} if a settings activity was opened, {@code false} otherwise.
     */
    public static boolean openOemAutostartSettings(Context context) {
        for (Intent intent : AUTOSTART_SETTINGS_INTENTS) {
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            try {
                context.startActivity(intent);
                return true;
            } catch (Exception ignored) {
                // Try the next intent
            }
        }
        return false;
    }

    /**
     * Open the autostart settings of the device.<br>
     * Try the known manufacturer settings first, then fallback to the application details settings.
     *
     * @param context The context used to start the settings activity.
     * @return {@code true} if a settings activity was opened, {@code false} otherwise.
     */
    public static boolean openAutostartSettings(Context context) {
        if (openOemAutostartSettings(context)) {
            return true;
        }
        // Fallback to the application details settings
        try {
            Intent intent = new Intent(
                    Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    Uri.parse("package:" + context.getPackageName())
            ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            context.startActivity(intent);
            return true;
        } catch (Exception ignored) {
            return false;
        }
    }
}
