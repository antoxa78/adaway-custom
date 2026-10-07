package org.adaway.ui.hosts;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.adaway.model.source.CatalogSource;

import java.util.Objects;

/**
 * This class represents an item displayed in the recommended sources catalog.<br>
 * An item is either a category header or a catalog source with its subscription state.
 *
 * @author Bruce BUJON (bruce.bujon(at)gmail(dot)com)
 */
public class CatalogListItem {
    /**
     * The item type.
     */
    public enum Type {
        /**
         * A category header.
         */
        HEADER,
        /**
         * A catalog source.
         */
        SOURCE
    }

    private final Type type;
    private final String header;
    private final CatalogSource source;
    private final boolean checked;
    private final boolean added;
    private final int size;

    private CatalogListItem(Type type, @Nullable String header, @Nullable CatalogSource source,
                            boolean checked, boolean added, int size) {
        this.type = type;
        this.header = header;
        this.source = source;
        this.checked = checked;
        this.added = added;
        this.size = size;
    }

    /**
     * Create a category header item.
     *
     * @param header The localized category name.
     * @return The header item.
     */
    public static CatalogListItem header(@NonNull String header) {
        return new CatalogListItem(Type.HEADER, header, null, false, false, 0);
    }

    /**
     * Create a catalog source item.
     *
     * @param source  The catalog source.
     * @param checked Whether the source is added and enabled.
     * @param added   Whether the source is in the hosts sources (enabled or not).
     * @param size    The number of hosts of the source, <code>0</code> if unknown or not added.
     * @return The source item.
     */
    public static CatalogListItem source(@NonNull CatalogSource source, boolean checked, boolean added, int size) {
        return new CatalogListItem(Type.SOURCE, null, source, checked, added, size);
    }

    public Type getType() {
        return this.type;
    }

    @Nullable
    public String getHeader() {
        return this.header;
    }

    @Nullable
    public CatalogSource getSource() {
        return this.source;
    }

    public boolean isChecked() {
        return this.checked;
    }

    public boolean isAdded() {
        return this.added;
    }

    public int getSize() {
        return this.size;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        CatalogListItem that = (CatalogListItem) o;
        return this.checked == that.checked
                && this.added == that.added
                && this.size == that.size
                && this.type == that.type
                && Objects.equals(this.header, that.header)
                && Objects.equals(this.source, that.source);
    }

    @Override
    public int hashCode() {
        return Objects.hash(this.type, this.header, this.source, this.checked, this.added, this.size);
    }
}
