package org.adaway.model.source;

import androidx.annotation.NonNull;
import androidx.annotation.StringRes;

import java.util.Objects;

/**
 * This class represents a recommended hosts source proposed in the source catalog.
 * A catalog source is identified by its URL and is only a template used to create a
 * {@link org.adaway.db.entity.HostsSource} when the user subscribes to it.
 *
 * @author Bruce BUJON (bruce.bujon(at)gmail(dot)com)
 */
public class CatalogSource {
    /**
     * The source URL, used as unique identifier.
     */
    private final String url;
    /**
     * The label string resource.
     */
    @StringRes
    private final int labelRes;
    /**
     * The description string resource.
     */
    @StringRes
    private final int descriptionRes;
    /**
     * The category string resource, used to group sources.
     */
    @StringRes
    private final int categoryRes;

    /**
     * Constructor.
     *
     * @param url            The source URL.
     * @param labelRes       The label string resource.
     * @param descriptionRes The description string resource.
     * @param categoryRes    The category string resource.
     */
    public CatalogSource(@NonNull String url, @StringRes int labelRes, @StringRes int descriptionRes, @StringRes int categoryRes) {
        this.url = url;
        this.labelRes = labelRes;
        this.descriptionRes = descriptionRes;
        this.categoryRes = categoryRes;
    }

    @NonNull
    public String getUrl() {
        return this.url;
    }

    @StringRes
    public int getLabelRes() {
        return this.labelRes;
    }

    @StringRes
    public int getDescriptionRes() {
        return this.descriptionRes;
    }

    @StringRes
    public int getCategoryRes() {
        return this.categoryRes;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        CatalogSource that = (CatalogSource) o;
        return this.url.equals(that.url);
    }

    @Override
    public int hashCode() {
        return Objects.hash(this.url);
    }
}
