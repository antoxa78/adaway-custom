package org.adaway.model.vpn;

import static org.adaway.model.adblocking.AdBlockMethod.VPN;
import static org.adaway.model.error.HostError.ENABLE_VPN_FAIL;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.util.LruCache;

import androidx.localbroadcastmanager.content.LocalBroadcastManager;

import org.adaway.R;
import org.adaway.db.AppDatabase;
import org.adaway.db.dao.BlockedStatDao;
import org.adaway.db.dao.HostEntryDao;
import org.adaway.db.entity.HostEntry;
import org.adaway.model.adblocking.AdBlockMethod;
import org.adaway.model.adblocking.AdBlockModel;
import org.adaway.model.adblocking.AdBlockState;
import org.adaway.model.error.HostErrorException;
import org.adaway.util.AppExecutors;
import org.adaway.vpn.VpnService;
import org.adaway.vpn.VpnServiceControls;
import org.adaway.vpn.VpnStatus;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import timber.log.Timber;

/**
 * This class is the model to represent VPN service configuration.
 *
 * @author Bruce BUJON (bruce.bujon(at)gmail(dot)com)
 */
public class VpnModel extends AdBlockModel {
    private final HostEntryDao hostEntryDao;
    private final BlockedStatDao blockedStatDao;
    private final LruCache<String, HostEntry> blockCache;
    private final LinkedHashSet<String> logs;
    private final BroadcastReceiver vpnStatusReceiver;
    /**
     * The domains already counted today (guarded by itself).
     */
    private final Set<String> blockedDomains;
    private boolean recordingLogs;
    private int requestCount;
    /**
     * The day {@link #blockedDomains} refers to ({@code null} if not initialized).
     */
    private String blockedDomainsDay;

    /**
     * Constructor.
     *
     * @param context The application context.
     */
    public VpnModel(Context context) {
        super(context);
        AppDatabase database = AppDatabase.getInstance(context);
        this.hostEntryDao = database.hostEntryDao();
        this.blockedStatDao = database.blockedStatDao();
        this.blockedDomains = new HashSet<>();
        this.blockedDomainsDay = null;
        this.blockCache = new LruCache<String, HostEntry>(4 * 1024) {
            @Override
            protected HostEntry create(String key) {
                return VpnModel.this.hostEntryDao.getEntry(key);
            }
        };
        this.logs = new LinkedHashSet<>();
        this.recordingLogs = false;
        this.requestCount = 0;
        setBlockingState(VpnServiceControls.isRunning(context) ? AdBlockState.RUNNING : AdBlockState.STOPPED);
        // Register VPN status receiver to update the blocking state according to the VPN service status
        this.vpnStatusReceiver = createVpnStatusReceiver();
        LocalBroadcastManager.getInstance(context).registerReceiver(
                this.vpnStatusReceiver,
                new IntentFilter(VpnService.VPN_UPDATE_STATUS_INTENT)
        );
    }

    private BroadcastReceiver createVpnStatusReceiver() {
        return new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                int statusCode = intent.getIntExtra(VpnService.VPN_UPDATE_STATUS_EXTRA, VpnStatus.STOPPED.toCode());
                VpnStatus vpnStatus = VpnStatus.fromCode(statusCode);
                Timber.d("VPN status update: %s.", vpnStatus);
                VpnModel.this.setBlockingState(toAdBlockState(vpnStatus));
            }
        };
    }

    /**
     * Convert a VPN status to the corresponding ad blocking state.
     *
     * @param status The VPN status to convert.
     * @return The corresponding ad blocking state.
     */
    private static AdBlockState toAdBlockState(VpnStatus status) {
        switch (status) {
            case STARTING:
                return AdBlockState.STARTING;
            case RUNNING:
                return AdBlockState.RUNNING;
            case WAITING_FOR_NETWORK:
                return AdBlockState.WAITING_FOR_NETWORK;
            case RECONNECTING:
            case RECONNECTING_NETWORK_ERROR:
                return AdBlockState.RECONNECTING;
            case STOPPING:
            case STOPPED:
            default:
                return AdBlockState.STOPPED;
        }
    }

    @Override
    public AdBlockMethod getMethod() {
        return VPN;
    }

    @Override
    public void apply() throws HostErrorException {
        // Clear cache
        this.blockCache.evictAll();
        // Start VPN
        boolean wasRunning = VpnServiceControls.isRunning(this.context);
        boolean started = VpnServiceControls.start(this.context);
        if (!started) {
            setBlockingState(AdBlockState.STOPPED);
            throw new HostErrorException(ENABLE_VPN_FAIL);
        }
        // Only report the starting state when the service was not already running: a start
        // command sent to a running service keeps its current status (waiting, reconnecting…).
        if (!wasRunning) {
            setBlockingState(AdBlockState.STARTING);
        }
        setState(R.string.status_vpn_configuration_updated);
    }

    @Override
    public void revert() {
        VpnServiceControls.stop(this.context);
        setBlockingState(AdBlockState.STOPPED);
    }

    @Override
    public void reconcile() {
        // The blocking state is normally pushed by VPN status broadcasts. If the service is
        // destroyed without emitting a STOPPED status (e.g. killed by the system), the last
        // value would stay active and the UI would keep displaying "running" with no tunnel.
        // Recompute it from the current service state to avoid such a stale display.
        if (!VpnServiceControls.isRunning(this.context)) {
            setBlockingState(AdBlockState.STOPPED);
        }
    }

    @Override
    public boolean isRecordingLogs() {
        return this.recordingLogs;
    }

    @Override
    public void setRecordingLogs(boolean recording) {
        this.recordingLogs = recording;
    }

    @Override
    public List<String> getLogs() {
        return new ArrayList<>(this.logs);
    }

    @Override
    public void clearLogs() {
        this.logs.clear();
    }

    /**
     * Record a blocked domain for the statistics.
     * <p>
     * Domains are deduplicated in memory for the current day, so the database is only written the
     * first time a domain is blocked during the day. The database ledger is persistent, which
     * makes the distinct count correct even across application restarts.
     *
     * @param hostname The blocked domain.
     */
    public void recordBlocked(String hostname) {
        String day = LocalDate.now().toString();
        String domain = hostname.toLowerCase(Locale.ENGLISH);
        synchronized (this.blockedDomains) {
            if (!day.equals(this.blockedDomainsDay)) {
                // Day changed: start a new in-memory ledger and prune the previous days
                this.blockedDomains.clear();
                this.blockedDomainsDay = day;
                AppExecutors.getInstance().diskIO().execute(() -> this.blockedStatDao.pruneDomainsBefore(day));
            }
            if (!this.blockedDomains.add(domain)) {
                // Already counted today
                return;
            }
        }
        // Persist outside of the DNS worker thread, once per distinct domain and not per query
        AppExecutors.getInstance().diskIO().execute(() -> this.blockedStatDao.recordBlocked(day, domain));
    }

    /**
     * Checks host entry related to an host name.
     *
     * @param host A hostname to check.
     * @return The related host entry.
     */
    public HostEntry getEntry(String host) {
        // Compute miss rate periodically
        this.requestCount++;
        if (this.requestCount >= 1000) {
            int hits = this.blockCache.hitCount();
            int misses = this.blockCache.missCount();
            double missRate = 100D * (hits + misses) / misses;
            Timber.d("Host cache miss rate: %s.", missRate);
            this.requestCount = 0;
        }
        // Add host to logs
        if (this.recordingLogs) {
            this.logs.add(host);
        }
        // Check cache
        return this.blockCache.get(host);
    }
}
