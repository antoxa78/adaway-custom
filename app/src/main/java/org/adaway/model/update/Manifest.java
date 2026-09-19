package org.adaway.model.update;

import androidx.annotation.Nullable;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * This class represents a GitHub release used to update the application.
 * <p>
 * It is built from the GitHub Releases API response for a single release:
 * {@code tag_name} (e.g. {@code v6.2.0}), {@code body} (release notes) and a
 * {@code browser_download_url} pointing to the APK asset.
 * </p>
 */
public class Manifest {
    private static final Pattern VERSION_PATTERN = Pattern.compile("(\\d+)\\.(\\d+)(?:\\.(\\d+))?");

    public final String version;
    public final int versionCode;
    @Nullable
    public final String changelog;
    public final String downloadUrl;
    public final boolean updateAvailable;

    public Manifest(String release, long currentVersionCode) throws JSONException {
        JSONObject releaseObject = new JSONObject(release);
        String tagName = releaseObject.getString("tag_name");
        this.version = releaseObject.optString("name", tagName);
        this.versionCode = versionCodeFromTag(tagName);
        this.changelog = releaseObject.isNull("body") ? null : releaseObject.getString("body");
        this.downloadUrl = getApkUrl(releaseObject.getJSONArray("assets"));
        this.updateAvailable = this.versionCode > currentVersionCode;
    }

    /**
     * Derive the version code from a version tag following the
     * {@code x.yy.zz} convention (e.g. {@code v6.2.0} to {@code 60200}).
     *
     * @param tagName The release tag name (e.g. {@code v6.2.0}).
     * @return The derived version code.
     */
    private static int versionCodeFromTag(String tagName) {
        Matcher matcher = VERSION_PATTERN.matcher(tagName);
        if (!matcher.find()) {
            return 0;
        }
        int major = Integer.parseInt(matcher.group(1));
        int minor = Integer.parseInt(matcher.group(2));
        int patch = matcher.group(3) == null ? 0 : Integer.parseInt(matcher.group(3));
        return major * 10000 + minor * 100 + patch;
    }

    /**
     * Find the download URL of the APK asset.
     * <p>
     * Prefers the release (signed) APK asset and skips debug builds.
     * </p>
     *
     * @param assets The release assets.
     * @return The first APK asset URL, or {@code null} if no APK asset is found.
     * @throws JSONException If the assets can't be parsed.
     */
    @Nullable
    private static String getApkUrl(JSONArray assets) throws JSONException {
        String fallback = null;
        for (int i = 0; i < assets.length(); i++) {
            JSONObject asset = assets.getJSONObject(i);
            String name = asset.optString("name", "");
            if (!name.endsWith(".apk")) {
                continue;
            }
            if (fallback == null) {
                fallback = asset.getString("browser_download_url");
            }
            if (!name.toLowerCase().contains("debug")) {
                return asset.getString("browser_download_url");
            }
        }
        return fallback;
    }
}
