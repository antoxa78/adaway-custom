package org.adaway.broadcast;

import static org.adaway.model.adblocking.AdBlockMethod.VPN;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.net.VpnService;

import org.adaway.helper.PreferenceHelper;
import org.adaway.model.adblocking.AdBlockMethod;
import org.adaway.vpn.VpnServiceControls;

import timber.log.Timber;

/**
 * This broadcast receiver restarts the VPN service when it has been killed while it is supposed
 * to be running.
 * <p>
 * The system only delivers these broadcasts to manifest registered receivers when the process is
 * not running, which allows the application to restart the VPN tunnel after an unexpected kill.
 *
 * @author Bruce BUJON (bruce.bujon(at)gmail(dot)com)
 */
public class RestartReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        AdBlockMethod adBlockMethod = PreferenceHelper.getAdBlockMethod(context);
        if (adBlockMethod != VPN) {
            return;
        }
        // Ensure the application is still the prepared VPN owner, otherwise it must be authorized again
        if (VpnService.prepare(context) != null) {
            Timber.w("VPN authorization revoked, cannot restart VPN service.");
            return;
        }
        // Restart the VPN service if it is supposed to be running but is not
        boolean shouldRun = VpnServiceControls.isStarted(context);
        boolean running = VpnServiceControls.isRunning(context);
        if (shouldRun && !running) {
            Timber.i("Restarting VPN service from %s broadcast.", intent.getAction());
            try {
                VpnServiceControls.start(context);
            } catch (RuntimeException e) {
                Timber.w(e, "Failed to restart VPN service from %s broadcast.", intent.getAction());
            }
        }
    }
}