package org.adaway.ui.hosts;

import android.app.Application;
import android.content.Context;

import androidx.annotation.NonNull;
import androidx.lifecycle.AndroidViewModel;
import androidx.lifecycle.LiveData;
import androidx.lifecycle.Transformations;

import org.adaway.db.AppDatabase;
import org.adaway.db.dao.HostsSourceDao;
import org.adaway.db.entity.HostsSource;
import org.adaway.model.source.CatalogSource;
import org.adaway.model.source.CatalogSources;
import org.adaway.util.AppExecutors;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executor;

/**
 * This class is the {@link AndroidViewModel} for the {@link RecommendedSourcesFragment}.<br>
 * It combines the static catalog with the currently subscribed hosts sources to expose the
 * subscription state of each catalog entry.
 *
 * @author Bruce BUJON (bruce.bujon(at)gmail(dot)com)
 */
public class RecommendedSourcesViewModel extends AndroidViewModel {
    private static final Executor DISK_IO_EXECUTOR = AppExecutors.getInstance().diskIO();

    private final HostsSourceDao hostsSourceDao;
    private final LiveData<List<HostsSource>> hostsSources;
    private final LiveData<List<CatalogListItem>> items;

    public RecommendedSourcesViewModel(@NonNull Application application) {
        super(application);
        this.hostsSourceDao = AppDatabase.getInstance(application).hostsSourceDao();
        this.hostsSources = this.hostsSourceDao.loadAll();
        this.items = Transformations.map(this.hostsSources, this::buildItems);
    }

    /**
     * Get the hosts sources, to notify when the configuration changed and must be applied.
     *
     * @return The hosts sources.
     */
    public LiveData<List<HostsSource>> getHostsSources() {
        return this.hostsSources;
    }

    /**
     * Get the catalog items with their subscription state.
     *
     * @return The catalog items.
     */
    public LiveData<List<CatalogListItem>> getItems() {
        return this.items;
    }

    /**
     * Subscribe or unsubscribe to a catalog source.
     *
     * @param catalogSource The catalog source to update.
     * @param subscribed    Whether the source should be subscribed.
     */
    public void setSubscribed(CatalogSource catalogSource, boolean subscribed) {
        DISK_IO_EXECUTOR.execute(() -> {
            String url = catalogSource.getUrl();
            if (subscribed) {
                // Create the source if it does not exist yet (ignore if already present)
                Context context = getApplication();
                HostsSource source = new HostsSource();
                source.setLabel(context.getString(catalogSource.getLabelRes()));
                source.setUrl(url);
                this.hostsSourceDao.insert(source);
                // Make sure the source and its items are enabled
                this.hostsSourceDao.setSourceEnabledByUrl(url, true);
                this.hostsSourceDao.setSourceItemsEnabledByUrl(url, true);
            } else {
                // Disable the source instead of deleting it: a checked entry means an enabled
                // source, and the user settings of the source (label, etc.) are kept
                this.hostsSourceDao.setSourceEnabledByUrl(url, false);
                this.hostsSourceDao.setSourceItemsEnabledByUrl(url, false);
            }
        });
    }

    private List<CatalogListItem> buildItems(List<HostsSource> sources) {
        // Index the added sources by URL (a disabled source shows unchecked so it can be re-enabled)
        Map<String, HostsSource> sourcesByUrl = new HashMap<>();
        for (HostsSource source : sources) {
            sourcesByUrl.put(source.getUrl(), source);
        }
        // Build items with category headers
        Context context = getApplication();
        List<CatalogListItem> items = new ArrayList<>();
        int currentCategory = 0;
        for (CatalogSource source : CatalogSources.getSources()) {
            if (source.getCategoryRes() != currentCategory) {
                currentCategory = source.getCategoryRes();
                items.add(CatalogListItem.header(context.getString(currentCategory)));
            }
            HostsSource added = sourcesByUrl.get(source.getUrl());
            items.add(CatalogListItem.source(
                    source,
                    added != null && added.isEnabled(),
                    added != null,
                    added == null ? 0 : added.getSize()
            ));
        }
        return items;
    }
}
