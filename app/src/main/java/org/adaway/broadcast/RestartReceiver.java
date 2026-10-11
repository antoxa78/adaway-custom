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
 * It listens to the few system broadcasts still delivered to manifest registered receivers
 * (time, time zone and locale changes, and the application update), which wake the application
 * up even when its process was killed, so the VPN tunnel can be restarted.
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
        // Ensure the tunnel is established without relying on the service liveness: a service left
        // running without any tunnel is restarted too
        try {
            if (VpnServiceControls.ensureRunning(context)) {
                Timber.i("Restarted VPN service from %s broadcast.", intent.getAction());
            }
        } catch (RuntimeException e) {
            Timber.w(e, "Failed to restart VPN service from %s broadcast.", intent.getAction());
        }
    }
}