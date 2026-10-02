/*
 * Derived from dns66:
 * Copyright (C) 2016-2019 Julian Andres Klode <jak@jak-linux.org>
 *
 * Parsing code derived from AdBuster:
 * Copyright (C) 2016 Daniel Brodie <dbrodie@gmail.com>
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, version 3.
 *
 * Contributions shall also be provided under any later versions of the
 * GPL.
 */
package org.adaway.vpn.worker;

import androidx.annotation.NonNull;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.SocketException;

import timber.log.Timber;

/**
 * Checks that the tunnel can still reach its target and paces the polls.
 * <p>
 * The probes sent here are keep-alives: while the device is idle, a perfectly healthy tunnel
 * carries no packets at all, so a probe may go unanswered for a long time. Therefore an
 * unanswered probe is <em>not</em> treated as a failure. The tunnel is only considered dead when
 * the probe cannot even be sent (e.g. <code>ENETUNREACH</code>), which indicates the underlying
 * network is unreachable. All other failures (closed tunnel, failed DNS forwards) are reported by
 * the DNS forward path and by {@link VpnConnectionMonitor}.
 * <p>
 * While the tunnel is alive, the poll timeout is quadrupled on success up to a maximum, and it is
 * backed off on consecutive unanswered probes so the watchdog does not hammer the network when the
 * device is simply idle.
 */

class VpnWatchdog {
    // Polling is quadrupled on every success, and values range from 4s to 1h8m.
    private static final int POLL_TIMEOUT_START = 1000;
    private static final int POLL_TIMEOUT_END = 4096000;
    private static final int POLL_TIMEOUT_WAITING = 7000;
    private static final int POLL_TIMEOUT_GROW = 4;

    private int pollTimeout = POLL_TIMEOUT_START;

    // Information about when packets where received.
    private long lastPacketSent;
    private long lastPacketReceived;

    // The number of probes that were sent without any packet received in between.
    private int consecutiveMisses;

    private boolean enabled;
    private DatagramPacket checkAlivePacket;

    VpnWatchdog() {
        // Set default timestamps
        this.lastPacketSent = 0;
        this.lastPacketReceived = 0;
        // Set disable by default
        this.enabled = false;
        this.consecutiveMisses = 0;
    }

    /**
     * Returns the current poll time out.
     */
    int getPollTimeout() {
        if (!this.enabled) {
            return -1;
        }
        if (this.lastPacketReceived < this.lastPacketSent) {
            // A probe is awaiting a response. While the device is idle the tunnel may legitimately
            // stay quiet, so wait a bit longer after each consecutive miss instead of polling fast.
            return Math.min(POLL_TIMEOUT_WAITING * (1 + this.consecutiveMisses), POLL_TIMEOUT_END);
        }
        return this.pollTimeout;
    }

    /**
     * Sets the target address ping packets should be sent to.
     */
    void setTarget(InetAddress target) {
        this.checkAlivePacket = new DatagramPacket(new byte[0], 0, 0 /* length */, target, 53);
    }

    /**
     * An initialization method.
     *
     * @param enabled If the watchdog should be enabled.
     */
    void initialize(boolean enabled) {
        Timber.d("initialize: Initializing watchdog");

        this.pollTimeout = POLL_TIMEOUT_START;
        this.lastPacketSent = 0;
        this.consecutiveMisses = 0;
        this.enabled = enabled;

        if (!this.enabled) {
            Timber.d("initialize: Disabled.");
        }
    }

    /**
     * Handles a timeout of poll().
     * <p>
     * An unanswered probe is expected while the device is idle and is <em>not</em> fatal: only a
     * failure to send the probe (underlying network unreachable) throws a
     * {@link VpnNetworkException}.
     *
     * @throws VpnNetworkException When the probe could not be sent, meaning the network is dead.
     */
    void handleTimeout() throws VpnNetworkException {
        if (!this.enabled) {
            return;
        }
        Timber.d("handleTimeout: Milliseconds elapsed between last receive and sent: %dms", (this.lastPacketReceived - this.lastPacketSent));
        if (this.lastPacketReceived < this.lastPacketSent && this.lastPacketSent != 0) {
            // The probe was not answered within the wait window. This is normal while the device is
            // idle: a healthy tunnel carries no packets when no application uses it. Do not treat it
            // as a fatal error; back off and send a new probe.
            this.consecutiveMisses++;
            this.pollTimeout = Math.min(this.pollTimeout * POLL_TIMEOUT_GROW, POLL_TIMEOUT_END);
            sendPacket();
            return;
        }
        // We received a packet after sending it, so we can be more confident and grow our wait time.
        this.consecutiveMisses = 0;
        this.pollTimeout *= POLL_TIMEOUT_GROW;
        if (this.pollTimeout > POLL_TIMEOUT_END) {
            this.pollTimeout = POLL_TIMEOUT_END;
        }

        sendPacket();
    }

    /**
     * Handles an incoming packet on a device.
     *
     * @param packetData The data of the packet
     */
    void handlePacket(byte[] packetData) {
        if (!this.enabled) {
            return;
        }
        Timber.d("handlePacket: Received packet of length %s", packetData.length);
        this.lastPacketReceived = System.currentTimeMillis();
    }

    /**
     * Sends an empty check-alive packet to the configured target address.
     *
     * @throws VpnNetworkException If sending failed and we should restart
     */
    void sendPacket() throws VpnNetworkException {
        if (!this.enabled || this.checkAlivePacket == null) {
            return;
        }
        Timber.d("sendPacket: Sending packet, poll timeout is %d.", this.pollTimeout);

        try (DatagramSocket socket = newDatagramSocket()) {
            socket.send(this.checkAlivePacket);
            this.lastPacketSent = System.currentTimeMillis();
        } catch (IOException e) {
            throw new VpnNetworkException("Failed to send check-alive packet.", e);
        }
    }

    @NonNull
    DatagramSocket newDatagramSocket() throws SocketException {
        return new DatagramSocket();
    }
}