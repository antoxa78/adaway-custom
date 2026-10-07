package org.adaway.ui.home;

import static org.adaway.model.adblocking.AdBlockMethod.UNDEFINED;
import static org.adaway.model.adblocking.AdBlockMethod.VPN;
import static org.adaway.ui.Animations.removeView;
import static org.adaway.ui.Animations.showView;
import static org.adaway.ui.lists.ListsActivity.ALLOWED_HOSTS_TAB;
import static org.adaway.ui.lists.ListsActivity.BLOCKED_HOSTS_TAB;
import static org.adaway.ui.lists.ListsActivity.REDIRECTED_HOSTS_TAB;
import static org.adaway.ui.lists.ListsActivity.TAB;

import android.content.Intent;
import android.content.res.Resources;
import android.graphics.Typeface;
import android.net.VpnService;
import android.os.Bundle;
import android.view.View;
import android.widget.TextView;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.lifecycle.LiveData;
import androidx.lifecycle.Transformations;
import androidx.lifecycle.ViewModelProvider;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import org.adaway.R;
import org.adaway.databinding.HomeActivityBinding;
import org.adaway.helper.NotificationHelper;
import org.adaway.helper.PreferenceHelper;
import org.adaway.helper.ThemeHelper;
import org.adaway.model.adblocking.AdBlockMethod;
import org.adaway.model.error.HostError;
import org.adaway.ui.help.HelpActivity;
import org.adaway.ui.hosts.HostsSourcesActivity;
import org.adaway.ui.lists.ListsActivity;
import org.adaway.ui.prefs.PrefsActivity;
import org.adaway.ui.update.UpdateActivity;
import org.adaway.ui.welcome.WelcomeActivity;
import org.adaway.util.AutostartUtils;
import org.adaway.util.BatteryOptimizationUtils;
import org.adaway.vpn.VpnServiceControls;

import java.text.NumberFormat;
import java.util.concurrent.TimeUnit;

import kotlin.jvm.functions.Function1;
import timber.log.Timber;

/**
 * This class is the application main activity.
 *
 * @author Bruce BUJON (bruce.bujon(at)gmail(dot)com)
 */
public class HomeActivity extends AppCompatActivity {
    /**
     * The delay between two battery optimization prompts.
     */
    private static final long BATTERY_OPTIMIZATION_PROMPT_DELAY_MS = TimeUnit.DAYS.toMillis(7);

    private HomeActivityBinding binding;
    private HomeViewModel homeViewModel;
    private ActivityResultLauncher<Intent> prepareVpnLauncher;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        ThemeHelper.applyTheme(this);
        NotificationHelper.clearUpdateNotifications(this);
        Timber.i("Starting main activity");
        this.binding = HomeActivityBinding.inflate(getLayoutInflater());
        setContentView(this.binding.getRoot());

        this.homeViewModel = new ViewModelProvider(this).get(HomeViewModel.class);
        this.homeViewModel.isAdBlocked().observe(this, this::notifyAdBlocked);
        this.homeViewModel.getError().observe(this, this::notifyError);

        applyActionBar();
        bindAppVersion();
        bindHostCounter();
        bindSourceCounter();
        bindPending();
        bindState();
        bindLastSourceUpdate();
        bindClickListeners();
        bindDrawerButton();
        bindFab();

        this.prepareVpnLauncher = registerForActivityResult(new StartActivityForResult(), result -> {
            // Restart the VPN if it was authorized by the user
            if (result.getResultCode() == RESULT_OK) {
                checkVpnRestart();
            }
        });

        if (savedInstanceState == null) {
            checkUpdateAtStartup();
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        checkFirstStep();
        checkVpnRestart();
        checkBatteryOptimization();
        checkStartupPermissions();
    }

    private void checkFirstStep() {
        AdBlockMethod adBlockMethod = PreferenceHelper.getAdBlockMethod(this);
        Intent prepareIntent;
        if (adBlockMethod == UNDEFINED) {
            // Start welcome activity
            startActivity(new Intent(this, WelcomeActivity.class));
            finish();
        } else if (adBlockMethod == VPN && (prepareIntent = VpnService.prepare(this)) != null) {
            // Prepare VPN
            this.prepareVpnLauncher.launch(prepareIntent);
        }
    }

    private void checkVpnRestart() {
        // Only restart when VPN ad blocking is supposed to be running
        if (PreferenceHelper.getAdBlockMethod(this) != VPN) {
            return;
        }
        // Ensure the application is still the prepared VPN owner
        if (VpnService.prepare(this) != null) {
            return;
        }
        boolean shouldRun = VpnServiceControls.isStarted(this);
        boolean running = VpnServiceControls.isRunning(this);
        if (shouldRun && !running) {
            Timber.i("Restarting killed VPN service…");
            VpnServiceControls.start(this);
        }
    }

