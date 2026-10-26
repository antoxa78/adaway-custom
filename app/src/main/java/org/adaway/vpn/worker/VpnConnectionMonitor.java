package org.adaway.vpn.worker;

import static java.lang.Integer.parseInt;
import static java.util.Objects.requireNonNull;

import android.content.Context;

import org.adaway.vpn.dns.DnsServerMapper;

import java.net.NetworkInterface;
import java.net.SocketException;
import java.util.Enumeration;
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
     * The worker owning the tunnel. The monitor only ever acts on the tunnel it observed, so a
     * stale monitor cannot tear down a tunnel established by a newer generation.
     */
    private final VpnWorker worker;

    /**
     * Constructor.
     *
     * @param context The application context.
     * @param worker  The VPN worker.
     */
    VpnConnectionMonitor(Context context, VpnWorker worker) {
        this.context = context;
        this.worker = worker;
    }

    static NetworkInterface findVpnNetworkInterface() {
        try {
            NetworkInterface vpnNetworkInterface = null;
            Enumeration<NetworkInterface> networkInterfaces = NetworkInterface.getNetworkInterfaces();
            while (networkInterfaces.hasMoreElements()) {
                NetworkInterface networkInterface = networkInterfaces.nextElement();
                vpnNetworkInterface = pickLastVpnNetworkInterface(vpnNetworkInterface, networkInterface);
            }
            if (vpnNetworkInterface == null) {
                Timber.w("Failed to find a VPN network interface.");
            }
            return vpnNetworkInterface;
        } catch (SocketException e) {
            Timber.w(e, "Failed to find VPN network interface.");
            return null;
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
     * Monitor the VPN network interface is still up while the VPN is running.
     *
     * @param generation The generation this monitor belongs to.
     */
    void monitor(int generation) {
        while (this.worker.isGeneration(generation)) {
            VpnWorker.Tunnel tunnel = this.worker.getTunnel();
            if (tunnel != null && tunnel.generation == generation) {
                NetworkInterface networkInterface = tunnel.networkInterface;
                if (networkInterface == null) {
                    // The interface may not have been visible when the tunnel was registered.
                    networkInterface = findVpnNetworkInterface();
                }
                if (networkInterface != null) {
                    try {
                        if (!networkInterface.isUp()) {
                            Timber.i("VPN network interface %s is down. Restarting VPN service…",
                                    networkInterface.getName());
                            if (this.worker.requestRestart(generation, tunnel.id)) {
                                return;
                            }
                        }
                    } catch (SocketException e) {
                        Timber.w(e, "Failed to test VPN network interface %s. Restarting VPN service…",
                                networkInterface.getName());
                        if (this.worker.requestRestart(generation, tunnel.id)) {
                            return;
                        }
                    }
                }
                // Re-map DNS servers when the underlying network changed them: forwarding DNS to a
                // stale (unreachable) server silently times out and makes the device look offline.
                DnsServerMapper dnsMapper = tunnel.dnsServerMapper;
                if (dnsMapper != null) {
                    DnsServerMapper.DnsUpdate update = dnsMapper.remapDnsServers(this.context);
                    if (update == DnsServerMapper.DnsUpdate.RESTART_REQUIRED) {
                        // The tunnel was established while the network had no DNS server, so its
                        // fake DNS addresses and routes are not registered. Rebuild it now that DNS
                        // servers are available.
                        Timber.i("DNS servers are now available. Restarting VPN service to use them…");
                        if (this.worker.requestRestart(generation, tunnel.id)) {
                            return;
                        }
                        Timber.w("Failed to restart VPN service to use the new DNS servers, will retry.");
                    } else if (update == DnsServerMapper.DnsUpdate.REMAPPED) {
                        Timber.i("Network DNS servers changed, DNS mapping updated in place.");
                    }
                }
            }
            try {
                Thread.sleep(CONNECTION_CHECK_DELAY_MS);
            } catch (InterruptedException e) {
                Timber.d("Stop monitoring.");
                Thread.currentThread().interrupt();
                break;
            }
        }
    }
}
