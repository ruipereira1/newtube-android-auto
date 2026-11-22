package com.newtube.mobile.car;

import android.os.Handler;
import android.os.Looper;
import android.view.Surface;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.car.app.AppManager;
import androidx.car.app.CarContext;
import androidx.car.app.Screen;
import androidx.car.app.SurfaceCallback;
import androidx.car.app.SurfaceContainer;
import androidx.car.app.model.Action;
import androidx.car.app.model.ActionStrip;
import androidx.car.app.model.CarIcon;
import androidx.car.app.model.MessageTemplate;
import androidx.car.app.model.Template;
import androidx.car.app.navigation.model.NavigationTemplate;
import androidx.core.graphics.drawable.IconCompat;
import androidx.lifecycle.DefaultLifecycleObserver;
import androidx.lifecycle.LifecycleOwner;
import androidx.media3.exoplayer.ExoPlayer;

import com.liskovsoft.smartyoutubetv2.common.app.models.data.Video;
import com.liskovsoft.smartyoutubetv2.tv.R;
import com.newtube.mobile.ui.playback.CarDisplayBridge;

import java.util.Objects;

/**
 * The Android Auto screen.
 *
 * <p>A {@link NavigationTemplate} is used because the host only exposes a drawing surface to
 * navigation apps. The surface is filled with the live NewTube player (not a map): the activity
 * that owns the player hands it over through {@link CarDisplayBridge}. The template's action
 * strip provides play/pause, next and previous; a tap or a horizontal fling on the video toggles
 * playback or seeks by ten seconds on hosts that forward surface gestures.</p>
 *
 * <p>Video is shown when the passenger mode switch is on ({@link CarModePrefs}). It is on by
 * default and can be turned off from Settings or from the car screen, in which case the screen
 * stays a plain message instead of exposing moving pictures to the driver.</p>
 */
public class CarVideoScreen extends Screen implements SurfaceCallback {
    private static final long SEEK_STEP_MS = 10_000;
    private static final long REFRESH_MS = 1_000;

    private final Handler mHandler = new Handler(Looper.getMainLooper());
    @Nullable
    private Surface mSurface;
    private boolean mPolling;
    private boolean mLastPassengerMode;
    private boolean mLastHasPlayer;
    private boolean mLastPlayWhenReady;
    @Nullable
    private String mLastVideoId;

    private final Runnable mRefresh = new Runnable() {
        @Override
        public void run() {
            if (!mPolling) {
                return;
            }
            refreshIfChanged();
            mHandler.postDelayed(this, REFRESH_MS);
        }
    };

    public CarVideoScreen(@NonNull CarContext carContext) {
        super(carContext);
        mLastPassengerMode = CarModePrefs.isPassengerModeEnabled(carContext);
        carContext.getCarService(AppManager.class).setSurfaceCallback(this);
        getLifecycle().addObserver(new DefaultLifecycleObserver() {
            @Override
            public void onStart(@NonNull LifecycleOwner owner) {
                startPolling();
            }

            @Override
            public void onStop(@NonNull LifecycleOwner owner) {
                stopPolling();
            }

            @Override
            public void onDestroy(@NonNull LifecycleOwner owner) {
                destroy();
            }
        });
    }

    @NonNull
    @Override
    public Template onGetTemplate() {
        CarContext context = getCarContext();

        if (!CarModePrefs.isPassengerModeEnabled(context)) {
            return message(context.getString(R.string.car_passenger_mode_off))
                    .addAction(new Action.Builder()
                            .setTitle(context.getString(R.string.car_enable_passenger_mode))
                            .setOnClickListener(() -> {
                                CarModePrefs.setPassengerModeEnabled(context, true);
                                mLastPassengerMode = true;
                                invalidate();
                            })
                            .build())
                    .build();
        }

        ExoPlayer player = CarDisplayBridge.getPlayer();
        Video video = CarDisplayBridge.getVideo();
        if (player == null || video == null) {
            return message(context.getString(R.string.car_start_video)).build();
        }

        boolean playWhenReady = player.getPlayWhenReady();
        return new NavigationTemplate.Builder()
                .setActionStrip(new ActionStrip.Builder()
                        .addAction(action(R.drawable.ic_player_previous,
                                context.getString(R.string.car_previous), this::previous))
                        .addAction(action(playWhenReady
                                        ? R.drawable.ic_player_pause
                                        : R.drawable.ic_player_play,
                                context.getString(playWhenReady
                                        ? R.string.car_pause
                                        : R.string.car_play),
                                CarDisplayBridge::togglePlayPause))
                        .addAction(action(R.drawable.ic_player_next,
                                context.getString(R.string.car_next), this::next))
                        .build())
                .build();
    }

