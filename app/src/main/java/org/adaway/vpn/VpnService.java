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
package org.adaway.vpn;

import static android.Manifest.permission.POST_NOTIFICATIONS;
import static android.app.PendingIntent.FLAG_IMMUTABLE;
import static android.content.Intent.FLAG_ACTIVITY_CLEAR_TASK;
import static android.content.Intent.FLAG_ACTIVITY_NEW_TASK;
import static android.content.pm.PackageManager.PERMISSION_GRANTED;
import static android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE;
import static android.net.NetworkCapabilities.TRANSPORT_CELLULAR;
import static android.net.NetworkCapabilities.TRANSPORT_VPN;
import static android.net.NetworkCapabilities.TRANSPORT_WIFI;
import static android.os.Build.VERSION.SDK_INT;
import static android.os.Build.VERSION_CODES.UPSIDE_DOWN_CAKE;
import static org.adaway.broadcast.Command.START;
import static org.adaway.broadcast.Command.STOP;
import static org.adaway.broadcast.CommandReceiver.SEND_COMMAND_ACTION;
import static org.adaway.helper.NotificationHelper.VPN_RESUME_SERVICE_NOTIFICATION_ID;
import static org.adaway.helper.NotificationHelper.VPN_RUNNING_SERVICE_NOTIFICATION_ID;
import static org.adaway.helper.NotificationHelper.VPN_SERVICE_NOTIFICATION_CHANNEL;
import static org.adaway.vpn.VpnService.NetworkType.CELLULAR;
import static org.adaway.vpn.VpnService.NetworkType.WIFI;
import static org.adaway.vpn.VpnStatus.RECONNECTING;
import static org.adaway.vpn.VpnStatus.RUNNING;
import static org.adaway.vpn.VpnStatus.STARTING;
import static org.adaway.vpn.VpnStatus.STOPPED;
import static org.adaway.vpn.VpnStatus.WAITING_FOR_NETWORK;
import static java.util.Objects.requireNonNull;

import android.app.Notification;
import android.app.PendingIntent;
import android.content.Intent;
import android.net.ConnectivityManager;
import android.net.ConnectivityManager.NetworkCallback;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkRequest;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import android.view.View;
import android.widget.RemoteViews;

import androidx.annotation.ColorRes;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;
import androidx.core.app.NotificationManagerCompat;
import androidx.localbroadcastmanager.content.LocalBroadcastManager;

import org.adaway.R;
import org.adaway.broadcast.Command;
import org.adaway.broadcast.CommandReceiver;
import org.adaway.helper.PreferenceHelper;
import org.adaway.ui.home.HomeActivity;
import org.adaway.vpn.worker.VpnWorker;

import java.lang.ref.WeakReference;
import java.util.HashSet;
import java.util.Set;

import timber.log.Timber;

/**
 * This class is the VPN platform service implementation.
 * <p>
 * it is in charge of:
 * <ul>
 * <li>Accepting service commands,</li>
 * <li>Starting / stopping the {@link VpnWorker} thread,</li>
 * <li>Publishing notifications and intent about the VPN state,</li>
 * <li>Reacting to network connectivity changes.</li>
 * </ul>
 *
 * @author Bruce BUJON (bruce.bujon(at)gmail(dot)com)
 */
public class VpnService extends android.net.VpnService implements Handler.Callback {
    public static final String VPN_UPDATE_STATUS_INTENT = "org.jak_linux.dns66.VPN_UPDATE_STATUS";
    public static final String VPN_UPDATE_STATUS_EXTRA = "VPN_STATUS";
    /*
     * Notification intent related.
     */
    private static final int REQUEST_CODE_START = 43;
    private static final int REQUEST_CODE_PAUSE = 42;
    /*
     * Handler related.
     */
    private static final int VPN_STATUS_UPDATE_MESSAGE_TYPE = 0;

    private final MyHandler handler;
    private final NetworkTypeCallback wifiNetworkCallback;
    private final NetworkTypeCallback cellularNetworkCallback;
    /**
     * The available (Wi-Fi or cellular) networks.<br>
     * Networks are tracked by identity so a network reported twice (once by the initial check,
     * once by the callback registration) is only counted once.
     */
    private final Set<Network> availableNetworks;
    private final VpnWorker vpnWorker;

