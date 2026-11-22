package com.newtube.mobile.car;

import android.graphics.Rect;
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
import androidx.car.app.navigation.model.MessageInfo;
import androidx.car.app.navigation.model.NavigationTemplate;
import androidx.core.graphics.drawable.IconCompat;
import androidx.lifecycle.DefaultLifecycleObserver;
import androidx.lifecycle.LifecycleOwner;
import androidx.media3.common.Player;
import androidx.media3.common.VideoSize;
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
 * strip provides previous, play/pause, next and Menu ({@link CarMenuScreen}). A tap on the video
 * only brings the buttons back; a horizontal fling seeks by ten seconds on hosts that forward
 * surface gestures.</p>
 *
 * <p>Video is shown when the passenger mode switch is on ({@link CarModePrefs}). It is on by
 * default and can be turned off from Settings or from the car screen, in which case the screen
 * stays a plain message instead of exposing moving pictures to the driver.</p>
 *
 * <p>The surface is detached when the screen stops and reattached when it starts, so the phone
 * gets the video back while the car app is in the background.</p>
 */
public class CarVideoScreen extends Screen implements SurfaceCallback {
    private static final long SEEK_STEP_MS = 10_000;
    private static final long REFRESH_MS = 1_000;

    private final Handler mHandler = new Handler(Looper.getMainLooper());
    /** The surface the player draws into: the letterboxed video view, or the raw host surface. */
    @Nullable
    private Surface mSurface;
    /** The whole-screen surface the host gave us. */
    @Nullable
    private Surface mHostSurface;
    @Nullable
    private CarLetterbox mLetterbox;
    /** Part of the car screen not covered by the host's own UI; null until the host reports it. */
    @Nullable
    private Rect mVisibleArea;
    private boolean mStarted;
    private boolean mPolling;
    private boolean mLastPassengerMode;
    private boolean mLastHasPlayer;
    private boolean mLastPlayWhenReady;
    @Nullable
    private String mLastVideoId;
    /** Refreshes in a row in which the player had nothing to play (error, ended live, ...). */
    private int mProblemTicks;
    private boolean mLastShowProblem;

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
                mStarted = true;
                startPolling();
                attachSurface();
            }

            @Override
            public void onStop(@NonNull LifecycleOwner owner) {
                mStarted = false;
                stopPolling();
                detachSurfaceFromPlayer();
            }

            @Override
            public void onDestroy(@NonNull LifecycleOwner owner) {
                mStarted = false;
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
                    .addAction(menuAction(context))
                    .build();
        }

        ExoPlayer player = CarDisplayBridge.getPlayer();
        Video video = CarDisplayBridge.getVideo();
        if (player == null || video == null) {
            return message(context.getString(R.string.car_start_video))
                    .addAction(menuAction(context))
                    .addAction(new Action.Builder()
                            .setTitle(context.getString(R.string.car_disable_passenger_mode))
                            .setOnClickListener(this::disablePassengerMode)
                            .build())
                    .build();
        }

        boolean playWhenReady = player.getPlayWhenReady();
        NavigationTemplate.Builder template = new NavigationTemplate.Builder();
        if (mLastShowProblem) {
            // The player has nothing to show (an ended live stream, a video that failed): say so
            // instead of leaving the car screen black. Same template, so no screen change.
            template.setNavigationInfo(new MessageInfo.Builder(
                    context.getString(R.string.car_video_unavailable)).build());
        }
        // Previous and Next are icons only: with four labelled buttons Android Auto cuts the last
        // label ("Menu") down to "...".
        return template
                .setActionStrip(new ActionStrip.Builder()
                        .addAction(action(R.drawable.ic_player_previous, null, this::previous))
                        .addAction(action(playWhenReady
                                        ? R.drawable.ic_player_pause
                                        : R.drawable.ic_player_play,
                                context.getString(playWhenReady
                                        ? R.string.car_pause
                                        : R.string.car_play),
                                CarDisplayBridge::togglePlayPause))
                        .addAction(action(R.drawable.ic_player_next, null, this::next))
                        // Passenger mode is switched from inside the menu (Android Auto allows
                        // four buttons here).
                        .addAction(action(R.drawable.ic_player_queue,
                                context.getString(R.string.car_menu), this::openMenu))
                        .build())
                .build();
    }

    private Action menuAction(CarContext context) {
        return new Action.Builder()
                .setTitle(context.getString(R.string.car_menu))
                .setOnClickListener(this::openMenu)
                .build();
    }

    /** Opens the NewTube menu (Home, Subscriptions, ...) on the car screen. */
    private void openMenu() {
        getScreenManager().push(new CarMenuScreen(getCarContext()));
    }

    private MessageTemplate.Builder message(String text) {
        return new MessageTemplate.Builder(text);
    }

    private Action action(int iconRes, @Nullable String title, Runnable onClick) {
        Action.Builder action = new Action.Builder()
                .setIcon(new CarIcon.Builder(
                        IconCompat.createWithResource(getCarContext(), iconRes)).build())
                .setOnClickListener(onClick::run);
        if (title != null) {
            action.setTitle(title);
        }
        return action.build();
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

    private void disablePassengerMode() {
        CarModePrefs.setPassengerModeEnabled(getCarContext(), false);
        mLastPassengerMode = false;
        invalidate();
    }

    private void refreshIfChanged() {
        boolean passengerMode = CarModePrefs.isPassengerModeEnabled(getCarContext());
        ExoPlayer player = CarDisplayBridge.getPlayer();
        Video video = CarDisplayBridge.getVideo();
        boolean hasPlayer = player != null && video != null;
        boolean playWhenReady = player != null && player.getPlayWhenReady();
        String videoId = video != null ? video.videoId : null;
        updateAspectRatio(player);

        // Two refreshes in a row (about two seconds), so the short idle moment while a new video
        // loads never flashes the message.
        boolean problem = player != null && (player.getPlayerError() != null
                || player.getPlaybackState() == Player.STATE_IDLE);
        mProblemTicks = problem ? mProblemTicks + 1 : 0;
        boolean showProblem = mProblemTicks >= 2;

        if (passengerMode != mLastPassengerMode
                || hasPlayer != mLastHasPlayer
                || playWhenReady != mLastPlayWhenReady
                || showProblem != mLastShowProblem
                || !Objects.equals(videoId, mLastVideoId)) {
            mLastPassengerMode = passengerMode;
            mLastHasPlayer = hasPlayer;
            mLastPlayWhenReady = playWhenReady;
            mLastShowProblem = showProblem;
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
        releaseHostSurface();
    }

    private void updateAspectRatio(@Nullable ExoPlayer player) {
        if (mLetterbox == null || player == null) {
            return;
        }
        VideoSize size = player.getVideoSize();
        if (size.width > 0 && size.height > 0) {
            mLetterbox.setVideoAspectRatio(size.width * size.pixelWidthHeightRatio / size.height);
        }
    }

    /** Drops everything tied to the host surface: the player first, then the letterbox window. */
    private void releaseHostSurface() {
        detachSurfaceFromPlayer();
        mSurface = null;
        CarLetterbox letterbox = mLetterbox;
        mLetterbox = null;
        if (letterbox != null) {
            letterbox.release();
        }
        mHostSurface = null;
    }

    @Override
    public void onSurfaceAvailable(@NonNull SurfaceContainer surfaceContainer) {
        Surface surface = surfaceContainer.getSurface();
        if (surface == null || !surface.isValid()) {
            return;
        }
        if (mHostSurface != null) {
            releaseHostSurface();
        }
        mHostSurface = surface;
        try {
            mLetterbox = new CarLetterbox(getCarContext(), surface,
                    surfaceContainer.getWidth(), surfaceContainer.getHeight(),
                    surfaceContainer.getDpi(), new CarLetterbox.Listener() {
                        @Override
                        public void onVideoSurfaceReady(@NonNull Surface videoSurface) {
                            mSurface = videoSurface;
                            updateAspectRatio(CarDisplayBridge.getPlayer());
                            if (mStarted) {
                                attachSurface();
                            }
                        }

                        @Override
                        public void onVideoSurfaceGone(@NonNull Surface videoSurface) {
                            if (mSurface == videoSurface) {
                                detachSurfaceFromPlayer();
                                mSurface = null;
                            }
                        }
                    });
            mLetterbox.setVisibleArea(mVisibleArea);
        } catch (RuntimeException e) {
            // No virtual display on this phone: fall back to the raw (stretched) host surface.
            mLetterbox = null;
            mSurface = surface;
            if (mStarted) {
                attachSurface();
            }
        }
    }

    @Override
    public void onSurfaceDestroyed(@NonNull SurfaceContainer surfaceContainer) {
        // The host owns one surface per screen, and the binder may hand back a different Surface
        // wrapper for it, so compare nothing: whatever we built on the host surface goes now,
        // before the native surface under the virtual display disappears.
        if (mHostSurface != null) {
            releaseHostSurface();
        }
    }

    @Override
    public void onVisibleAreaChanged(@NonNull Rect visibleArea) {
        mVisibleArea = new Rect(visibleArea);
        if (mLetterbox != null) {
            mLetterbox.setVisibleArea(mVisibleArea);
        }
    }

    @Override
    public void onClick(float x, float y) {
        // A tap on the video is how people bring the buttons back, so it must not also pause
        // (it used to). Pause and play are on the Pause/Play button.
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

    private void attachSurface() {
        if (mSurface != null && mSurface.isValid()) {
            CarDisplayBridge.attachCarSurface(mSurface);
        }
    }

    private void detachSurfaceFromPlayer() {
        if (mSurface != null) {
            CarDisplayBridge.detachCarSurface(mSurface);
        }
    }
}
