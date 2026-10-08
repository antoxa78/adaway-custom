package org.adaway.ui.home;

import static android.content.Context.CONNECTIVITY_SERVICE;
import static android.net.NetworkCapabilities.NET_CAPABILITY_INTERNET;
import static android.net.NetworkCapabilities.TRANSPORT_CELLULAR;
import static android.net.NetworkCapabilities.TRANSPORT_VPN;
import static android.net.NetworkCapabilities.TRANSPORT_WIFI;

import android.app.Application;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkRequest;

import androidx.annotation.NonNull;
import androidx.lifecycle.AndroidViewModel;
import androidx.lifecycle.LiveData;
import androidx.lifecycle.MediatorLiveData;
import androidx.lifecycle.MutableLiveData;
import androidx.lifecycle.Transformations;

import org.adaway.AdAwayApplication;
import org.adaway.db.AppDatabase;
import org.adaway.db.dao.HostListItemDao;
import org.adaway.db.dao.HostsSourceDao;
import org.adaway.model.adblocking.AdBlockModel;
import org.adaway.model.error.HostError;
import org.adaway.model.error.HostErrorException;
import org.adaway.model.source.SourceModel;
import org.adaway.model.update.Manifest;
import org.adaway.model.update.UpdateModel;
import org.adaway.util.AppExecutors;
import org.adaway.util.DateTimeUtils;

import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.Collections;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import timber.log.Timber;

/**
 * This class is an {@link AndroidViewModel} for the {@link HomeActivity} cards.
 *
 * @author Bruce BUJON (bruce.bujon(at)gmail(dot)com)
 */
public class HomeViewModel extends AndroidViewModel {
    private static final AppExecutors EXECUTORS = AppExecutors.getInstance();

    private final SourceModel sourceModel;
    private final AdBlockModel adBlockModel;
    private final UpdateModel updateModel;

    private final HostsSourceDao hostsSourceDao;
    private final HostListItemDao hostListItemDao;

    private final MutableLiveData<Boolean> pending;
    private final MediatorLiveData<String> state;
    private final MutableLiveData<HostError> error;
    private final MutableLiveData<Boolean> networkAvailable;
    private final Set<Network> availableNetworks;
    private final ConnectivityManager connectivityManager;
    private final ConnectivityManager.NetworkCallback networkCallback;
    private boolean networkCallbackRegistered;

    public HomeViewModel(@NonNull Application application) {
        super(application);
        AdAwayApplication awayApplication = (AdAwayApplication) application;
        this.sourceModel = awayApplication.getSourceModel();
        this.adBlockModel = awayApplication.getAdBlockModel();
        this.updateModel = awayApplication.getUpdateModel();

        AppDatabase database = AppDatabase.getInstance(application);
        this.hostsSourceDao = database.hostsSourceDao();
        this.hostListItemDao = database.hostsListItemDao();

        this.pending = new MutableLiveData<>(false);
        this.state = new MediatorLiveData<>();
        this.state.addSource(this.sourceModel.getState(), this.state::setValue);
        this.state.addSource(this.adBlockModel.getState(), this.state::setValue);
        this.error = new MutableLiveData<>();

        // Track the underlying networks to report whether a network is available
        this.networkAvailable = new MutableLiveData<>(true);
        this.availableNetworks = Collections.newSetFromMap(new ConcurrentHashMap<>());
        this.connectivityManager = (ConnectivityManager) application.getSystemService(CONNECTIVITY_SERVICE);
        NetworkRequest networkRequest = new NetworkRequest.Builder()
                .addCapability(NET_CAPABILITY_INTERNET)
                .addTransportType(TRANSPORT_WIFI)
                .addTransportType(TRANSPORT_CELLULAR)
                .build();
        this.networkCallback = new ConnectivityManager.NetworkCallback() {
            @Override
            public void onAvailable(@NonNull Network network) {
                HomeViewModel.this.availableNetworks.add(network);
                HomeViewModel.this.updateNetworkAvailable();
            }

            @Override
            public void onLost(@NonNull Network network) {
                HomeViewModel.this.availableNetworks.remove(network);
                HomeViewModel.this.updateNetworkAvailable();
            }

            @Override
            public void onUnavailable() {
                HomeViewModel.this.updateNetworkAvailable();
            }
        };
        try {
            this.connectivityManager.registerNetworkCallback(networkRequest, this.networkCallback);
            this.networkCallbackRegistered = true;
        } catch (RuntimeException e) {
            // Keep the initial availability computed from the current networks
            Timber.w(e, "Failed to register the network callback.");
        }
        // Initialize the availability from the current networks: the callback only reports changes
        for (Network network : this.connectivityManager.getAllNetworks()) {
            NetworkCapabilities capabilities = this.connectivityManager.getNetworkCapabilities(network);
            if (capabilities != null
                    && capabilities.hasCapability(NET_CAPABILITY_INTERNET)
                    && !capabilities.hasTransport(TRANSPORT_VPN)
                    && (capabilities.hasTransport(TRANSPORT_WIFI) || capabilities.hasTransport(TRANSPORT_CELLULAR))) {
                this.availableNetworks.add(network);
            }
        }
        updateNetworkAvailable();
    }