    /**
     * Constructor.
     */
    public VpnService() {
        this.handler = new MyHandler(this);
        this.wifiNetworkCallback = new NetworkTypeCallback(WIFI);
        this.cellularNetworkCallback = new NetworkTypeCallback(CELLULAR);
        this.availableNetworks = new HashSet<>();
        this.vpnWorker = new VpnWorker(this);
    }

    /*
     * VPN Service.
     */

    @Override
    public void onCreate() {
        Timber.d("Creating VPN service…");
        registerNetworkCallback();
    }

    @Override
    public int onStartCommand(@Nullable Intent intent, int flags, int startId) {
        Timber.d("onStartCommand %s", intent == null ? "null intent" : intent);
        // Check null intent that happens when system restart the service
        // https://developer.android.com/reference/android/app/Service#START_STICKY
        Command command = intent == null ?
                START :
                Command.readFromIntent(intent);
        switch (command) {
            case START:
                startVpn();
                return START_STICKY;
            case STOP:
                stopVpn();
                return START_NOT_STICKY;
            default:
                Timber.w("Unknown command: %s", command);
                return START_NOT_STICKY;
        }
    }

    @Override
    public void onDestroy() {
        Timber.d("Destroying VPN service…");
        unregisterNetworkCallback();
        Timber.d("Destroyed VPN service.");
    }

    @Override
    public void onRevoke() {
        Timber.w("VPN service revoked.");
        // Stop the current tunnel
        this.vpnWorker.stop();
        // If the application is still the prepared VPN owner, restart the tunnel right away
        Intent prepareIntent = VpnService.prepare(this);
        if (prepareIntent == null) {
            Timber.i("Restarting revoked VPN service…");
            startVpn();
        } else {
            Timber.w("VPN preparation revoked, waiting for user to re-authorize.");
            // Fully stop the service (status, foreground notification and service itself),
            // as the default onRevoke() implementation would do
            stopVpn();
        }
    }

    /*
     * Handler callback.
     */

    @Override
    public boolean handleMessage(@NonNull Message message) {
        if (message.what == VPN_STATUS_UPDATE_MESSAGE_TYPE) {
            updateVpnStatus(VpnStatus.fromCode(message.arg1));
        }
        return true;
    }

    /**
     * Notify a of the new VPN status.
     *
     * @param status The new VPN status.
     */
    public void notifyVpnStatus(VpnStatus status) {
        Message statusMessage = this.handler.obtainMessage(VPN_STATUS_UPDATE_MESSAGE_TYPE, status.toCode(), 0);
        this.handler.sendMessage(statusMessage);
    }

    private void startVpn() {
        Timber.d("Starting VPN service…");
        PreferenceHelper.setVpnServiceStatus(this, RUNNING);
        updateVpnStatus(STARTING);
        // Stop any previous worker first: a start command may be sent to an already running
        // service to rebuild a broken tunnel (see VpnServiceControls.restart()).
        this.vpnWorker.stop();
        this.vpnWorker.start();
        Timber.i("VPN service started.");
    }

    private void stopVpn() {
        Timber.d("Stopping VPN service…");
        PreferenceHelper.setVpnServiceStatus(this, STOPPED);
        this.vpnWorker.stop();
        stopForeground(true);
        stopSelf();
        updateVpnStatus(STOPPED);
        Timber.i("VPN service stopped.");
    }

    private void waitForNetVpn() {
        this.vpnWorker.stop();
        updateVpnStatus(WAITING_FOR_NETWORK);
    }

    private void reconnect() {
        updateVpnStatus(RECONNECTING);
        // Stop the previous worker before starting a new one so two tunnels do not fight
        // over the VPN interface when the network changes.
        this.vpnWorker.stop();
        this.vpnWorker.start();
    }

