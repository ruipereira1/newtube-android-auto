package com.newtube.mobile.car.setup;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Build;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.WorkerThread;

import com.liskovsoft.smartyoutubetv2.tv.BuildConfig;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

/**
 * This fork's releases on GitHub: the latest tag, and its APK for this phone.
 *
 * <p>Light on data: the check is one small JSON request (a few KB), at most once a day on its
 * own; the APK (~25 MB) is downloaded only when the person taps Update, and only on Wi-Fi -
 * Wireless debugging, which installs it, needs Wi-Fi anyway.</p>
 */
public final class CarUpdates {
    private static final String REPO = "ruipereira1/newtube-android-auto";
    private static final String LATEST = "https://api.github.com/repos/" + REPO + "/releases/latest";
    private static final String PREFS = "newtube_car_setup";
    private static final String KEY_LATEST_TAG = "latest_tag";
    private static final String KEY_LATEST_URL = "latest_url";
    private static final String KEY_LATEST_SIZE = "latest_size";
    private static final String KEY_CHECKED_AT = "checked_at";
    private static final long DAY = 24L * 3600_000L;

    public static final class Release {
        @NonNull public final String tag;
        @NonNull public final String apkUrl;
        public final long size;

        Release(@NonNull String tag, @NonNull String apkUrl, long size) {
            this.tag = tag;
            this.apkUrl = apkUrl;
            this.size = size;
        }
    }

    public interface DownloadProgress {
        void onProgress(long bytes, long total);
    }

    private CarUpdates() {
    }

    /** This build's release tag, or "" for a build made on a computer. */
    @NonNull
    public static String installedTag() {
        return BuildConfig.AUTO_RELEASE_TAG;
    }

    /** The last release seen, without asking GitHub. */
    @Nullable
    public static Release cached(@NonNull Context context) {
        SharedPreferences p = prefs(context);
        String tag = p.getString(KEY_LATEST_TAG, null);
        String url = p.getString(KEY_LATEST_URL, null);
        return tag != null && url != null ? new Release(tag, url, p.getLong(KEY_LATEST_SIZE, -1)) : null;
    }

    /** True when the last release seen is not this build (a computer build never counts as old). */
    public static boolean hasUpdate(@NonNull Context context) {
        Release latest = cached(context);
        String installed = installedTag();
        return latest != null && !installed.isEmpty() && !latest.tag.equals(installed);
    }

    public static boolean isCheckDue(@NonNull Context context) {
        return System.currentTimeMillis() - prefs(context).getLong(KEY_CHECKED_AT, 0) > DAY;
    }

    /** Asks GitHub for the latest release. */
    @WorkerThread
    @NonNull
    public static Release check(@NonNull Context context) throws IOException {
        HttpURLConnection c = open(LATEST);
        c.setRequestProperty("Accept", "application/vnd.github+json");
        try {
            if (c.getResponseCode() != 200) {
                throw new IOException("GitHub: HTTP " + c.getResponseCode());
            }
            JSONObject json = new JSONObject(readAll(c.getInputStream()));
            String tag = json.getString("tag_name");
            JSONArray assets = json.getJSONArray("assets");
            String wanted = "NewTube-Android-Auto-" + abi() + ".apk";
            String url = null;
            long size = -1;
            for (int i = 0; i < assets.length(); i++) {
                JSONObject a = assets.getJSONObject(i);
                String name = a.optString("name");
                if (name.equals(wanted) || (url == null && name.equals("NewTube-Android-Auto-universal.apk"))) {
                    url = a.getString("browser_download_url");
                    size = a.optLong("size", -1);
                }
            }
            if (url == null) {
                throw new IOException("No APK for " + abi() + " in " + tag);
            }
            prefs(context).edit()
                    .putString(KEY_LATEST_TAG, tag)
                    .putString(KEY_LATEST_URL, url)
                    .putLong(KEY_LATEST_SIZE, size)
                    .putLong(KEY_CHECKED_AT, System.currentTimeMillis())
                    .apply();
            return new Release(tag, url, size);
        } catch (org.json.JSONException e) {
            throw new IOException(e);
        } finally {
            c.disconnect();
        }
    }

    /** Downloads the release's APK into the app's cache (one file, replaced each time). */
    @WorkerThread
    @NonNull
    public static File download(@NonNull Context context, @NonNull Release release,
                                @NonNull DownloadProgress progress) throws IOException {
        File dir = new File(context.getCacheDir(), "car_update");
        //noinspection ResultOfMethodCallIgnored
        dir.mkdirs();
        File part = new File(dir, "update.apk.part");
        File apk = new File(dir, "update.apk");
        HttpURLConnection c = open(release.apkUrl);
        try {
            if (c.getResponseCode() != 200) {
                throw new IOException("GitHub: HTTP " + c.getResponseCode());
            }
            long total = c.getContentLengthLong() > 0 ? c.getContentLengthLong() : release.size;
            try (InputStream in = c.getInputStream(); OutputStream out = new FileOutputStream(part)) {
                byte[] buffer = new byte[64 * 1024];
                long done = 0;
                int n;
                while ((n = in.read(buffer)) != -1) {
                    out.write(buffer, 0, n);
                    done += n;
                    progress.onProgress(done, total);
                }
            }
        } finally {
            c.disconnect();
        }
        if (!part.renameTo(apk)) {
            throw new IOException("Could not save the update");
        }
        return apk;
    }

    /** Frees the space of a downloaded update (called once it is no longer needed). */
    public static void clearDownload(@NonNull Context context) {
        File dir = new File(context.getCacheDir(), "car_update");
        File[] files = dir.listFiles();
        if (files != null) {
            for (File f : files) {
                //noinspection ResultOfMethodCallIgnored
                f.delete();
            }
        }
    }

    @NonNull
    static String abi() {
        for (String abi : Build.SUPPORTED_ABIS) {
            if ("arm64-v8a".equals(abi) || "armeabi-v7a".equals(abi)) {
                return abi;
            }
        }
        return "universal";
    }

    private static HttpURLConnection open(String url) throws IOException {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(15_000);
        c.setReadTimeout(30_000);
        c.setInstanceFollowRedirects(true);
        c.setRequestProperty("User-Agent", "NewTube-Android-Auto");
        return c;
    }

    private static String readAll(InputStream in) throws IOException {
        try (InputStream input = in) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buffer = new byte[8192];
            int n;
            while ((n = input.read(buffer)) != -1) {
                out.write(buffer, 0, n);
            }
            return out.toString(StandardCharsets.UTF_8.name());
        }
    }

    private static SharedPreferences prefs(Context context) {
        return context.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }
}
