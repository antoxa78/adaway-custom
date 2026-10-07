package org.adaway.model.update;

import static android.app.DownloadManager.ACTION_DOWNLOAD_COMPLETE;
import static org.adaway.model.update.UpdateStore.getApkStore;
import static java.util.Objects.requireNonNull;

import android.app.DownloadManager;
import android.content.Context;
import android.content.IntentFilter;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.net.Uri;

import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;

import org.adaway.R;
import org.adaway.helper.PreferenceHelper;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;

import okhttp3.HttpUrl;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;
import timber.log.Timber;

/**
 * This class is the model in charge of updating the application.
 *
 * @author Bruce BUJON (bruce.bujon(at)gmail(dot)com)
 */
public class UpdateModel {
    /**
     * The latest stable release (GitHub excludes drafts and pre-releases from this endpoint).
     */
    private static final String LATEST_RELEASE_URL = "https://api.github.com/repos/antoxa78/adaway-custom/releases/latest";
    /**
     * The release list, including pre-releases, used when beta releases are enabled.
     */
    private static final String RELEASES_URL = "https://api.github.com/repos/antoxa78/adaway-custom/releases";
    /**
     * The number of releases to look at when beta releases are enabled.
     */
    private static final int RELEASES_PAGE_SIZE = 10;
    private final Context context;
    private final VersionInfo versionInfo;
    private final OkHttpClient client;
    private final MutableLiveData<Manifest> manifest;
    private ApkDownloadReceiver receiver;

    /**
     * Constructor.
     *
     * @param context The application context.
     */
    public UpdateModel(Context context) {
        this.context = context;
        this.versionInfo = VersionInfo.get(context);
        this.manifest = new MutableLiveData<>();
        this.client = buildHttpClient();
        ApkUpdateService.syncPreferences(context);
    }

    /**
     * Get the current version code.
     *
     * @return The current version code.
     */
    public int getVersionCode() {
        return this.versionInfo.code;
    }

    /**
     * Get the current version name.
     *
     * @return The current version name.
     */
    public String getVersionName() {
        return this.versionInfo.name;
    }

    /**
     * Get the last version manifest.
     *
     * @return The last version manifest.
     */
    public LiveData<Manifest> getManifest() {
        return this.manifest;
    }

    /**
     * Get the application update store.
     *
     * @return The application update store.
     */
    public UpdateStore getStore() {
        return getApkStore(this.context);
    }

    /**
     * Get the application update channel.
     *
     * @return The application update channel.
     */
    public String getChannel() {
        return PreferenceHelper.getIncludeBetaReleases(this.context) ? "beta" : "stable";
    }

    /**
     * Check if there is an update available.
     */
    public void checkForUpdate() {
        Manifest manifest = downloadManifest();
        // Notify update
        if (manifest != null) {
            this.manifest.postValue(manifest);
        }
    }

    private OkHttpClient buildHttpClient() {
        return new OkHttpClient.Builder().build();
    }

    private Manifest downloadManifest() {
        if (!this.versionInfo.isValid()) {
            return null;
        }
        boolean includeBetaReleases = PreferenceHelper.getIncludeBetaReleases(this.context);
        HttpUrl httpUrl = includeBetaReleases ?
                requireNonNull(HttpUrl.parse(RELEASES_URL), "Failed to parse releases URL")
                        .newBuilder()
                        .addQueryParameter("per_page", Integer.toString(RELEASES_PAGE_SIZE))
                        .build() :
                requireNonNull(HttpUrl.parse(LATEST_RELEASE_URL), "Failed to parse latest release URL");
        Request request = new Request.Builder()
                .url(httpUrl)
                .header("Accept", "application/vnd.github+json")
                .build();
        try (Response execute = this.client.newCall(request).execute();
             ResponseBody body = execute.body()) {
            if (!execute.isSuccessful() || body == null) {
                return null;
            }
            String content = body.string();
            return includeBetaReleases ?
                    findNewestRelease(new JSONArray(content)) :
                    new Manifest(content, this.versionInfo.code);
        } catch (IOException | JSONException exception) {
            Timber.e(exception, "Unable to download manifest.");
            // Return failed
            return null;
        }
    }

    /**
     * Find the newest release (stable or pre-release) from a GitHub release list.
     *
     * @param releases The GitHub release list.
     * @return The manifest of the release with the highest version, {@code null} if none.
     * @throws JSONException If the release list cannot be parsed.
     */
    private Manifest findNewestRelease(JSONArray releases) throws JSONException {
        Manifest newest = null;
        for (int i = 0; i < releases.length(); i++) {
            JSONObject release = releases.getJSONObject(i);
            if (release.optBoolean("draft", false)) {
                continue;
            }
            Manifest manifest = new Manifest(release.toString(), this.versionInfo.code);
            if (newest == null || manifest.versionCode > newest.versionCode) {
                newest = manifest;
            }
        }
        return newest;
    }

    /**
     * Update the application to the latest version.
     *
     * @return The download identifier ({@code -1} if download was not started).
     */
    public long update() {
        // Check manifest
        Manifest manifest = this.manifest.getValue();
        if (manifest == null) {
            return -1;
        }
        // Check the release has an APK to download
        if (manifest.downloadUrl == null) {
            Timber.w("Release %s has no APK asset to download.", manifest.version);
            return -1;
        }
        // Check previous broadcast receiver
        if (this.receiver != null) {
            this.context.unregisterReceiver(this.receiver);
        }
        // Queue download
        long downloadId = download(manifest);
        // Register new broadcast receiver
        this.receiver = new ApkDownloadReceiver(downloadId);
        this.context.registerReceiver(this.receiver, new IntentFilter(ACTION_DOWNLOAD_COMPLETE));
        // Return download identifier
        return downloadId;
    }

    private long download(Manifest manifest) {
        Timber.i("Downloading " + manifest.version + ".");
        Uri uri = Uri.parse(manifest.downloadUrl);
        DownloadManager.Request request = new DownloadManager.Request(uri)
                .setTitle("AdAway " + manifest.version)
                .setDescription(this.context.getString(R.string.update_notification_description));
        DownloadManager downloadManager = this.context.getSystemService(DownloadManager.class);
        return downloadManager.enqueue(request);
    }

    private static class VersionInfo {
        private final int code;
        private final String name;

        private VersionInfo(int code, String name) {
            this.code = code;
            this.name = name;
        }

        public static VersionInfo get(Context context) {
            try {
                PackageInfo packageInfo = context.getPackageManager()
                        .getPackageInfo(context.getPackageName(), 0);
                return new VersionInfo(packageInfo.versionCode, packageInfo.versionName);
            } catch (PackageManager.NameNotFoundException e) {
                return new VersionInfo(0, "development");
            }
        }

        public boolean isValid() {
            return this.code > 0;
        }
    }
}