    private void updateVpnStatus(VpnStatus status) {
        Notification notification = getNotification(status);
        NotificationManagerCompat notificationManager = NotificationManagerCompat.from(this);
        switch (status) {
            case STARTING:
            case RUNNING:
                notificationManager.cancel(VPN_RESUME_SERVICE_NOTIFICATION_ID);
                if (SDK_INT >= UPSIDE_DOWN_CAKE) {
                    startForeground(VPN_RUNNING_SERVICE_NOTIFICATION_ID, notification, FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
                } else {
                    startForeground(VPN_RUNNING_SERVICE_NOTIFICATION_ID, notification);
                }
                break;
            default:
                if (checkSelfPermission(POST_NOTIFICATIONS) == PERMISSION_GRANTED) {
                    notificationManager.notify(VPN_RESUME_SERVICE_NOTIFICATION_ID, notification);
                }
        }

        Intent intent = new Intent(VPN_UPDATE_STATUS_INTENT);
        intent.putExtra(VPN_UPDATE_STATUS_EXTRA, status.toCode());
        LocalBroadcastManager.getInstance(this).sendBroadcast(intent);
    }

    private Notification getNotification(VpnStatus status) {
        String title = getString(R.string.vpn_notification_title, getString(status.getTextResource()));

        Intent intent = new Intent(getApplicationContext(), HomeActivity.class);
        intent.setFlags(FLAG_ACTIVITY_NEW_TASK | FLAG_ACTIVITY_CLEAR_TASK);
        PendingIntent contentIntent = PendingIntent.getActivity(getApplicationContext(), 0, intent, FLAG_IMMUTABLE);

        // Status color: green when blocking, amber while (re)connecting or waiting, red when stopped
        int statusColor = getColor(getStatusColor(status));
        String text = getStatusText(status);
        // Custom content view to color the notification title with the status color. The system
        // header above it already shows the (tinted) small icon and the application name.
        RemoteViews contentView = new RemoteViews(getPackageName(), R.layout.notification_vpn);
        contentView.setTextViewText(R.id.notificationTitle, title);
        contentView.setTextColor(R.id.notificationTitle, statusColor);
        if (text == null) {
            contentView.setViewVisibility(R.id.notificationText, View.GONE);
        } else {
            contentView.setTextViewText(R.id.notificationText, text);
            contentView.setViewVisibility(R.id.notificationText, View.VISIBLE);
        }
        contentView.setOnClickPendingIntent(R.id.notificationContent, contentIntent);

        NotificationCompat.Builder builder = new NotificationCompat.Builder(this, VPN_SERVICE_NOTIFICATION_CHANNEL)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .setContentIntent(contentIntent)
                .setSmallIcon(R.drawable.ic_notification)
                .setColor(statusColor)
                .setContentTitle(title)
                .setContentText(text)
                .setShowWhen(false)
                .setCustomContentView(contentView)
                .setStyle(new NotificationCompat.DecoratedCustomViewStyle());
        switch (status) {
            case RUNNING:
                Intent stopIntent = new Intent(this, CommandReceiver.class)
                        .setAction(SEND_COMMAND_ACTION);
                STOP.appendToIntent(stopIntent);
                PendingIntent stopActionIntent = PendingIntent.getBroadcast(this, REQUEST_CODE_PAUSE, stopIntent, FLAG_IMMUTABLE);
                builder.addAction(
                        R.drawable.ic_pause_24dp,
                        getString(R.string.vpn_notification_action_pause),
                        stopActionIntent
                ).setOngoing(true);
                break;
            case STOPPED:
                Intent startIntent = new Intent(this, CommandReceiver.class)
                        .setAction(SEND_COMMAND_ACTION);
                START.appendToIntent(startIntent);
                PendingIntent startActionIntent = PendingIntent.getBroadcast(this, REQUEST_CODE_START, startIntent, FLAG_IMMUTABLE);
                builder.addAction(
                        0,
                        getString(R.string.vpn_notification_action_resume),
                        startActionIntent
                );
                break;
        }
        return builder.build();
    }

    @ColorRes
    private static int getStatusColor(VpnStatus status) {
        switch (status) {
            case RUNNING:
                return R.color.adblock_active;
            case STOPPED:
                return R.color.adblock_stopped;
            default:
                return R.color.adblock_pending;
        }
    }

    @Nullable
    private String getStatusText(VpnStatus status) {
        switch (status) {
            case STARTING:
                return getString(R.string.vpn_notification_text_starting);
            case RUNNING:
                return getString(R.string.vpn_notification_text_running);
            case WAITING_FOR_NETWORK:
                return getString(R.string.vpn_notification_text_waiting_for_net);
            case RECONNECTING:
            case RECONNECTING_NETWORK_ERROR:
                return getString(R.string.vpn_notification_text_reconnecting);
            case STOPPED:
                return getString(R.string.vpn_notification_text_stopped);
            default:
                return null;
        }
    }

    private void registerNetworkCallback() {
        ConnectivityManager connectivityManager = (ConnectivityManager) getSystemService(CONNECTIVITY_SERVICE);
        NetworkRequest wifiNetworkRequest = new NetworkRequest.Builder()
                .addTransportType(TRANSPORT_WIFI)
                .build();
        NetworkRequest cellularNetworkRequest = new NetworkRequest.Builder()
                .addTransportType(TRANSPORT_CELLULAR)
                .build();
        initializeNetworkTypes(connectivityManager);
        connectivityManager.registerNetworkCallback(wifiNetworkRequest, this.wifiNetworkCallback, this.handler);
        connectivityManager.registerNetworkCallback(cellularNetworkRequest, this.cellularNetworkCallback, this.handler);

    }

    private void unregisterNetworkCallback() {
        ConnectivityManager connectivityManager = (ConnectivityManager) getSystemService(CONNECTIVITY_SERVICE);
        connectivityManager.unregisterNetworkCallback(this.wifiNetworkCallback);
        connectivityManager.unregisterNetworkCallback(this.cellularNetworkCallback);
    }

    private void initializeNetworkTypes(ConnectivityManager connectivityManager) {
        this.availableNetworks.clear();
        Network activeNetwork = connectivityManager.getActiveNetwork();
        if (activeNetwork != null) {
            NetworkCapabilities networkCapabilities = connectivityManager.getNetworkCapabilities(activeNetwork);
            if (networkCapabilities != null
                    && !networkCapabilities.hasTransport(TRANSPORT_VPN)
                    && (networkCapabilities.hasTransport(TRANSPORT_WIFI) || networkCapabilities.hasTransport(TRANSPORT_CELLULAR))) {
                this.availableNetworks.add(activeNetwork);
            }
        }
        Timber.d("Initial networks: %s ", this.availableNetworks);
    }

    private void addNetwork(Network network, NetworkType type) {
        boolean noNetwork = this.availableNetworks.isEmpty();
        // The callback registration reports already connected networks again: ignore duplicates
        boolean added = this.availableNetworks.add(network);
        if (added && noNetwork) {
            Timber.d("Reconnecting VPN on network %s (%s).", network, type);
            reconnect();
        }
    }

    private void removeNetwork(Network network, NetworkType type) {
        // Only act when the LAST network is gone: losing one network while another remains
        // (dual-band Wi-Fi links, or a cellular blip while Wi-Fi is the actual carrier) must not
        // stop or restart the VPN. If the real carrier actually changed, the worker self-heals by
        // reconnecting on the first failed DNS forward (ENETUNREACH) and re-maps the DNS servers
        // with {@link org.adaway.vpn.dns.DnsServerMapper}.
        boolean removed = this.availableNetworks.remove(network);
        if (removed && this.availableNetworks.isEmpty()) {
            Timber.d("Lost last network %s (%s). Waiting for network…", network, type);
            waitForNetVpn();
        }
    }

    /**
     * This class receives network change events to monitor network type available.
     *
     * @author Bruce BUJON (bruce.bujon(at)gmail(dot)com)
     * @see <a href="https://developer.android.com/training/basics/network-ops/reading-network-state#listening-events">Android Developer Documentation</a>
     */
    private class NetworkTypeCallback extends NetworkCallback {
        private final NetworkType monitoredType;

        NetworkTypeCallback(NetworkType monitoredType) {
            this.monitoredType = monitoredType;
        }

        @Override
        public void onAvailable(@NonNull Network network) {
            Timber.d("On available %s", this.monitoredType);
            addNetwork(network, this.monitoredType);
        }

        @Override
        public void onLost(@NonNull Network network) {
            Timber.d("On lost %s", this.monitoredType);
            removeNetwork(network, this.monitoredType);
        }
    }

    enum NetworkType {
        CELLULAR,
        WIFI,
    }

    /* The handler may only keep a weak reference around, otherwise it leaks */
    private static class MyHandler extends Handler {

        private final WeakReference<Callback> callback;

        MyHandler(Callback callback) {
            super(requireNonNull(Looper.myLooper()));
            this.callback = new WeakReference<>(callback);
        }

        @Override
        public void handleMessage(@NonNull Message msg) {
            Callback callback = this.callback.get();
            if (callback != null) {
                callback.handleMessage(msg);
            }
            super.handleMessage(msg);
        }
    }
}
