package org.adaway.ui.prefs;

import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult;
import androidx.annotation.NonNull;
import androidx.preference.ListPreference;
import androidx.preference.Preference;
import androidx.preference.PreferenceFragmentCompat;
import androidx.preference.SwitchPreferenceCompat;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import org.adaway.R;
import org.adaway.ui.prefs.exclusion.PrefsVpnExcludedAppsActivity;
import org.adaway.util.AutostartUtils;
import org.adaway.util.BatteryOptimizationUtils;
import org.adaway.vpn.VpnServiceControls;

import static org.adaway.ui.prefs.PrefsActivity.PREFERENCE_NOT_FOUND;
import static org.adaway.util.Constants.PREFS_NAME;

/**
 * This fragment is the preferences fragment for VPN ad blocker.
 *
 * @author Bruce BUJON (bruce.bujon(at)gmail(dot)com)
 */
public class PrefsVpnFragment extends PreferenceFragmentCompat {
    private ActivityResultLauncher<Intent> startActivityLauncher;

    @Override
    public void onCreatePreferences(Bundle savedInstanceState, String rootKey) {
        // Configure preferences
        getPreferenceManager().setSharedPreferencesName(PREFS_NAME);
        addPreferencesFromResource(R.xml.preferences_vpn);
        // Register for activity
        registerForStartActivity();
        // Bind pref actions
        bindAutostart();
        bindExcludedSystemApps();
        bindExcludedUserApps();
        bindBatteryOptimization();
    }

    @Override
    public void onAttach(@NonNull Context context) {
        super.onAttach(context);
        PrefsActivity.setAppBarTitle(this, R.string.pref_vpn_title);
    }

    private void registerForStartActivity() {
        this.startActivityLauncher = registerForActivityResult(
                new StartActivityForResult(),
                result -> restartVpn()
        );
    }

    private void bindAutostart() {
        SwitchPreferenceCompat autostartPreference = findPreference(getString(R.string.pref_vpn_service_on_boot_key));
        assert autostartPreference != null : PREFERENCE_NOT_FOUND;
        autostartPreference.setOnPreferenceChangeListener((preference, newValue) -> {
            // Disabling does not require any confirmation
            if (!(Boolean) newValue) {
                return true;
            }
            // Ask the user to allow running at startup before enabling the option
            showAutostartConfirmation((SwitchPreferenceCompat) preference);
            return false;
        });
    }

    private void showAutostartConfirmation(SwitchPreferenceCompat preference) {
        Context context = requireContext();
        new MaterialAlertDialogBuilder(context)
                .setTitle(R.string.pref_vpn_service_on_boot_confirm_title)
                .setMessage(R.string.pref_vpn_service_on_boot_confirm_message)
                .setPositiveButton(R.string.pref_vpn_service_on_boot_confirm_allow, (dialog, which) -> {
                    // Enable the option
                    preference.setChecked(true);
                    // Open the system autostart settings so the user can allow AdAway
                    AutostartUtils.openAutostartSettings(context);
                })
                .setNegativeButton(R.string.pref_vpn_service_on_boot_confirm_deny, null)
                .show();
    }

    private void bindExcludedSystemApps() {
        ListPreference excludeUserAppsPreferences = findPreference(getString(R.string.pref_vpn_excluded_system_apps_key));
        assert excludeUserAppsPreferences != null : PREFERENCE_NOT_FOUND;
        excludeUserAppsPreferences.setOnPreferenceChangeListener((preference, newValue) -> {
            restartVpn();
            return true;
        });
    }

    private void bindExcludedUserApps() {
        Context context = requireContext();
        Preference excludeUserAppsPreferences = findPreference(getString(R.string.pref_vpn_excluded_user_apps_key));
        assert excludeUserAppsPreferences != null : PREFERENCE_NOT_FOUND;
        excludeUserAppsPreferences.setOnPreferenceClickListener(preference -> {
            Intent intent = new Intent(context, PrefsVpnExcludedAppsActivity.class);
            this.startActivityLauncher.launch(intent);
            return true;
        });
    }

    private void bindBatteryOptimization() {
        Preference batteryOptimizationPreference = findPreference(getString(R.string.pref_vpn_battery_optimization_key));
        assert batteryOptimizationPreference != null : PREFERENCE_NOT_FOUND;
        batteryOptimizationPreference.setOnPreferenceClickListener(preference -> {
            Context context = requireContext();
            if (BatteryOptimizationUtils.isIgnoringBatteryOptimizations(context)) {
                Toast.makeText(context, R.string.pref_vpn_battery_optimization_already_exempt, Toast.LENGTH_SHORT).show();
            } else {
                BatteryOptimizationUtils.requestIgnoreBatteryOptimizations(context);
            }
            return true;
        });
    }

    @Override
    public void onResume() {
        super.onResume();
        updateBatteryOptimizationSummary();
    }

    private void updateBatteryOptimizationSummary() {
        Context context = requireContext();
        Preference batteryOptimizationPreference = findPreference(getString(R.string.pref_vpn_battery_optimization_key));
        assert batteryOptimizationPreference != null : PREFERENCE_NOT_FOUND;
        batteryOptimizationPreference.setSummary(BatteryOptimizationUtils.isIgnoringBatteryOptimizations(context) ?
                R.string.pref_vpn_battery_optimization_summary_exempt :
                R.string.pref_vpn_battery_optimization_summary_request);
    }

    private void restartVpn() {
        Context context = requireContext();
        if (VpnServiceControls.isRunning(context)) {
            // Rebuild the tunnel in place to apply the new configuration. Sending stop then start
            // does not work: start sees the service still running and does nothing, then the stop
            // command arrives and leaves the VPN off.
            VpnServiceControls.restart(context);
        }
    }
}
