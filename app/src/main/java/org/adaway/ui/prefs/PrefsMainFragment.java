package org.adaway.ui.prefs;

import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Bundle;

import androidx.annotation.NonNull;
import androidx.preference.Preference;
import androidx.preference.PreferenceFragmentCompat;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import org.adaway.R;
import org.adaway.helper.PreferenceHelper;
import org.adaway.model.adblocking.AdBlockMethod;
import org.adaway.ui.help.HelpActivity;
import org.adaway.ui.log.LogActivity;

import timber.log.Timber;

import static org.adaway.model.adblocking.AdBlockMethod.ROOT;
import static org.adaway.model.adblocking.AdBlockMethod.VPN;
import static org.adaway.ui.prefs.PrefsActivity.PREFERENCE_NOT_FOUND;
import static org.adaway.util.Constants.PREFS_NAME;

/**
 * This fragment is the preferences main fragment.
 *
 * @author Bruce BUJON (bruce.bujon(at)gmail(dot)com)
 */
public class PrefsMainFragment extends PreferenceFragmentCompat {
    /**
     * The development project link.
     */
    private static final String PROJECT_LINK = "https://github.com/antoxa78/adaway-custom";

    @Override
    public void onCreatePreferences(Bundle savedInstanceState, String rootKey) {
        // Configure preferences
        getPreferenceManager().setSharedPreferencesName(PREFS_NAME);
        addPreferencesFromResource(R.xml.preferences_main);
        // Bind pref actions
        bindThemePrefAction();
        bindAdBlockMethod();
        bindHelpAndAbout();
    }

    @Override
    public void onAttach(@NonNull Context context) {
        super.onAttach(context);
        PrefsActivity.setAppBarTitle(this, R.string.pref_main_title);
    }

    @Override
    public void onResume() {
        super.onResume();
        PrefsActivity.setAppBarTitle(this, R.string.pref_main_title);
    }

    private void bindThemePrefAction() {
        Preference darkThemePref = findPreference(getString(R.string.pref_dark_theme_mode_key));
        assert darkThemePref != null : PREFERENCE_NOT_FOUND;
        darkThemePref.setOnPreferenceChangeListener((preference, newValue) -> {
            requireActivity().recreate();
            // Allow preference change
            return true;
        });
    }

    private void bindAdBlockMethod() {
        Preference rootPreference = findPreference(getString(R.string.pref_root_ad_block_method_key));
        assert rootPreference != null : PREFERENCE_NOT_FOUND;
        Preference vpnPreference = findPreference(getString(R.string.pref_vpn_ad_block_method_key));
        assert vpnPreference != null : PREFERENCE_NOT_FOUND;
        AdBlockMethod adBlockMethod = PreferenceHelper.getAdBlockMethod(requireContext());
        rootPreference.setEnabled(adBlockMethod == ROOT);
        vpnPreference.setEnabled(adBlockMethod == VPN);
    }

    private void bindHelpAndAbout() {
        Preference dnsLogPreference = findPreference("pref_show_dns_log");
        assert dnsLogPreference != null : PREFERENCE_NOT_FOUND;
        dnsLogPreference.setOnPreferenceClickListener(preference -> {
            startActivity(new Intent(requireContext(), LogActivity.class));
            return true;
        });

        Preference helpPreference = findPreference("pref_show_help");
        assert helpPreference != null : PREFERENCE_NOT_FOUND;
        helpPreference.setOnPreferenceClickListener(preference -> {
            startActivity(new Intent(requireContext(), HelpActivity.class));
            return true;
        });

        Preference projectPreference = findPreference("pref_github_project");
        assert projectPreference != null : PREFERENCE_NOT_FOUND;
        projectPreference.setOnPreferenceClickListener(preference -> {
            try {
                startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(PROJECT_LINK)));
            } catch (ActivityNotFoundException e) {
                // No browser installed: show the link so the user can still copy it
                Timber.w(e, "No application to open the project page.");
                showAboutDialog();
            }
            return true;
        });

        Preference aboutPreference = findPreference("pref_about");
        assert aboutPreference != null : PREFERENCE_NOT_FOUND;
        aboutPreference.setOnPreferenceClickListener(preference -> {
            showAboutDialog();
            return true;
        });
    }

    private void showAboutDialog() {
        Context context = requireContext();
        String versionName = "";
        try {
            versionName = context.getPackageManager()
                    .getPackageInfo(context.getPackageName(), 0)
                    .versionName;
        } catch (PackageManager.NameNotFoundException exception) {
            // Ignore, keep the version empty
        }
        String message = context.getString(R.string.pref_about_version, versionName)
                + "\n\n" + context.getString(R.string.app_description)
                + "\n\n" + PROJECT_LINK;
        new MaterialAlertDialogBuilder(context)
                .setTitle(R.string.app_name)
                .setMessage(message)
                .setPositiveButton(R.string.button_close, null)
                .show();
    }
}
