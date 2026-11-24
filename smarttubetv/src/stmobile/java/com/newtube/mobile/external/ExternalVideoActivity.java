package com.newtube.mobile.external;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.net.Uri;
import android.os.Bundle;
import android.os.Parcelable;
import android.text.TextUtils;
import android.view.Surface;
import android.view.View;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.media3.common.AudioAttributes;
import androidx.media3.common.C;
import androidx.media3.common.MediaItem;
import androidx.media3.common.MimeTypes;
import androidx.media3.common.PlaybackException;
import androidx.media3.common.Player;
import androidx.media3.datasource.DefaultDataSource;
import androidx.media3.datasource.DefaultHttpDataSource;
import androidx.media3.exoplayer.DefaultRenderersFactory;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory;
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector;
import androidx.media3.ui.PlayerView;

import com.newtube.mobile.ui.playback.CarDisplayBridge;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * NewTube Auto as an external video player, for apps such as Nuvio or Stremio ("open in another
 * player"). It plays the link the other app sends (with its headers, subtitles and resume
 * position) on the phone, and on the Android Auto screen when that is open (see
 * {@link CarDisplayBridge}). When it closes it tells the other app where playback stopped, the
 * way MX Player and VLC do, so "continue watching" stays right there.
 */
public class ExternalVideoActivity extends Activity implements CarDisplayBridge.ExternalSession {
    /** The car screen shows at most 720p: no point fetching more while it is the only screen. */
    private static final int CAR_MAX_WIDTH = 1280;
    private static final int CAR_MAX_HEIGHT = 720;

