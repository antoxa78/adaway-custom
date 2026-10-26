/*
 * Derived from dns66:
 * Copyright (C) 2016-2019 Julian Andres Klode <jak@jak-linux.org>
 *
 * Derived from AdBuster:
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

import static android.system.OsConstants.ENETUNREACH;
import static android.system.OsConstants.EPERM;
import static android.system.OsConstants.POLLIN;
import static android.system.OsConstants.POLLOUT;
import static org.adaway.vpn.VpnStatus.RECONNECTING_NETWORK_ERROR;
import static org.adaway.vpn.VpnStatus.RUNNING;
import static org.adaway.vpn.VpnStatus.STARTING;
import static org.adaway.vpn.VpnStatus.STOPPED;
import static org.adaway.vpn.VpnStatus.STOPPING;
import static org.adaway.vpn.worker.VpnBuilder.establish;

import android.os.ParcelFileDescriptor;
import android.system.ErrnoException;
import android.system.Os;
import android.system.StructPollfd;

import org.adaway.helper.PreferenceHelper;
import org.adaway.vpn.VpnService;
import org.adaway.vpn.VpnServiceControls;
import org.adaway.vpn.dns.DnsPacketProxy;
import org.adaway.vpn.dns.DnsQueryQueue;
import org.adaway.vpn.dns.DnsServerMapper;
import org.pcap4j.packet.IpPacket;

import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.NetworkInterface;
import java.net.SocketException;
import java.util.Arrays;
import java.util.LinkedList;
import java.util.Queue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

import timber.log.Timber;

/**
 * The VPN worker establishes the tunnel and forwards DNS packets.
 * <p>
 * A worker generation identifies one start of the service. A generation is superseded as soon as
 * the service is started or stopped again: its threads then exit silently, and any tunnel it may
 * still establish is closed instead of replacing the current one. This is required because the
 * system (always-on VPN) and the connection monitor can both ask for a restart while a previous
 * tunnel is being established. Without this guard, a late {@link #stop()} could close the tunnel
 * of a newer generation and leave the service running without any tunnel.
 */
public class VpnWorker implements DnsPacketProxy.EventLoop {
    /**
     * Maximum packet size is constrained by the MTU, which is given as a signed short.
     */
    private static final int MAX_PACKET_SIZE = Short.MAX_VALUE;

    /**
     * Whether the VPN tunnel is currently established, i.e. the tunnel interface is open.<br>
     * It is updated by the worker thread and read by the application (same process) to detect a
     * service left running without any tunnel.
     */
    private static final AtomicBoolean TUNNEL_ESTABLISHED = new AtomicBoolean(false);

    /**
     * The worker of the currently running VPN service, used by the heartbeat and the UI to avoid
     * restarting a tunnel that is already starting.
     */
    private static final AtomicReference<VpnWorker> CURRENT_WORKER = new AtomicReference<>(null);

    /**
     * The VPN service, also used as {@link android.content.Context}.
     */
    private final VpnService vpnService;
    /**
     * The queue of packets to send to the device.
     */
    private final Queue<byte[]> deviceWrites;
    /**
     * The queue of DNS queries.
     */
    private final DnsQueryQueue dnsQueryQueue;
    // The mapping between fake and real dns addresses
    private final DnsServerMapper dnsServerMapper;
    // The object where we actually handle packets.
    private final DnsPacketProxy dnsPacketProxy;

    // TODO Comment
    private final VpnConnectionThrottler connectionThrottler;
    private final VpnConnectionMonitor connectionMonitor;

    // Watch dog that checks our connection is alive.
    private final VpnWatchdog vpnWatchDog;

