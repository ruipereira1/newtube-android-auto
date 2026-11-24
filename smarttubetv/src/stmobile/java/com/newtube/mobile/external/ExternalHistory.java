package com.newtube.mobile.external;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Parcelable;
import android.text.TextUtils;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/**
 * The videos other apps (Nuvio, Stremio...) sent to NewTube lately, with where they stopped, so
 * the passenger can pick one again from the car screen ({@link
 * com.newtube.mobile.car.CarExternalListScreen}) without the phone.
 *
 * <p>Each entry keeps what is needed to play it again: the link, its type, the headers and the
 * subtitles the other app sent. Links from some sources expire after a while; the car screen then
 * shows the usual "video unavailable" message and the video has to be opened from the other app
 * again. Stored on the phone only.</p>
 */
public final class ExternalHistory {
    private static final String PREFS = "external_history";
    private static final String KEY = "entries";
    private static final int MAX = 15;

    public static final class Entry {
        public final String url;
        @Nullable
        public final String title;
        @Nullable
        public final String type;
        public final ArrayList<String> headers;
        public final ArrayList<String> subtitleUrls;
        public final ArrayList<String> subtitleNames;
        public long positionMs;
        public long durationMs;
        public long updatedAt;

        Entry(String url, @Nullable String title, @Nullable String type, ArrayList<String> headers,
                ArrayList<String> subtitleUrls, ArrayList<String> subtitleNames) {
            this.url = url;
            this.title = title;
            this.type = type;
            this.headers = headers;
            this.subtitleUrls = subtitleUrls;
            this.subtitleNames = subtitleNames;
        }

        /** Finished, or so close to the end that there is nothing left to watch. */
        public boolean isFinished() {
            return durationMs > 0 && positionMs >= durationMs * 0.95;
        }

        /** The same extras the other app sent, so the player opens it the same way. */
        public Intent toIntent(Context context) {
            Intent intent = new Intent(Intent.ACTION_VIEW)
                    .setClass(context, ExternalVideoActivity.class)
                    .setDataAndType(Uri.parse(url), type != null ? type : "video/*")
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            if (!TextUtils.isEmpty(title)) {
                intent.putExtra("title", title);
            }
            if (positionMs > 0 && !isFinished()) {
                intent.putExtra("position", (int) Math.min(Integer.MAX_VALUE, positionMs));
            }
            if (!headers.isEmpty()) {
                intent.putExtra("headers", headers.toArray(new String[0]));
            }
            if (!subtitleUrls.isEmpty()) {
                Parcelable[] subs = new Parcelable[subtitleUrls.size()];
                for (int i = 0; i < subs.length; i++) {
                    subs[i] = Uri.parse(subtitleUrls.get(i));
                }
                intent.putExtra("subs", subs);
                intent.putExtra("subs.name", subtitleNames.toArray(new String[0]));
            }
            return intent;
        }
    }

    private ExternalHistory() {
    }

    /** Remembers a video another app opened (or updates it, keeping its place). */
    static void remember(Context context, @NonNull Uri uri, @Nullable String title,
            @Nullable String type, @NonNull Intent intent) {
        String url = uri.toString();
        String scheme = uri.getScheme();
        if (!"http".equalsIgnoreCase(scheme) && !"https".equalsIgnoreCase(scheme)) {
            return; // a local file or another app's content link can't be opened again later
        }
        ArrayList<String> headers = new ArrayList<>();
        String[] rawHeaders = intent.getStringArrayExtra("headers");
        if (rawHeaders != null) {
            for (String header : rawHeaders) {
                if (header != null) {
                    headers.add(header);
                }
            }
        }
        ArrayList<String> subUrls = new ArrayList<>();
        ArrayList<String> subNames = new ArrayList<>();
        Parcelable[] subs = intent.getParcelableArrayExtra("subs");
        String[] names = intent.getStringArrayExtra("subs.name");
        if (subs != null) {
            for (int i = 0; i < subs.length; i++) {
                if (subs[i] instanceof Uri) {
                    String sub = subs[i].toString();
                    if (sub.startsWith("http")) {
                        subUrls.add(sub);
                        subNames.add(names != null && i < names.length && names[i] != null
                                ? names[i] : "");
                    }
                }
            }
        }

        List<Entry> entries = load(context);
        Entry previous = null;
        for (Entry entry : entries) {
            if (entry.url.equals(url) || (title != null && title.equals(entry.title))) {
                previous = entry;
                break;
            }
        }
        if (previous != null) {
            entries.remove(previous);
        }
        Entry entry = new Entry(url, title, type, headers, subUrls, subNames);
        if (previous != null) {
            entry.positionMs = previous.positionMs;
            entry.durationMs = previous.durationMs;
        }
        entry.updatedAt = System.currentTimeMillis();
        entries.add(0, entry);
        save(context, entries);
    }

    /** Where the video with this link stopped. */
    static void updatePosition(Context context, @NonNull String url, long positionMs,
            long durationMs) {
        List<Entry> entries = load(context);
        for (Entry entry : entries) {
            if (entry.url.equals(url)) {
                entry.positionMs = Math.max(0, positionMs);
                if (durationMs > 0) {
                    entry.durationMs = durationMs;
                }
                entry.updatedAt = System.currentTimeMillis();
                save(context, entries);
                return;
            }
        }
    }

    /** Newest first. */
    @NonNull
    public static List<Entry> load(Context context) {
        String json = prefs(context).getString(KEY, null);
        if (json == null) {
            return new ArrayList<>();
        }
        List<Entry> entries = new ArrayList<>();
        try {
            JSONArray array = new JSONArray(json);
            for (int i = 0; i < array.length(); i++) {
                JSONObject o = array.getJSONObject(i);
                Entry entry = new Entry(o.getString("url"), o.optString("title", null),
                        o.optString("type", null), strings(o.optJSONArray("headers")),
                        strings(o.optJSONArray("subs")), strings(o.optJSONArray("subNames")));
                entry.positionMs = o.optLong("position");
                entry.durationMs = o.optLong("duration");
                entry.updatedAt = o.optLong("updated");
                entries.add(entry);
            }
        } catch (JSONException e) {
            return new ArrayList<>();
        }
        return entries;
    }

    private static void save(Context context, List<Entry> entries) {
        JSONArray array = new JSONArray();
        try {
            for (Entry entry : entries.subList(0, Math.min(MAX, entries.size()))) {
                JSONObject o = new JSONObject();
                o.put("url", entry.url);
                o.put("title", entry.title);
                o.put("type", entry.type);
                o.put("headers", new JSONArray(entry.headers));
                o.put("subs", new JSONArray(entry.subtitleUrls));
                o.put("subNames", new JSONArray(entry.subtitleNames));
                o.put("position", entry.positionMs);
                o.put("duration", entry.durationMs);
                o.put("updated", entry.updatedAt);
                array.put(o);
            }
        } catch (JSONException e) {
            return;
        }
        prefs(context).edit().putString(KEY, array.toString()).apply();
    }

    private static ArrayList<String> strings(@Nullable JSONArray array) {
        ArrayList<String> result = new ArrayList<>();
        if (array != null) {
            for (int i = 0; i < array.length(); i++) {
                result.add(array.optString(i, ""));
            }
        }
        return result;
    }

    private static SharedPreferences prefs(Context context) {
        return context.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }
}