    private MessageTemplate.Builder message(String text) {
        return new MessageTemplate.Builder(text);
    }

    private Action action(int iconRes, String title, Runnable onClick) {
        return new Action.Builder()
                .setIcon(new CarIcon.Builder(
                        IconCompat.createWithResource(getCarContext(), iconRes)).build())
                .setTitle(title)
                .setOnClickListener(onClick::run)
                .build();
    }

    private void previous() {
        if (!CarDisplayBridge.playPrevious()) {
            CarDisplayBridge.seekBy(-SEEK_STEP_MS);
        }
        invalidate();
    }

    private void next() {
        if (!CarDisplayBridge.playNext()) {
            CarDisplayBridge.seekBy(SEEK_STEP_MS);
        }
        invalidate();
    }

    private void refreshIfChanged() {
        boolean passengerMode = CarModePrefs.isPassengerModeEnabled(getCarContext());
        ExoPlayer player = CarDisplayBridge.getPlayer();
        Video video = CarDisplayBridge.getVideo();
        boolean hasPlayer = player != null && video != null;
        boolean playWhenReady = player != null && player.getPlayWhenReady();
        String videoId = video != null ? video.videoId : null;

        if (passengerMode != mLastPassengerMode
                || hasPlayer != mLastHasPlayer
                || playWhenReady != mLastPlayWhenReady
                || !Objects.equals(videoId, mLastVideoId)) {
            mLastPassengerMode = passengerMode;
            mLastHasPlayer = hasPlayer;
            mLastPlayWhenReady = playWhenReady;
            mLastVideoId = videoId;
            invalidate();
        }
    }

    private void startPolling() {
        if (mPolling) {
            return;
        }
        mPolling = true;
        mHandler.post(mRefresh);
    }

    private void stopPolling() {
        mPolling = false;
        mHandler.removeCallbacks(mRefresh);
    }

    private void destroy() {
        stopPolling();
        try {
            getCarContext().getCarService(AppManager.class).setSurfaceCallback(null);
        } catch (RuntimeException e) {
            // The host may already be gone; the callback dies with it.
        }
        detachSurface();
    }

    @Override
    public void onSurfaceAvailable(@NonNull SurfaceContainer surfaceContainer) {
        Surface surface = surfaceContainer.getSurface();
        if (surface == null || !surface.isValid()) {
            return;
        }
        mSurface = surface;
        CarDisplayBridge.attachCarSurface(surface);
    }

    @Override
    public void onSurfaceDestroyed(@NonNull SurfaceContainer surfaceContainer) {
        // The binder may hand back a different Surface wrapper for the same native surface, so
        // identity alone is not enough: an invalid mSurface is also ours to drop.
        if (mSurface != null
                && (surfaceContainer.getSurface() == mSurface || !mSurface.isValid())) {
            detachSurface();
        }
    }

    @Override
    public void onClick(float x, float y) {
        CarDisplayBridge.togglePlayPause();
        invalidate();
    }

    @Override
    public void onFling(float velocityX, float velocityY) {
        if (Math.abs(velocityX) < Math.abs(velocityY)) {
            return;
        }
        CarDisplayBridge.seekBy(velocityX < 0 ? SEEK_STEP_MS : -SEEK_STEP_MS);
        invalidate();
    }

    private void detachSurface() {
        Surface surface = mSurface;
        mSurface = null;
        CarDisplayBridge.detachCarSurface(surface);
    }
}
