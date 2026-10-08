package org.adaway.vpn.worker;

import static java.lang.Integer.parseInt;
import static java.util.Objects.requireNonNull;

import android.content.Context;

import org.adaway.vpn.VpnServiceControls;
import org.adaway.vpn.dns.DnsServerMapper;

import java.net.NetworkInterface;
import java.net.SocketException;
import java.util.Enumeration;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import timber.log.Timber;

/**
 * This class monitors the VPN network interface is still up while the VPN is running.
 *
 * @author Bruce BUJON (bruce.bujon(at)gmail(dot)com)
 */
public class VpnConnectionMonitor {
    private static final int CONNECTION_CHECK_DELAY_MS = 10_000;
    private static final Pattern TUNNEL_PATTERN = Pattern.compile("tun([0-9]+)");
    /**
     * The application context.
     */
    private final Context context;
    /**
     * Whether the monitor is running (<code>true</code> if running, <code>false</code> if stopped).
     */
    private final AtomicBoolean running;
    /**
     * The network interface to monitor (<code>null</code> if not initialized).<br>
     * It is set by the worker thread and read by the monitor thread.
     */
    private volatile NetworkInterface networkInterface;
    /**
     * The DNS server mapper to detect network DNS changes (<code>null</code> if not initialized).
     */
    private volatile DnsServerMapper dnsServerMapper;

    /**
     * Constructor.
     *
     * @param context The application context.
     */
    VpnConnectionMonitor(Context context) {
        this.context = context;
        this.running = new AtomicBoolean(true);
        this.networkInterface = null;
        this.dnsServerMapper = null;
    }

    private static NetworkInterface findVpnNetworkInterface() {
        try {
            NetworkInterface vpnNetworkInterface = null;
            Enumeration<NetworkInterface> networkInterfaces = NetworkInterface.getNetworkInterfaces();
            while (networkInterfaces.hasMoreElements()) {
                NetworkInterface networkInterface = networkInterfaces.nextElement();
                vpnNetworkInterface = pickLastVpnNetworkInterface(vpnNetworkInterface, networkInterface);
            }
            if (vpnNetworkInterface == null) {
                throw new IllegalStateException("Failed to find a network interface.");
            }
            return vpnNetworkInterface;
        } catch (SocketException e) {
            throw new IllegalStateException("Failed to find VPN network interface.", e);
        }
    }

    private static NetworkInterface pickLastVpnNetworkInterface(NetworkInterface current, NetworkInterface other) {
        // Ensure other is a tunnel interface, otherwise picks current
        Matcher otherMatcher = TUNNEL_PATTERN.matcher(other.getName());
        if (!otherMatcher.matches()) {
            return current;
        }
        // If other is a tunnel interface and no current interface, picks other.
        if (current == null) {
            return other;
        }
        // Ensure current is still a tunnel interface (it happens to change), otherwise picks other
        Matcher currentMatcher = TUNNEL_PATTERN.matcher(current.getName());
        if (!currentMatcher.matches()) {
            Timber.e("Current interface %s is no more a tunnel interface.", current.getName());
            return other;
        }
        // Compare current and ot then pick the last one
        int currentTunnelNumber = parseInt(requireNonNull(currentMatcher.group(1)));
        int otherTunnelNumber = parseInt(requireNonNull(otherMatcher.group(1)));
        return otherTunnelNumber > currentTunnelNumber ? other : current;
    }

    /**
     * Initialize the monitor once the VPN connection is up.
     *
     * @param dnsServerMapper The DNS server mapper used to detect network DNS changes.
     */
    void initialize(DnsServerMapper dnsServerMapper) {
        Timber.d("Initializing connection monitor…");
        this.networkInterface = findVpnNetworkInterface();
        this.dnsServerMapper = dnsServerMapper;
        Timber.d("Connection monitor initialized to watch interface %s.", this.networkInterface.getName());
    }

    /**
     * Monitor the VPN network interface is still up while the VPN is running.
     */
    void monitor() {
        // The same monitor instance is reused each time the worker starts: re-arm it
        this.running.set(true);
        try {
            while (this.running.get()) {
                NetworkInterface monitoredInterface = this.networkInterface;
                if (monitoredInterface != null && !monitoredInterface.isUp()) {
                    stop();
                    Timber.i("VPN network interface %s is down. Restarting VPN service…",
                            monitoredInterface.getName());
                    // Use restart, not start: the service is still running, only its tunnel is dead
                    VpnServiceControls.restart(this.context);
                }
                // Re-map DNS servers when the underlying network changed them: forwarding DNS to a
                // stale (unreachable) server silently times out and makes the device look offline.
                // The mapping is updated in place to avoid interrupting DNS with a tunnel restart.
                DnsServerMapper dnsMapper = this.dnsServerMapper;
                if (dnsMapper != null && dnsMapper.remapDnsServers(this.context)) {
                    Timber.i("Network DNS servers changed, DNS mapping updated in place.");
                }
                try {
                    Thread.sleep(CONNECTION_CHECK_DELAY_MS);
                } catch (InterruptedException e) {
                    Timber.d("Stop monitoring.");
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        } catch (SocketException e) {
            NetworkInterface monitoredInterface = this.networkInterface;
            Timber.w(e, "Failed to test VPN network interface %s. Restarting VPN service…",
                    monitoredInterface == null ? "unset" : monitoredInterface.getName());
            reset();
            VpnServiceControls.restart(this.context);
        }
    }

    /**
     * Reset the connection monitor if the VPN connection changed.
     */
    void reset() {
        this.networkInterface = null;
        this.dnsServerMapper = null;
    }

    /**
     * Stop the monitor.
     */
    void stop() {
        this.running.set(false);
    }
}