    /**
     * The VPN worker executor (<code>null</code> if not started).
     */
    private final AtomicReference<ExecutorService> executor;
    /**
     * The generation counter incremented each time the worker is started or stopped.
     * It allows a superseded worker thread to detect it has been replaced.
     */
    private final AtomicInteger generation;
    /**
     * The currently established tunnel (<code>null</code> if not established).<br>
     * The tunnel is tagged with the generation which established it so a superseded worker can
     * never close a newer tunnel.
     */
    private final AtomicReference<Tunnel> tunnel;
    /**
     * The tunnel identifier sequence, used to detect a tunnel which was replaced between a monitor
     * check and the corresponding restart command.
     */
    private final AtomicLong tunnelSequence;
    /**
     * The lifecycle lock guarding {@link #generation}, {@link #starting}, {@link #tunnel} and the
     * executor. It is also used to serialize start/stop commands with monitor restart requests.
     */
    private final Object lifecycleLock;
    /**
     * The establishment lock serializing tunnel creation and registration. It prevents a
     * superseded worker from overwriting the DNS mapping of the newer tunnel.
     */
    private final Object establishLock;
    /**
     * Whether a worker is currently starting or retrying to establish its tunnel.
     */
    private boolean starting;

    /**
     * Constructor.
     *
     * @param vpnService The VPN service, also used as {@link android.content.Context}.
     */
    public VpnWorker(VpnService vpnService) {
        this.vpnService = vpnService;
        this.deviceWrites = new LinkedList<>();
        this.dnsQueryQueue = new DnsQueryQueue();
        this.dnsServerMapper = new DnsServerMapper();
        this.dnsPacketProxy = new DnsPacketProxy(this, this.dnsServerMapper);
        this.connectionThrottler = new VpnConnectionThrottler();
        this.vpnWatchDog = new VpnWatchdog();
        this.executor = new AtomicReference<>(null);
        this.generation = new AtomicInteger(0);
        this.tunnel = new AtomicReference<>(null);
        this.tunnelSequence = new AtomicLong(0);
        this.lifecycleLock = new Object();
        this.establishLock = new Object();
        this.starting = false;
        this.connectionMonitor = new VpnConnectionMonitor(this.vpnService, this);
        CURRENT_WORKER.set(this);
    }

    /**
     * Check whether the VPN tunnel is currently established.
     *
     * @return {@code true} if the tunnel interface is open, {@code false} otherwise.
     */
    public static boolean isTunnelEstablished() {
        return TUNNEL_ESTABLISHED.get();
    }

    /**
     * Get the generation of the current VPN worker.
     *
     * @return The current generation, or {@code -1} if no worker exists.
     */
    public static int getCurrentGeneration() {
        VpnWorker worker = CURRENT_WORKER.get();
        return worker == null ? -1 : worker.generation.get();
    }

    /**
     * Get the identifier of the current tunnel.
     *
     * @return The current tunnel identifier, or {@code 0} if no tunnel is established.
     */
    public static long getCurrentTunnelId() {
        VpnWorker worker = CURRENT_WORKER.get();
        if (worker == null) {
            return 0;
        }
        Tunnel currentTunnel = worker.tunnel.get();
        return currentTunnel == null ? 0 : currentTunnel.id;
    }

    /**
     * Check whether a VPN worker is currently starting or retrying to establish its tunnel.
     *
     * @return {@code true} if a worker is starting, {@code false} otherwise.
     */
    public static boolean isVpnStarting() {
        VpnWorker worker = CURRENT_WORKER.get();
        return worker != null && worker.isStarting();
    }

    private boolean isStarting() {
        synchronized (this.lifecycleLock) {
            return this.starting;
        }
    }

    /**
     * Check whether this worker still is the current generation.
     *
     * @param workerGeneration The generation to check.
     * @return {@code true} if the generation is current, {@code false} otherwise.
     */
    public boolean isGeneration(int workerGeneration) {
        return this.generation.get() == workerGeneration;
    }

