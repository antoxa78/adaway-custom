package org.adaway.ui.hosts;

import android.content.Context;
import android.content.res.ColorStateList;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.DiffUtil;
import androidx.recyclerview.widget.ListAdapter;
import androidx.recyclerview.widget.RecyclerView;

import org.adaway.R;
import org.adaway.databinding.RecommendedSourcesHeaderBinding;
import org.adaway.databinding.RecommendedSourcesItemBinding;
import org.adaway.model.source.CatalogSource;

import java.text.NumberFormat;

/**
 * This class is the {@link RecyclerView.Adapter} for the recommended sources catalog.<br>
 * It displays category headers and catalog sources with a check box reflecting the
 * subscription state.
 *
 * @author Bruce BUJON (bruce.bujon(at)gmail(dot)com)
 */
class RecommendedSourcesAdapter extends ListAdapter<CatalogListItem, RecyclerView.ViewHolder> {
    /**
     * This callback is used to compare catalog items.
     */
    private static final DiffUtil.ItemCallback<CatalogListItem> DIFF_CALLBACK =
            new DiffUtil.ItemCallback<>() {
                @Override
                public boolean areItemsTheSame(@NonNull CatalogListItem oldItem, @NonNull CatalogListItem newItem) {
                    if (oldItem.getType() != newItem.getType()) {
                        return false;
                    }
                    if (oldItem.getType() == CatalogListItem.Type.HEADER) {
                        return oldItem.getHeader().equals(newItem.getHeader());
                    }
                    return oldItem.getSource().equals(newItem.getSource());
                }

                @Override
                public boolean areContentsTheSame(@NonNull CatalogListItem oldItem, @NonNull CatalogListItem newItem) {
                    return oldItem.equals(newItem);
                }
            };

    private final Callback callback;

    RecommendedSourcesAdapter(@NonNull Callback callback) {
        super(DIFF_CALLBACK);
        this.callback = callback;
    }

    @Override
    public int getItemViewType(int position) {
        return getItem(position).getType().ordinal();
    }

    @NonNull
    @Override
    public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        LayoutInflater layoutInflater = LayoutInflater.from(parent.getContext());
        if (viewType == CatalogListItem.Type.HEADER.ordinal()) {
            RecommendedSourcesHeaderBinding binding = RecommendedSourcesHeaderBinding.inflate(layoutInflater, parent, false);
            return new HeaderViewHolder(binding);
        }
        RecommendedSourcesItemBinding binding = RecommendedSourcesItemBinding.inflate(layoutInflater, parent, false);
        return new SourceViewHolder(binding);
    }

    @Override
    public void onBindViewHolder(@NonNull RecyclerView.ViewHolder holder, int position) {
        CatalogListItem item = getItem(position);
        if (holder instanceof HeaderViewHolder) {
            ((HeaderViewHolder) holder).binding.catalogHeaderTextView.setText(item.getHeader());
            return;
        }
        SourceViewHolder sourceHolder = (SourceViewHolder) holder;
        CatalogSource source = item.getSource();
        assert source != null;
        sourceHolder.binding.catalogSourceCheckBox.setChecked(item.isChecked());
        sourceHolder.binding.catalogSourceLabelTextView.setText(source.getLabelRes());
        sourceHolder.binding.catalogSourceDescriptionTextView.setText(source.getDescriptionRes());
        bindStatus(sourceHolder, item);
        // Toggle from the immutable item state to stay robust against view recycling
        sourceHolder.itemView.setOnClickListener(view -> this.callback.onToggle(source, !item.isChecked()));
    }

    /**
     * This class is the {@link RecyclerView.ViewHolder} for a category header.
     */
    static class HeaderViewHolder extends RecyclerView.ViewHolder {
        final RecommendedSourcesHeaderBinding binding;

        HeaderViewHolder(RecommendedSourcesHeaderBinding binding) {
            super(binding.getRoot());
            this.binding = binding;
        }
    }

    /**
     * This class is the {@link RecyclerView.ViewHolder} for a catalog source.
     */
    /**
     * Show whether the list is already added, and how many hosts it brings once downloaded.
     */
    private static void bindStatus(SourceViewHolder holder, CatalogListItem item) {
        TextView statusTextView = holder.binding.catalogSourceStatusTextView;
        if (!item.isAdded()) {
            statusTextView.setVisibility(View.GONE);
            return;
        }
        Context context = statusTextView.getContext();
        String status;
        if (!item.isChecked()) {
            status = context.getString(R.string.catalog_status_disabled);
        } else if (item.getSize() > 0) {
            String count = NumberFormat.getIntegerInstance().format(item.getSize());
            status = context.getResources().getQuantityString(R.plurals.catalog_status_enabled_hosts, item.getSize(), count);
        } else {
            status = context.getString(R.string.catalog_status_enabled_pending);
        }
        statusTextView.setText(status);
        // Enabled lists use the "active" color of the application, disabled ones the default text color
        statusTextView.setTextColor(item.isChecked() ?
                ColorStateList.valueOf(context.getColor(R.color.adblock_active)) :
                holder.defaultStatusColors);
        statusTextView.setVisibility(View.VISIBLE);
    }

    static class SourceViewHolder extends RecyclerView.ViewHolder {
        final RecommendedSourcesItemBinding binding;
        final ColorStateList defaultStatusColors;

        SourceViewHolder(RecommendedSourcesItemBinding binding) {
            super(binding.getRoot());
            this.binding = binding;
            this.defaultStatusColors = binding.catalogSourceStatusTextView.getTextColors();
        }
    }

    /**
     * This interface is the callback used to toggle a catalog source subscription.
     */
    interface Callback {
        /**
         * Called when a catalog source subscription is toggled.
         *
         * @param source The catalog source.
         * @param enable Whether the source should be subscribed.
         */
        void onToggle(CatalogSource source, boolean enable);
    }
}
