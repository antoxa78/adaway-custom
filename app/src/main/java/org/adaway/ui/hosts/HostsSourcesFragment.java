/*
 * Copyright (C) 2011-2012 Dominik Schürmann <dominik@dominikschuermann.de>
 *
 * This file is part of AdAway.
 *
 * AdAway is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * AdAway is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with AdAway.  If not, see <http://www.gnu.org/licenses/>.
 *
 */

package org.adaway.ui.hosts;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.coordinatorlayout.widget.CoordinatorLayout;
import androidx.fragment.app.Fragment;
import androidx.lifecycle.LifecycleOwner;
import androidx.lifecycle.ViewModelProvider;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.floatingactionbutton.FloatingActionButton;

import org.adaway.R;
import org.adaway.db.entity.HostsSource;
import org.adaway.ui.adblocking.ApplyConfigurationSnackbar;
import org.adaway.ui.source.SourceEditActivity;

import static org.adaway.ui.source.SourceEditActivity.SOURCE_ID;

/**
 * This class is a {@link Fragment} to display and manage hosts sources.
 *
 * @author Bruce BUJON (bruce.bujon(at)gmail(dot)com)
 */
public class HostsSourcesFragment extends Fragment implements HostsSourcesViewCallback {
    /**
     * The view model (<code>null</code> if view is not created).
     */
    private HostsSourcesViewModel mViewModel;
    /**
     * The snackbar used to notify and apply configuration changes.
     */
    private ApplyConfigurationSnackbar applySnackbar;

    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
        // Get activity
        Activity activity = requireActivity();
        // Initialize view model
        this.mViewModel = new ViewModelProvider(this).get(HostsSourcesViewModel.class);
        LifecycleOwner lifecycleOwner = getViewLifecycleOwner();
        // Create fragment view
        View view = inflater.inflate(R.layout.hosts_sources_fragment, container, false);
        // Enable options menu to access the recommended sources catalog
        setHasOptionsMenu(true);
        /*
         * Configure snackbar.
         */
        // Get lists layout to attached snackbar to
        CoordinatorLayout coordinatorLayout = view.findViewById(R.id.coordinator);
        // Create apply snackbar
        this.applySnackbar = new ApplyConfigurationSnackbar(coordinatorLayout, true, true);
        // Bind snakbar to view models
        this.mViewModel.getHostsSources().observe(lifecycleOwner, this.applySnackbar.createObserver());
        /*
         * Configure recycler view.
         */
        // Store recycler view
        RecyclerView recyclerView = view.findViewById(R.id.hosts_sources_list);
        recyclerView.setHasFixedSize(true);
        // Defile recycler layout
        LinearLayoutManager linearLayoutManager = new LinearLayoutManager(activity);
        recyclerView.setLayoutManager(linearLayoutManager);
        // Create recycler adapter
        HostsSourcesAdapter adapter = new HostsSourcesAdapter(this);
        recyclerView.setAdapter(adapter);
        // Bind adapter to view model
        this.mViewModel.getHostsSources().observe(lifecycleOwner, adapter::submitList);
        /*
         * Add floating action button.
         */
        // Get floating action button
        FloatingActionButton button = view.findViewById(R.id.hosts_sources_add);
        // Set click listener to display menu add entry
        button.setOnClickListener(actionButton -> startSourceEdition(null));
        /*
         * Configure apply button.
         */
        MaterialButton applyButton = view.findViewById(R.id.hosts_sources_apply);
        applyButton.setOnClickListener(actionButton -> {
            // Prevent concurrent apply operations
            applyButton.setEnabled(false);
            this.applySnackbar.apply();
        });
        this.applySnackbar.setOnApplyFinished(() -> applyButton.setEnabled(true));
        // Display snackbars above the bottom bar to avoid covering the apply/add buttons
        this.applySnackbar.setAnchorView(view.findViewById(R.id.hosts_sources_bottom_bar));
        // Return fragment view
        return view;
    }

    @Override
    public void onCreateOptionsMenu(@NonNull Menu menu, @NonNull MenuInflater inflater) {
        inflater.inflate(R.menu.hosts_sources_menu, menu);
        super.onCreateOptionsMenu(menu, inflater);
    }

    @Override
    public boolean onOptionsItemSelected(@NonNull MenuItem item) {
        if (item.getItemId() == R.id.recommended_sources_action) {
            startActivity(new Intent(requireContext(), RecommendedSourcesActivity.class));
            return true;
        }
        if (item.getItemId() == R.id.restore_default_sources_action) {
            confirmRestoreDefaultSources();
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    private void confirmRestoreDefaultSources() {
        new MaterialAlertDialogBuilder(requireContext())
                .setTitle(R.string.hosts_restore_defaults)
                .setMessage(R.string.hosts_restore_defaults_message)
                .setPositiveButton(R.string.hosts_restore_defaults_confirm, (dialog, which) -> this.mViewModel.restoreDefaultSources())
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    @Override
    public void toggleEnabled(HostsSource source) {
        this.mViewModel.toggleSourceEnabled(source);
    }

    @Override
    public void edit(HostsSource source) {
        startSourceEdition(source);
    }

    private void startSourceEdition(@Nullable HostsSource source) {
        Intent intent = new Intent(requireContext(), SourceEditActivity.class);
        if (source != null) {
            intent.putExtra(SOURCE_ID, source.getId());
        }
        startActivity(intent);
    }
}