    /**
     * Start the VPN worker if the request is still relevant.
     * <p>
     * A system start (always-on VPN) must not tear down a healthy tunnel. A restart request from
     * the connection monitor must not tear down a tunnel that replaced the one it observed.
     *
     * @param expectedGeneration The generation the caller observed, or {@code -1} for no check.
     * @param expectedTunnelId   The tunnel the caller observed, {@code 0} for no tunnel, or
     *                           {@link VpnService#NO_TUNNEL_EXPECTATION} for no check.
     * @param force              Whether a running tunnel must be rebuilt.
     * @return {@code true} if a new worker was started, {@code false} otherwise.
     */
    public boolean startIfNeeded(int expectedGeneration, long expectedTunnelId, boolean force) {
        synchronized (this.lifecycleLock) {
            if (expectedGeneration >= 0 && expectedGeneration != this.generation.get()) {
                Timber.d("Ignoring stale start request for generation %d (current generation is %d).",
                        expectedGeneration, this.generation.get());
                return false;
            }
            if (expectedTunnelId != VpnService.NO_TUNNEL_EXPECTATION) {
                Tunnel currentTunnel = this.tunnel.get();
                long currentTunnelId = currentTunnel == null ? 0 : currentTunnel.id;
                if (currentTunnelId != expectedTunnelId) {
                    Timber.d("Ignoring stale start request for tunnel %d (current tunnel is %d).",
                            expectedTunnelId, currentTunnelId);
                    return false;
                }
            }
            if (!force && (this.starting || this.tunnel.get() != null)) {
                Timber.d("VPN is already starting or running, ignoring start request.");
                return false;
            }

            // Invalidate the previous worker and close its tunnel before starting the new one.
            int stoppedGeneration = this.generation.getAndIncrement();
            this.starting = false;
            closeTunnelUpTo(stoppedGeneration);
            shutdownExecutorLocked();
            this.connectionThrottler.reset();

            int workerGeneration = this.generation.incrementAndGet();
            this.starting = true;
            ExecutorService workerExecutor = Executors.newFixedThreadPool(2);
            this.executor.set(workerExecutor);
            workerExecutor.submit(() -> work(workerGeneration));
            workerExecutor.submit(() -> this.connectionMonitor.monitor(workerGeneration));
            Timber.i("VPN thread started (generation %d).", workerGeneration);
            return true;
        }
    }

    /**
     * Stop the VPN worker.
     */
    public void stop() {
        synchronized (this.lifecycleLock) {
            Timber.d("Stopping VPN thread.");
            int stoppedGeneration = this.generation.getAndIncrement();
            this.starting = false;
            // The tunnel interface is closed here: reflect it immediately, even if the worker
            // thread has not unwound its run loop yet.
            closeTunnelUpTo(stoppedGeneration);
            shutdownExecutorLocked();
            Timber.i("VPN thread stopped.");
        }
    }

    /**
     * Shut the previous executor down.
     */
    private void shutdownExecutorLocked() {
        ExecutorService oldExecutor = this.executor.getAndSet(null);
        if (oldExecutor != null) {
            Timber.d("Shutting down VPN executor…");
            oldExecutor.shutdownNow();
            Timber.d("VPN executor shut down.");
        }
    }

    /**
     * Close the tunnel if it belongs to the given generation or an older one.
     *
     * @param maxGeneration The maximum generation to close.
     */
    private void closeTunnelUpTo(int maxGeneration) {
        while (true) {
            Tunnel currentTunnel = this.tunnel.get();
            if (currentTunnel == null || currentTunnel.generation > maxGeneration) {
                break;
            }
            if (this.tunnel.compareAndSet(currentTunnel, null)) {
                TUNNEL_ESTABLISHED.set(false);
                closeQuietly(currentTunnel.pfd);
                break;
            }
        }
    }

