package org.adaway.vpn;

import static android.content.Context.ACTIVITY_SERVICE;
import static org.adaway.broadcast.Command.START;
import static org.adaway.broadcast.Command.STOP;
import static org.adaway.vpn.VpnStatus.STOPPED;

import android.content.Context;
import android.content.Intent;
import android.app.ActivityManager;

import org.adaway.helper.PreferenceHelper;


import timber.log.Timber;

/**
 * This utility class allows controlling (start and stop) the AdAway VPN service.
 *
 * @author Bruce BUJON (bruce.bujon(at)gmail(dot)com)
 */
public final class VpnServiceControls {
    /**
     * Private constructor.
     */
    private VpnServiceControls() {

    }

    /**
     * Check if the VPN service is currently running.
     *
     * @param context The application context.
     * @return {@code true} if the VPN service is currently running, {@code false} otherwise.
     */
    public static boolean isRunning(Context context) {
        // Check this application service instead of any VPN network, as another VPN app could be active.
        // The persisted status is left untouched so the restart mechanisms can still detect a killed service.
        return PreferenceHelper.getVpnServiceStatus(context).isStarted()
                && isVpnServiceRunning(context);
    }

    /**
     * Check if the VPN service is started.
     *
     * @param context The application context.
     * @return {@code true} if the VPN service is started, {@code false} otherwise.
     */
    public static boolean isStarted(Context context) {
        return PreferenceHelper.getVpnServiceStatus(context).isStarted();
    }

    /**
     * Start the VPN service.
     *
     * @param context The application context.
     * @return {@code true} if the service is started, {@code false} otherwise.
     */
    public static boolean start(Context context) {
        // Check if VPN is already running
        if (isRunning(context)) {
            return true;
        }
        // Start the VPN service
        Intent intent = new Intent(context, VpnService.class);
        START.appendToIntent(intent);
        boolean started = context.startForegroundService(intent) != null;
        if (started) {
            // Start the heartbeat
            VpnServiceHeartbeat.start(context);
        }
        return started;
    }

    /**
     * Stop the VPN service.
     *
     * @param context The application context.
     */
    public static void stop(Context context) {
        // Stop the heartbeat
        VpnServiceHeartbeat.stop(context);
        // Stop the service
        if (!isVpnServiceRunning(context)) {
            // Nothing to stop. Starting a service from the background would throw.
            PreferenceHelper.setVpnServiceStatus(context, STOPPED);
            return;
        }
        Intent intent = new Intent(context, VpnService.class);
        STOP.appendToIntent(intent);
        try {
            context.startService(intent);
        } catch (IllegalStateException e) {
            Timber.w(e, "Failed to send stop command to VPN service.");
            PreferenceHelper.setVpnServiceStatus(context, STOPPED);
        }
    }

    @SuppressWarnings("deprecation") // Only returns the application own services, which is fine here
    private static boolean isVpnServiceRunning(Context context) {
        ActivityManager manager = (ActivityManager) context.getSystemService(ACTIVITY_SERVICE);
        if (manager == null) {
            return false;
        }
        String serviceName = VpnService.class.getName();
        for (ActivityManager.RunningServiceInfo service : manager.getRunningServices(Integer.MAX_VALUE)) {
            if (serviceName.equals(service.service.getClassName())) {
                return true;
            }
        }
        return false;
    }
}
