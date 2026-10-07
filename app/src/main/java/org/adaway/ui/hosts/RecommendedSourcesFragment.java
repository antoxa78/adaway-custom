package org.adaway.ui.hosts;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.lifecycle.ViewModelProvider;
import androidx.recyclerview.widget.LinearLayoutManager;

import org.adaway.databinding.RecommendedSourcesFragmentBinding;
import org.adaway.model.source.CatalogSource;
import org.adaway.ui.adblocking.ApplyConfigurationSnackbar;

/**
 * This class is a {@link Fragment} displaying the recommended sources catalog.<br>
 * The user can enable or disable any catalog entry.
 *
 * @author Bruce BUJON (bruce.bujon(at)gmail(dot)com)
 */
public class RecommendedSourcesFragment extends Fragment {
    private RecommendedSourcesViewModel viewModel;

    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
        RecommendedSourcesFragmentBinding binding = RecommendedSourcesFragmentBinding.inflate(inflater, container, false);
        // Initialize view model
        this.viewModel = new ViewModelProvider(this).get(RecommendedSourcesViewModel.class);
        // Configure recycler view
        binding.recommendedSourcesList.setHasFixedSize(true);
        binding.recommendedSourcesList.setLayoutManager(new LinearLayoutManager(requireContext()));
        RecommendedSourcesAdapter adapter = new RecommendedSourcesAdapter(this::onToggle);
        binding.recommendedSourcesList.setAdapter(adapter);
        // Bind adapter to view model
        this.viewModel.getItems().observe(getViewLifecycleOwner(), adapter::submitList);
        // Offer to apply the new configuration once a list was enabled or disabled
        ApplyConfigurationSnackbar applySnackbar = new ApplyConfigurationSnackbar(binding.getRoot(), true, true);
        this.viewModel.getHostsSources().observe(getViewLifecycleOwner(), applySnackbar.createObserver());
        return binding.getRoot();
    }

    private void onToggle(@NonNull CatalogSource source, boolean enable) {
        this.viewModel.setSubscribed(source, enable);
    }
}