    private static boolean isTrue(LiveData<Boolean> liveData) {
        Boolean value = liveData.getValue();
        return value != null && value;
    }

    public LiveData<Boolean> isAdBlocked() {
        return this.adBlockModel.isApplied();
    }

    /**
     * Get whether a network is available.
     *
     * @return {@code true} if a network is available, {@code false} otherwise.
     */
    public LiveData<Boolean> isNetworkAvailable() {
        return this.networkAvailable;
    }

    private void updateNetworkAvailable() {
        this.networkAvailable.postValue(!this.availableNetworks.isEmpty());
    }

    public LiveData<Boolean> isUpdateAvailable() {
        return this.sourceModel.isUpdateAvailable();
    }

    public String getVersionName() {
        return this.updateModel.getVersionName();
    }

    public LiveData<Manifest> getAppManifest() {
        return this.updateModel.getManifest();
    }

    public LiveData<Integer> getBlockedHostCount() {
        return this.hostListItemDao.getBlockedHostCount();
    }

    public LiveData<Integer> getAllowedHostCount() {
        return this.hostListItemDao.getAllowedHostCount();
    }

    public LiveData<Integer> getRedirectHostCount() {
        return this.hostListItemDao.getRedirectHostCount();
    }

    public LiveData<Integer> getUpToDateSourceCount() {
        return this.hostsSourceDao.countUpToDate();
    }

    public LiveData<Integer> getOutdatedSourceCount() {
        return this.hostsSourceDao.countOutdated();
    }

    public LiveData<Boolean> getPending() {
        return this.pending;
    }

    public LiveData<String> getState() {
        return this.state;
    }

    /**
     * Get the date and time of the last sources update ({@code null} if no source was ever updated).
     *
     * @return The formatted date and time of the last sources update.
     */
    public LiveData<String> getLastSourceUpdate() {
        return Transformations.map(this.hostsSourceDao.getLastSourceUpdateTimestamp(), timestamp -> {
            if (timestamp == null || timestamp <= 0) {
                return null;
            }
            ZonedDateTime dateTime = ZonedDateTime.ofInstant(
                    Instant.ofEpochSecond(timestamp),
                    ZoneId.systemDefault()
            );
            return DateTimeUtils.formatDateTime(getApplication(), dateTime);
        });
    }

    public LiveData<HostError> getError() {
        return this.error;
    }

    public void checkForAppUpdate() {
        EXECUTORS.networkIO().execute(this.updateModel::checkForUpdate);
    }

    public void toggleAdBlocking() {
        if (isTrue(this.pending)) {
            return;
        }
        EXECUTORS.diskIO().execute(() -> {
            try {
                this.pending.postValue(true);
                if (isTrue(this.adBlockModel.isApplied())) {
                    this.adBlockModel.revert();
                } else {
                    this.adBlockModel.apply();
                }
            } catch (HostErrorException exception) {
                Timber.w(exception, "Failed to toggle ad blocking.");
                this.error.postValue(exception.getError());
            } finally {
                this.pending.postValue(false);
            }
        });
    }

    public void update() {
        if (isTrue(this.pending)) {
            return;
        }
        EXECUTORS.networkIO().execute(() -> {
            try {
                this.pending.postValue(true);
                this.sourceModel.checkForUpdate();
            } catch (HostErrorException exception) {
                Timber.w(exception, "Failed to update.");
                this.error.postValue(exception.getError());
            } finally {
                this.pending.postValue(false);
            }
        });
    }

    public void sync() {
        if (isTrue(this.pending)) {
            return;
        }
        EXECUTORS.networkIO().execute(() -> {
            try {
                this.pending.postValue(true);
                this.sourceModel.retrieveHostsSources();
                this.adBlockModel.apply();
            } catch (HostErrorException exception) {
                Timber.w(exception, "Failed to sync.");
                this.error.postValue(exception.getError());
            } finally {
                this.pending.postValue(false);
            }
        });
    }

    public void enableAllSources() {
        EXECUTORS.diskIO().execute(() -> {
            if (this.sourceModel.enableAllSources()) {
                sync();
            }
        });
    }

    @Override
    protected void onCleared() {
        super.onCleared();
        if (this.networkCallbackRegistered) {
            this.connectivityManager.unregisterNetworkCallback(this.networkCallback);
        }
    }
}