    /**
     * Register a newly established tunnel.
     *
     * @param workerGeneration The generation which established the tunnel.
     * @param pfd              The tunnel file descriptor.
     * @return {@code true} if the tunnel was registered, {@code false} if the worker was replaced.
     */
    private boolean registerTunnel(int workerGeneration, ParcelFileDescriptor pfd) {
        NetworkInterface networkInterface = VpnConnectionMonitor.findVpnNetworkInterface();
        // Never monitor an interface which is already down: it is likely a leftover from the
        // previous generation, and watching it would restart the freshly established tunnel.
        if (networkInterface != null) {
            try {
                if (!networkInterface.isUp()) {
                    networkInterface = null;
                }
            } catch (SocketException e) {
                networkInterface = null;
            }
        }
        synchronized (this.lifecycleLock) {
            if (workerGeneration != this.generation.get()) {
                closeQuietly(pfd);
                return false;
            }
            Tunnel previousTunnel = this.tunnel.getAndSet(new Tunnel(
                    workerGeneration,
                    this.tunnelSequence.incrementAndGet(),
                    pfd,
                    networkInterface,
                    this.dnsServerMapper
            ));
            if (previousTunnel != null) {
                closeQuietly(previousTunnel.pfd);
            }
            TUNNEL_ESTABLISHED.set(true);
            this.starting = false;
            return true;
        }
    }

    /**
     * Unregister a tunnel when its worker exits.
     *
     * @param workerGeneration The generation which established the tunnel.
     */
    private void unregisterTunnel(int workerGeneration) {
        synchronized (this.lifecycleLock) {
            Tunnel currentTunnel = this.tunnel.get();
            if (currentTunnel != null
                    && currentTunnel.generation == workerGeneration
                    && this.tunnel.compareAndSet(currentTunnel, null)) {
                TUNNEL_ESTABLISHED.set(false);
            }
        }
    }

    private static void closeQuietly(ParcelFileDescriptor pfd) {
        if (pfd == null) {
            return;
        }
        try {
            pfd.close();
        } catch (IOException e) {
            Timber.tag("Failed to close VPN network interface.").w(e);
        }
    }

    /**
     * Get the current tunnel for the connection monitor.
     *
     * @return The current tunnel, or {@code null} if no tunnel is established.
     */
    Tunnel getTunnel() {
        return this.tunnel.get();
    }

    /**
     * Request a VPN service restart if the observed tunnel is still the current one.
     *
     * @param workerGeneration The generation the monitor observed.
     * @param tunnelId         The tunnel identifier the monitor observed.
     * @return {@code true} if the restart command was sent, {@code false} otherwise.
     */
    boolean requestRestart(int workerGeneration, long tunnelId) {
        synchronized (this.lifecycleLock) {
            if (workerGeneration != this.generation.get()) {
                return false;
            }
            Tunnel currentTunnel = this.tunnel.get();
            if (currentTunnel == null || currentTunnel.id != tunnelId) {
                return false;
            }
        }
        return VpnServiceControls.restart(this.vpnService, workerGeneration, tunnelId);
    }

    private void work(int currentGeneration) {
        Timber.d("Starting work…");
        // Initialize context
        this.dnsPacketProxy.initialize(this.vpnService);
        // Initialize the watchdog
        this.vpnWatchDog.initialize(PreferenceHelper.getVpnWatchdogEnabled(this.vpnService));
        try {
            // Try connecting the vpn continuously
            while (true) {
                try {
                    this.connectionThrottler.throttle();
                    if (currentGeneration != this.generation.get()) {
                        break;
                    }
                    this.vpnService.notifyVpnStatus(STARTING);
                    runVpn(currentGeneration);
                    Timber.i("Told to stop");
                    if (currentGeneration == this.generation.get()) {
                        this.vpnService.notifyVpnStatus(STOPPING);
                    }
                    break;
                } catch (InterruptedException e) {
                    Timber.d(e, "Failed to wait for connexion throttling.");
                    Thread.currentThread().interrupt();
                    break;
                } catch (VpnNetworkException | IOException e) {
                    Timber.w(e, "Network exception in vpn thread, reconnecting…");
                    // If the worker was replaced, do not reconnect nor report a stale status.
                    if (currentGeneration != this.generation.get()) {
                        Timber.i("VPN worker was replaced, stopping.");
                        break;
                    }
                    // If an exception was thrown, notify status and try again
                    this.vpnService.notifyVpnStatus(RECONNECTING_NETWORK_ERROR);
                }
            }
            if (currentGeneration == this.generation.get()) {
                this.vpnService.notifyVpnStatus(STOPPED);
            }
        } finally {
            synchronized (this.lifecycleLock) {
                if (currentGeneration == this.generation.get()) {
                    this.starting = false;
                }
            }
            Timber.d("Exiting work.");
        }
    }

