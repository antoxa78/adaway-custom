package org.adaway.vpn;

import static android.content.Context.ACTIVITY_SERVICE;
import static android.content.Context.CONNECTIVITY_SERVICE;
import static android.net.NetworkCapabilities.NET_CAPABILITY_INTERNET;
import static android.net.NetworkCapabilities.TRANSPORT_VPN;
import static org.adaway.broadcast.Command.START;
import static org.adaway.broadcast.Command.STOP;
import static org.adaway.vpn.VpnStatus.STOPPED;

import android.app.ActivityManager;
import android.content.Context;
import android.content.Intent;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;

import org.adaway.helper.PreferenceHelper;
import org.adaway.vpn.worker.VpnWorker;

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
     * Check if the VPN tunnel is currently established.
     * <p>
     * Unlike {@link #isRunning(Context)}, which only tells whether the VPN service is alive, this
     * tells whether the tunnel interface is actually open.
     *
     * @return {@code true} if the VPN tunnel is established, {@code false} otherwise.
     */
    public static boolean isTunnelEstablished() {
        return VpnWorker.isTunnelEstablished();
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
            if (isTunnelEstablished() || VpnWorker.isVpnStarting()) {
                return true;
            }
            // The service is alive but its tunnel is gone: rebuild it, without racing a tunnel
            // which may have been established after this check.
            return restart(context, VpnWorker.getCurrentGeneration(), VpnWorker.getCurrentTunnelId());
        }
        // Start the VPN service
        Intent intent = new Intent(context, VpnService.class);
        START.appendToIntent(intent);
        boolean started;
        try {
            started = context.startForegroundService(intent) != null;
        } catch (IllegalStateException e) {
            // The system refuses to start a foreground service from the background
            // (ForegroundServiceStartNotAllowedException on Android 12 and later).
            Timber.w(e, "Failed to send start command to VPN service.");
            return false;
        }
        if (started) {
            // Start the heartbeat
            VpnServiceHeartbeat.start(context);
        }
        return started;
    }

    /**
     * Restart the VPN tunnel.<br>
     * Unlike {@link #start(Context)}, the start command is always sent, even if the service is
     * already running, so the service rebuilds its tunnel. Use it when the tunnel is known to be
     * broken (its network interface went down) or when the VPN configuration changed.
     *
     * @param context The application context.
     * @return {@code true} if the restart command was sent, {@code false} otherwise.
     */
    public static boolean restart(Context context) {
        return restart(context, -1, VpnService.NO_TUNNEL_EXPECTATION);
    }

    /**
     * Restart the VPN tunnel if the observed generation and tunnel are still current.
     *
     * @param context            The application context.
     * @param expectedGeneration The generation observed by the caller, or {@code -1} for no check.
     * @param expectedTunnelId   The tunnel observed by the caller, {@code 0} for no tunnel, or
     *                           {@link VpnService#NO_TUNNEL_EXPECTATION} for no check.
     * @return {@code true} if the restart command was sent, {@code false} otherwise.
     */
    public static boolean restart(Context context, int expectedGeneration, long expectedTunnelId) {
        Intent intent = new Intent(context, VpnService.class);
        START.appendToIntent(intent);
        if (expectedGeneration >= 0) {
            intent.putExtra(VpnService.EXTRA_EXPECTED_GENERATION, expectedGeneration);
        }
        if (expectedTunnelId != VpnService.NO_TUNNEL_EXPECTATION) {
            intent.putExtra(VpnService.EXTRA_EXPECTED_TUNNEL_ID, expectedTunnelId);
        }
        boolean started;
        try {
            started = context.startForegroundService(intent) != null;
        } catch (IllegalStateException e) {
            Timber.w(e, "Failed to send restart command to VPN service.");
            return false;
        }
        if (started) {
            // Start the heartbeat
            VpnServiceHeartbeat.start(context);
        }
        return started;
    }

    /**
     * Ensure the VPN tunnel is established while ad blocking is expected to run.
     * <p>
     * Unlike {@link #isRunning(Context)}, this does not trust the service liveness: a service left
     * running without any tunnel is restarted too, so the self-healing does not depend on the
     * service being killed to happen. It is a no-op when ad blocking is stopped, when the tunnel
     * is already established, or when the service is alive but waiting for a network.
     *
     * @param context The application context.
     * @return {@code true} if a start or restart command was sent, {@code false} otherwise.
     */
    public static boolean ensureRunning(Context context) {
        if (!isStarted(context) || isTunnelEstablished()) {
            return false;
        }
        // A worker may be establishing or retrying its tunnel: do not tear it down.
        if (VpnWorker.isVpnStarting()) {
            return false;
        }
        // When the service is still alive, only rebuild the tunnel if an underlying network is
        // available: otherwise the service is legitimately waiting for one and would rebuild a
        // tunnel it cannot use (see VpnStatus.WAITING_FOR_NETWORK).
        if (isVpnServiceRunning(context)) {
            if (!isUnderlyingNetworkAvailable(context)) {
                return false;
            }
            // Pass the observed generation and tunnel so a start which races with a newly
            // established tunnel is ignored by the service.
            Timber.i("VPN tunnel is not established, restarting VPN service…");
            return restart(context, VpnWorker.getCurrentGeneration(), VpnWorker.getCurrentTunnelId());
        }
        Timber.i("VPN tunnel is not established, starting VPN service…");
        return restart(context);
    }

    /**
     * Check if an underlying network (Wi-Fi or cellular, not the VPN itself) with internet access
     * is available.
     *
     * @param context The application context.
     * @return {@code true} if an underlying network is available, {@code false} otherwise.
     */
    private static boolean isUnderlyingNetworkAvailable(Context context) {
        ConnectivityManager connectivityManager = (ConnectivityManager) context.getSystemService(CONNECTIVITY_SERVICE);
        if (connectivityManager == null) {
            return false;
        }
        for (Network network : connectivityManager.getAllNetworks()) {
            NetworkCapabilities capabilities = connectivityManager.getNetworkCapabilities(network);
            if (capabilities != null
                    && capabilities.hasCapability(NET_CAPABILITY_INTERNET)
                    && !capabilities.hasTransport(TRANSPORT_VPN)) {
                return true;
            }
        }
        return false;
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