    @Nullable
    private ExoPlayer mPlayer;
    @Nullable
    private DefaultTrackSelector mTrackSelector;
    private PlayerView mPlayerView;
    private TextView mCarNote;
    @Nullable
    private Surface mCarSurface;
    @Nullable
    private String mTitle;
    @Nullable
    private String mSourceKey;
    private boolean mEnded;
    private boolean mFailed;
    private boolean mStopped;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Color.BLACK);
        mPlayerView = new PlayerView(this);
        mPlayerView.setShutterBackgroundColor(Color.BLACK);
        mPlayerView.setKeepContentOnPlayerReset(true);
        mPlayerView.setShowSubtitleButton(true);
        root.addView(mPlayerView, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));

        mCarNote = new TextView(this);
        mCarNote.setText(com.liskovsoft.smartyoutubetv2.tv.R.string.external_on_car);
        mCarNote.setTextColor(Color.WHITE);
        mCarNote.setTextSize(16);
        mCarNote.setGravity(android.view.Gravity.CENTER);
        mCarNote.setVisibility(View.GONE);
        root.addView(mCarNote, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT,
                android.view.Gravity.CENTER));
        setContentView(root);

        if (!open(getIntent())) {
            finishWithResult();
        }
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        // A new video from the other app replaces the current one.
        releasePlayer();
        if (!open(intent)) {
            finishWithResult();
        }
    }

    private boolean open(@Nullable Intent intent) {
        Uri uri = intent != null ? intent.getData() : null;
        if (uri == null) {
            return false;
        }
        mEnded = false;
        mFailed = false;
        mTitle = firstNonEmpty(intent.getStringExtra("title"),
                intent.getStringExtra(Intent.EXTRA_TITLE), uri.getLastPathSegment());
        mSourceKey = uri.toString();

        Map<String, String> headers = parseHeaders(intent);
        DefaultHttpDataSource.Factory http = new DefaultHttpDataSource.Factory()
                .setAllowCrossProtocolRedirects(true)
                .setConnectTimeoutMs(15_000)
                .setReadTimeoutMs(20_000);
        String userAgent = headers.remove("User-Agent");
        if (userAgent != null) {
            http.setUserAgent(userAgent);
        }
        if (!headers.isEmpty()) {
            http.setDefaultRequestProperties(headers);
        }
        DefaultDataSource.Factory data = new DefaultDataSource.Factory(this, http);

        mTrackSelector = new DefaultTrackSelector(this);
        // Prefer the phone's language for audio and subtitles, as other players do.
        String language = Locale.getDefault().getLanguage();
        mTrackSelector.setParameters(mTrackSelector.buildUponParameters()
                .setPreferredAudioLanguage(language)
                .setPreferredTextLanguage(language));

        DefaultRenderersFactory renderers = new DefaultRenderersFactory(this)
                .setEnableDecoderFallback(true)
                .setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_PREFER);
        ExoPlayer player = new ExoPlayer.Builder(this, renderers)
                .setTrackSelector(mTrackSelector)
                .setMediaSourceFactory(new DefaultMediaSourceFactory(data))
                .build();
        player.setAudioAttributes(new AudioAttributes.Builder()
                .setUsage(C.USAGE_MEDIA)
                .setContentType(C.AUDIO_CONTENT_TYPE_MOVIE)
                .build(), true);
        player.setHandleAudioBecomingNoisy(true);
        player.addListener(new Player.Listener() {
            @Override
            public void onPlaybackStateChanged(int state) {
                if (state == Player.STATE_ENDED) {
                    mEnded = true;
                    finishWithResult();
                }
            }

            @Override
            public void onPlayerError(@NonNull PlaybackException error) {
                mFailed = true;
            }
        });
        mPlayer = player;

        MediaItem.Builder item = new MediaItem.Builder().setUri(uri);
        String mime = mimeFor(intent.getType(), uri.toString());
        if (mime != null) {
            item.setMimeType(mime);
        }
        List<MediaItem.SubtitleConfiguration> subtitles = parseSubtitles(intent);
        if (!subtitles.isEmpty()) {
            item.setSubtitleConfigurations(subtitles);
        }
        long position = resumePosition(intent);
        if (position > 0) {
            player.setMediaItem(item.build(), position);
        } else {
            player.setMediaItem(item.build());
        }
        player.prepare();
        player.setPlayWhenReady(true);

        if (mCarSurface != null && mCarSurface.isValid()) {
            showOnCar(mCarSurface);
        } else {
            mPlayerView.setPlayer(player);
        }
        CarDisplayBridge.attachExternal(this);
        return true;
    }

    @Override
    protected void onStart() {
        super.onStart();
        mStopped = false;
    }

    @Override
    protected void onStop() {
        super.onStop();
        mStopped = true;
        // In the car the phone is usually locked: keep playing while the car screen shows it.
        if (mPlayer != null && !isOnCar()) {
            mPlayer.pause();
        }
    }

    @Override
    public void onBackPressed() {
        setPlaybackResult();
        super.onBackPressed();
    }

    @Override
    protected void onDestroy() {
        CarDisplayBridge.detachExternal(this);
        releasePlayer();
        super.onDestroy();
    }

    /** Tells the other app where playback stopped (MX Player / VLC extras), then closes. */
    private void finishWithResult() {
        setPlaybackResult();
        finish();
    }

    private void setPlaybackResult() {
        Intent result = new Intent();
        int resultCode = RESULT_CANCELED;
        if (mPlayer != null) {
            long duration = mPlayer.getDuration();
            long position = mEnded && duration > 0 ? duration : mPlayer.getCurrentPosition();
            if (duration > 0) {
                result.putExtra("duration", (int) Math.min(Integer.MAX_VALUE, duration));
                result.putExtra("extra_duration", duration);
            }
            result.putExtra("position", (int) Math.min(Integer.MAX_VALUE, position));
            result.putExtra("extra_position", position);
            result.putExtra("end_by", mEnded ? "playback_completion" : "user");
            resultCode = mFailed && position <= 0 ? RESULT_CANCELED : RESULT_OK;
        }
        setResult(resultCode, result);
    }

    private void releasePlayer() {
        ExoPlayer player = mPlayer;
        mPlayer = null;
        if (player != null) {
            mPlayerView.setPlayer(null);
            player.release();
        }
    }

    private boolean isOnCar() {
        return mCarSurface != null && mCarSurface.isValid();
    }

    private void showOnCar(@NonNull Surface surface) {
        ExoPlayer player = mPlayer;
        if (player == null) {
            return;
        }
        // One video output per player: the phone view lets go, the car screen takes it.
        mPlayerView.setPlayer(null);
        player.setVideoSurface(surface);
        mCarNote.setVisibility(View.VISIBLE);
        if (mTrackSelector != null) {
            mTrackSelector.setParameters(mTrackSelector.buildUponParameters()
                    .setMaxVideoSize(CAR_MAX_WIDTH, CAR_MAX_HEIGHT));
        }
    }

    // ---------------------------------------------------------------------------------------
    // CarDisplayBridge.ExternalSession
    // ---------------------------------------------------------------------------------------

    @Override
    public void attachCarSurface(@NonNull Surface surface) {
        mCarSurface = surface;
        showOnCar(surface);
    }

    @Override
    public void detachCarSurface() {
        Surface surface = mCarSurface;
        mCarSurface = null;
        mCarNote.setVisibility(View.GONE);
        ExoPlayer player = mPlayer;
        if (player == null) {
            return;
        }
        if (surface != null) {
            player.clearVideoSurface(surface);
        }
        if (mTrackSelector != null) {
            mTrackSelector.setParameters(mTrackSelector.buildUponParameters()
                    .clearVideoSizeConstraints());
        }
        mPlayerView.setPlayer(player);
        if (mStopped) {
            // Back to a phone nobody is looking at: don't play on behind the lock screen.
            player.pause();
        }
    }

    @Nullable
    @Override
    public ExoPlayer getCarPlayer() {
        return mPlayer;
    }

    @Nullable
    @Override
    public String getCarTitle() {
        return mTitle;
    }

    @Nullable
    @Override
    public String getCarKey() {
        return mSourceKey;
    }

    @Override
    public boolean isCarUnplayable() {
        return mFailed;
    }

    @Override
    public void togglePlayPauseFromCar() {
        if (mPlayer != null) {
            mPlayer.setPlayWhenReady(!mPlayer.getPlayWhenReady());
        }
    }

    @Override
    public void seekByFromCar(long deltaMs) {
        ExoPlayer player = mPlayer;
        if (player == null) {
            return;
        }
        long target = Math.max(0, player.getCurrentPosition() + deltaMs);
        long duration = player.getDuration();
        if (duration > 0) {
            target = Math.min(target, duration);
        }
        player.seekTo(target);
    }

    // ---------------------------------------------------------------------------------------
    // Intent extras, as MX Player / mpv / VLC read them
    // ---------------------------------------------------------------------------------------

    /** "headers": either "Key: Value" lines (Nuvio) or alternating key, value (MX Player). */
    private static Map<String, String> parseHeaders(Intent intent) {
        Map<String, String> headers = new HashMap<>();
        String[] raw = intent.getStringArrayExtra("headers");
        if (raw == null) {
            return headers;
        }
        boolean colonForm = true;
        for (String line : raw) {
            if (line == null || line.indexOf(':') <= 0) {
                colonForm = false;
                break;
            }
        }
        if (colonForm) {
            for (String line : raw) {
                int colon = line.indexOf(':');
                String key = line.substring(0, colon).trim();
                String value = line.substring(colon + 1).trim();
                if (!key.isEmpty()) {
                    headers.put(key, value);
                }
            }
        } else {
            for (int i = 0; i + 1 < raw.length; i += 2) {
                if (!TextUtils.isEmpty(raw[i]) && raw[i + 1] != null) {
                    headers.put(raw[i].trim(), raw[i + 1].trim());
                }
            }
        }
        return headers;
    }

    private static long resumePosition(Intent intent) {
        Bundle extras = intent.getExtras();
        if (extras == null) {
            return 0;
        }
        Object position = extras.get("position");
        if (position == null) {
            position = extras.get("startfrom");
        }
        if (position instanceof Number) {
            return Math.max(0, ((Number) position).longValue());
        }
        return 0;
    }

    private static List<MediaItem.SubtitleConfiguration> parseSubtitles(Intent intent) {
        List<MediaItem.SubtitleConfiguration> result = new ArrayList<>();
        Parcelable[] subs = intent.getParcelableArrayExtra("subs");
        String[] names = intent.getStringArrayExtra("subs.name");
        if (subs == null) {
            Parcelable single = intent.getParcelableExtra("subtitles_location");
            if (single instanceof Uri) {
                subs = new Parcelable[] {single};
            }
        }
        if (subs == null) {
            return result;
        }
        for (int i = 0; i < subs.length; i++) {
            if (!(subs[i] instanceof Uri)) {
                continue;
            }
            Uri uri = (Uri) subs[i];
            String name = names != null && i < names.length ? names[i] : null;
            MediaItem.SubtitleConfiguration.Builder sub =
                    new MediaItem.SubtitleConfiguration.Builder(uri)
                            .setMimeType(subtitleMime(uri.toString(), name));
            if (!TextUtils.isEmpty(name)) {
                sub.setLabel(name);
            }
            if (i == 0) {
                sub.setSelectionFlags(C.SELECTION_FLAG_DEFAULT);
            }
            result.add(sub.build());
        }
        return result;
    }

    private static String subtitleMime(String url, @Nullable String name) {
        String path = url.toLowerCase(Locale.ROOT);
        int query = path.indexOf('?');
        if (query >= 0) {
            path = path.substring(0, query);
        }
        if (path.endsWith(".vtt") || (name != null && name.toLowerCase(Locale.ROOT).endsWith(".vtt"))) {
            return MimeTypes.TEXT_VTT;
        }
        if (path.endsWith(".ass") || path.endsWith(".ssa")) {
            return MimeTypes.TEXT_SSA;
        }
        if (path.endsWith(".ttml") || path.endsWith(".dfxp")) {
            return MimeTypes.APPLICATION_TTML;
        }
        return MimeTypes.APPLICATION_SUBRIP; // what Nuvio and most addons send
    }

    /** Adaptive streams need their type; plain files are sniffed by the extractors. */
    @Nullable
    private static String mimeFor(@Nullable String type, String url) {
        String lowerType = type != null ? type.toLowerCase(Locale.ROOT) : "";
        String path = url.toLowerCase(Locale.ROOT);
        int query = path.indexOf('?');
        if (query >= 0) {
            path = path.substring(0, query);
        }
        if (lowerType.contains("mpegurl") || path.endsWith(".m3u8")) {
            return MimeTypes.APPLICATION_M3U8;
        }
        if (lowerType.contains("dash") || path.endsWith(".mpd")) {
            return MimeTypes.APPLICATION_MPD;
        }
        return null;
    }

    @Nullable
    private static String firstNonEmpty(String... values) {
        for (String value : values) {
            if (!TextUtils.isEmpty(value)) {
                return value;
            }
        }
        return null;
    }
}