    private void runVpn(int currentGeneration) throws IOException, VpnNetworkException {
        // Allocate the buffer for a single packet.
        byte[] packet = new byte[MAX_PACKET_SIZE];

        // Authenticate and configure the virtual network interface. The establishment lock
        // serializes the DNS mapper usage with a superseded worker which may still be establishing
        // its own tunnel.
        final ParcelFileDescriptor pfd;
        synchronized (this.establishLock) {
            if (currentGeneration != this.generation.get()) {
                return;
            }
            pfd = establish(this.vpnService, this.dnsServerMapper);
            if (pfd == null) {
                throw new VpnNetworkException("Failed to establish the VPN tunnel.");
            }
            if (!registerTunnel(currentGeneration, pfd)) {
                Timber.d("VPN worker was replaced while establishing its tunnel.");
                return;
            }
        }

        try (pfd;
             // Read and write views of the tunnel device
             FileInputStream inputStream = new FileInputStream(pfd.getFileDescriptor());
             FileOutputStream outputStream = new FileOutputStream(pfd.getFileDescriptor())) {
            if (currentGeneration != this.generation.get()) {
                return;
            }

            // Probe the fake DNS server address: it is routed into the tunnel, so a healthy tunnel
            // always hands the probe back to this worker (see VpnWatchdog)
            this.vpnWatchDog.setTarget(this.dnsServerMapper.getDefaultDnsServerAlias());

            // Now we are connected. Set the flag and show the message.
            this.vpnService.notifyVpnStatus(RUNNING);

            // We keep forwarding packets till something goes wrong.
            boolean deviceOpened = true;
            while (deviceOpened && currentGeneration == this.generation.get()) {
                deviceOpened = doOne(inputStream, outputStream, packet);
            }
            if (currentGeneration == this.generation.get()) {
                // The tunnel was closed without the worker being replaced: reconnect instead of
                // leaving the service running without any tunnel.
                throw new VpnNetworkException("VPN tunnel was closed.");
            }
        } finally {
            unregisterTunnel(currentGeneration);
        }
    }

    private boolean doOne(FileInputStream inputStream, FileOutputStream fileOutputStream, byte[] packet)
            throws IOException, VpnNetworkException {
        // Create poll FD on tunnel
        StructPollfd deviceFd = new StructPollfd();
        deviceFd.fd = inputStream.getFD();
        deviceFd.events = (short) POLLIN;
        if (!this.deviceWrites.isEmpty()) {
            deviceFd.events |= (short) POLLOUT;
        }
        // Create poll FD on each DNS query socket
        StructPollfd[] queryFds = this.dnsQueryQueue.getQueryFds();
        StructPollfd[] polls = new StructPollfd[1 + queryFds.length];
        polls[0] = deviceFd;
        System.arraycopy(queryFds, 0, polls, 1, queryFds.length);
        boolean deviceReadyToWrite;
        boolean deviceReadyToRead;
        try {
            Timber.d("doOne: Polling %d file descriptors.", polls.length);
            int numberOfEvents = Os.poll(polls, this.vpnWatchDog.getPollTimeout());
            // A poll timeout is handled by the watchdog: it probes the tunnel and throws once
            // several probes in a row did not come back.
            if (numberOfEvents == 0) {
                this.vpnWatchDog.handleTimeout();
                return true;
            }
            deviceReadyToWrite = (deviceFd.revents & POLLOUT) != 0;
            deviceReadyToRead = (deviceFd.revents & POLLIN) != 0;
        } catch (ErrnoException e) {
            throw new IOException("Failed to wait for event on file descriptors. Error number: " + e.errno, e);
        }

        // Need to do this before reading from the device, otherwise a new insertion there could
        // invalidate one of the sockets we want to read from either due to size or time out
        // constraints
        this.dnsQueryQueue.handleResponses();
        if (deviceReadyToWrite) {
            writeToDevice(fileOutputStream);
        }
        if (deviceReadyToRead) {
            return readPacketFromDevice(inputStream, packet) != -1;
        }
        return true;
    }