    private void checkBatteryOptimization() {
        // Only prompt while VPN ad blocking is running
        if (PreferenceHelper.getAdBlockMethod(this) != VPN || !VpnServiceControls.isRunning(this)) {
            return;
        }
        if (BatteryOptimizationUtils.isIgnoringBatteryOptimizations(this)) {
            return;
        }
        // Throttle the prompt to avoid nagging
        long now = System.currentTimeMillis();
        long lastPrompt = PreferenceHelper.getVpnBatteryOptimizationPromptTimestamp(this);
        if (lastPrompt != 0 && now - lastPrompt < BATTERY_OPTIMIZATION_PROMPT_DELAY_MS) {
            return;
        }
        PreferenceHelper.setVpnBatteryOptimizationPromptTimestamp(this, now);
        showBatteryOptimizationDialog();
    }

    private void showBatteryOptimizationDialog() {
        new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.battery_optimization_dialog_title)
                .setMessage(R.string.battery_optimization_dialog_message)
                .setPositiveButton(R.string.battery_optimization_dialog_positive, (dialog, which) ->
                        BatteryOptimizationUtils.requestIgnoreBatteryOptimizations(this))
                .setNegativeButton(R.string.battery_optimization_dialog_negative, (dialog, which) -> dialog.dismiss())
                .create()
                .show();
    }

    private void checkStartupPermissions() {
        // Only prompt once, after the initial setup
        if (PreferenceHelper.isStartupPermissionPrompted(this)) {
            return;
        }
        if (PreferenceHelper.getAdBlockMethod(this) == UNDEFINED) {
            return;
        }
        PreferenceHelper.setStartupPermissionPrompted(this, true);
        // Avoid the weekly battery optimization prompt right after this one
        PreferenceHelper.setVpnBatteryOptimizationPromptTimestamp(this, System.currentTimeMillis());
        showStartupPermissionDialog();
    }

    private void showStartupPermissionDialog() {
        new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.startup_permission_dialog_title)
                .setMessage(R.string.startup_permission_dialog_message)
                .setPositiveButton(R.string.startup_permission_dialog_allow, (dialog, which) -> {
                    // Allow AdAway to start at phone startup
                    PreferenceHelper.setVpnServiceOnBoot(this, true);
                    // Request to ignore battery optimization
                    BatteryOptimizationUtils.requestIgnoreBatteryOptimizations(this);
                    // Open the system autostart settings when available
                    AutostartUtils.openOemAutostartSettings(this);
                })
                .setNegativeButton(R.string.startup_permission_dialog_later, (dialog, which) -> dialog.dismiss())
                .create()
                .show();
    }

    private void checkUpdateAtStartup() {
        boolean checkAppUpdateAtStartup = PreferenceHelper.getUpdateCheckAppStartup(this);
        if (checkAppUpdateAtStartup) {
            this.homeViewModel.checkForAppUpdate();
        }
        boolean checkUpdateAtStartup = PreferenceHelper.getUpdateCheck(this);
        if (checkUpdateAtStartup) {
            this.homeViewModel.update();
        }
    }

    private void applyActionBar() {
        setSupportActionBar(this.binding.bar);
    }

    private void bindAppVersion() {
        TextView appNameTextView = this.binding.content.appNameTextView;
        String appName = getString(R.string.app_name);
        String version = this.homeViewModel.getVersionName();
        // Show the version right after the application name
        appNameTextView.setText(getString(R.string.app_name_version, appName, version));
        appNameTextView.setOnClickListener(this::showUpdate);

        this.homeViewModel.getAppManifest().observe(
                this,
                manifest -> {
                    if (manifest.updateAvailable) {
                        appNameTextView.setTypeface(appNameTextView.getTypeface(), Typeface.BOLD);
                        appNameTextView.setText(getString(R.string.app_name_version, appName, version)
                                + " • " + getString(R.string.update_available));
                    }
                }
        );
    }

    private void bindHostCounter() {
        // Show the counts with the locale digit grouping (e.g. 72,237), easier to read than 72237
        NumberFormat numberFormat = NumberFormat.getIntegerInstance();
        Function1<Integer, CharSequence> stringMapper = count -> numberFormat.format(count == null ? 0 : count);

        TextView blockedHostCountTextView = this.binding.content.blockedHostCounterTextView;
        LiveData<Integer> blockedHostCount = this.homeViewModel.getBlockedHostCount();
        Transformations.map(blockedHostCount, stringMapper).observe(this, blockedHostCountTextView::setText);

        TextView allowedHostCountTextView = this.binding.content.allowedHostCounterTextView;
        LiveData<Integer> allowedHostCount = this.homeViewModel.getAllowedHostCount();
        Transformations.map(allowedHostCount, stringMapper).observe(this, allowedHostCountTextView::setText);

        TextView redirectHostCountTextView = this.binding.content.redirectHostCounterTextView;
        LiveData<Integer> redirectHostCount = this.homeViewModel.getRedirectHostCount();
        Transformations.map(redirectHostCount, stringMapper).observe(this, redirectHostCountTextView::setText);
    }

    private void bindSourceCounter() {
        Resources resources = getResources();

        TextView upToDateSourcesTextView = this.binding.content.upToDateSourcesTextView;
        LiveData<Integer> upToDateSourceCount = this.homeViewModel.getUpToDateSourceCount();
        upToDateSourceCount.observe(this, count ->
                upToDateSourcesTextView.setText(resources.getQuantityString(R.plurals.up_to_date_source_label, count, count))
        );

        TextView outdatedSourcesTextView = this.binding.content.outdatedSourcesTextView;
        LiveData<Integer> outdatedSourceCount = this.homeViewModel.getOutdatedSourceCount();
        outdatedSourceCount.observe(this, count ->
                outdatedSourcesTextView.setText(resources.getQuantityString(R.plurals.outdated_source_label, count, count))
        );
    }

    private void bindPending() {
        this.homeViewModel.getPending().observe(this, pending -> {
            if (pending) {
                showView(this.binding.content.sourcesProgressBar);
                showView(this.binding.content.stateTextView);
            } else {
                removeView(this.binding.content.sourcesProgressBar);
            }
        });
    }

    private void bindState() {
        this.homeViewModel.getState().observe(this, text -> {
            this.binding.content.stateTextView.setText(text);
            if (text.isEmpty()) {
                removeView(this.binding.content.stateTextView);
            } else {
                showView(this.binding.content.stateTextView);
            }
        });
    }

    private void bindLastSourceUpdate() {
        TextView lastUpdateTextView = this.binding.content.lastUpdateTextView;
        this.homeViewModel.getLastSourceUpdate().observe(this, dateTime -> {
            if (dateTime == null || dateTime.isEmpty()) {
                removeView(lastUpdateTextView);
            } else {
                lastUpdateTextView.setText(getString(R.string.home_sources_last_update, dateTime));
                showView(lastUpdateTextView);
            }
        });
    }

    private void bindClickListeners() {
        this.binding.content.blockedHostCardView.setOnClickListener(v -> startHostListActivity(BLOCKED_HOSTS_TAB));
        this.binding.content.allowedHostCardView.setOnClickListener(v -> startHostListActivity(ALLOWED_HOSTS_TAB));
        this.binding.content.redirectHostCardView.setOnClickListener(v -> startHostListActivity(REDIRECTED_HOSTS_TAB));
        this.binding.content.sourcesCardView.setOnClickListener(this::startHostsSourcesActivity);
        this.binding.content.checkForUpdateImageView.setOnClickListener(v -> this.homeViewModel.update());
        this.binding.content.updateImageView.setOnClickListener(v -> this.homeViewModel.sync());
    }

    private void bindDrawerButton() {
        // Open the preferences directly from the navigation button
        this.binding.content.homeDrawerButton.setOnClickListener(v -> startPrefsActivity());
    }

    private void bindFab() {
        this.binding.fab.setOnClickListener(v -> this.homeViewModel.toggleAdBlocking());
    }

    /**
     * Start hosts lists activity.
     *
     * @param tab The tab to show.
     */
    private void startHostListActivity(int tab) {
        Intent intent = new Intent(this, ListsActivity.class);
        intent.putExtra(TAB, tab);
        startActivity(intent);
    }

    /**
     * Start hosts source activity.
     *
     * @param view The event source view.
     */
    private void startHostsSourcesActivity(View view) {
        startActivity(new Intent(this, HostsSourcesActivity.class));
    }

    /**
     * Start preferences activity.
     */
    private void startPrefsActivity() {
        startActivity(new Intent(this, PrefsActivity.class));
    }

    private void notifyAdBlocked(boolean adBlocked) {
        // Status color: green when ad blocking is running, red when stopped
        int color = getColor(adBlocked ? R.color.adblock_active : R.color.adblock_stopped);
        // Header background indicates the ad blocking state, the status bar continues it
        this.binding.content.headerFrameLayout.setBackgroundColor(color);
        getWindow().setStatusBarColor(color);
        // Update the start/stop button icon and description
        this.binding.fab.setImageResource(adBlocked ? R.drawable.ic_stop_24dp : R.drawable.ic_start_24dp);
        this.binding.fab.setContentDescription(getString(
                adBlocked ? R.string.adblock_stop_button_description : R.string.adblock_start_button_description
        ));
        // Update the status shown in the header (its colors follow the header)
        TextView statusTextView = this.binding.content.adBlockStatusTextView;
        statusTextView.setText(adBlocked ? R.string.adblock_status_running : R.string.adblock_status_stopped);
        // Hidden until the state is known, so no empty status is shown at startup
        statusTextView.setVisibility(View.VISIBLE);
    }

    private void notifyError(HostError error) {
        removeView(this.binding.content.stateTextView);
        if (error == null) {
            return;
        }

        String message = getString(error.getDetailsKey()) + "\n\n" + getString(R.string.error_dialog_help);
        new MaterialAlertDialogBuilder(this)
                .setIcon(android.R.drawable.ic_dialog_alert)
                .setTitle(error.getMessageKey())
                .setMessage(message)
                .setPositiveButton(R.string.button_close, (dialog, id) -> dialog.dismiss())
                .setNegativeButton(R.string.button_help, (dialog, id) -> {
                    dialog.dismiss();
                    startActivity(new Intent(this, HelpActivity.class));
                })
                .create()
                .show();
    }

    private void showUpdate(View view) {
        Intent intent = new Intent(this, UpdateActivity.class);
        startActivity(intent);
    }
}