    private void writeToDevice(FileOutputStream fileOutputStream) throws IOException {
        Timber.d("Write to device %d packets.", this.deviceWrites.size());
        try {
            while (!this.deviceWrites.isEmpty()) {
                byte[] ipPacketData = this.deviceWrites.poll();
                fileOutputStream.write(ipPacketData);
            }
        } catch (IOException e) {
            throw new IOException("Failed to write to tunnel output stream.", e);
        }
    }

    private int readPacketFromDevice(FileInputStream inputStream, byte[] packet) throws IOException {
        Timber.d("Read a packet from device.");
        // Read the outgoing packet from the input stream.
        int length = inputStream.read(packet);
        if (length < 0) {
            // TODO Stream closed. Is there anything else to do?
            Timber.d("Tunnel input stream closed.");
        } else if (length == 0) {
            Timber.d("Read empty packet from tunnel.");
        } else {
            byte[] readPacket = Arrays.copyOf(packet, length);
            vpnWatchDog.handlePacket(readPacket);
            dnsPacketProxy.handleDnsRequest(readPacket);
        }
        return length;
    }

    @Override
    public void forwardPacket(DatagramPacket packet) throws IOException {
        try (DatagramSocket dnsSocket = new DatagramSocket()) {
            this.vpnService.protect(dnsSocket);
            dnsSocket.send(packet);
        } catch (IOException e) {
            throw new IOException("Failed to forward packet.", e);
        }
    }

    @Override
    public void forwardPacket(DatagramPacket outPacket, Consumer<byte[]> callback) throws IOException {
        DatagramSocket dnsSocket = null;
        try {
            dnsSocket = new DatagramSocket();
            // Packets to be sent to the real DNS server will need to be protected from the VPN
            this.vpnService.protect(dnsSocket);
            dnsSocket.send(outPacket);
            // Enqueue DNS query
            this.dnsQueryQueue.addQuery(dnsSocket, callback);
        } catch (IOException e) {
            if (dnsSocket != null) {
                dnsSocket.close();
            }
            if (e.getCause() instanceof ErrnoException) {
                ErrnoException errnoExc = (ErrnoException) e.getCause();
                if ((errnoExc.errno == ENETUNREACH) || (errnoExc.errno == EPERM)) {
                    throw new IOException("Cannot send message:", e);
                }
            }
            Timber.w(e, "handleDnsRequest: Could not send packet to upstream");
        }
    }

    @Override
    public void queueDeviceWrite(IpPacket ipOutPacket) {
        byte[] rawData = ipOutPacket.getRawData();
        // TODO Check why data could be null
        if (rawData != null) {
            this.deviceWrites.add(rawData);
        }
    }

    /**
     * An established tunnel, tagged with the generation and a unique identifier.
     */
    static final class Tunnel {
        final int generation;
        final long id;
        final ParcelFileDescriptor pfd;
        final NetworkInterface networkInterface;
        final DnsServerMapper dnsServerMapper;

        Tunnel(int generation, long id, ParcelFileDescriptor pfd,
               NetworkInterface networkInterface, DnsServerMapper dnsServerMapper) {
            this.generation = generation;
            this.id = id;
            this.pfd = pfd;
            this.networkInterface = networkInterface;
            this.dnsServerMapper = dnsServerMapper;
        }
    }
}
