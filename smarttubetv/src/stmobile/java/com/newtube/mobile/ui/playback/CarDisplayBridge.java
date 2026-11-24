package com.newtube.mobile.ui.playback;

import android.view.Surface;

import androidx.annotation.Nullable;
import androidx.media3.exoplayer.ExoPlayer;

import androidx.annotation.NonNull;

import com.liskovsoft.smartyoutubetv2.common.app.models.data.Video;

import java.lang.ref.WeakReference;

/**
 * Process-wide access to the live playback session for the Android Auto screen.
 *
 * <p>The car app runs in the same process as {@link MobilePlaybackActivity}, which owns the only
 * {@link ExoPlayer}. The car screen therefore does not create a second player: it hands the
 * host-provided {@link Surface} to the live one and gets the phone's session surface back when
 * the car screen goes away. The activity is held weakly so a destroyed player cannot be revived
 * through this class.</p>
 *
 * <p>The host's surface outlives the activity on purpose: a video started on the phone after the
 * car screen is already open must land on the car display as soon as the activity registers
 * itself here (see {@link #attach(MobilePlaybackActivity)}).</p>
 *
 * <p>A video another app (Nuvio, Stremio...) opened in NewTube as its external player is an
 * {@link ExternalSession}. While one is open it has the car screen; when it closes the car goes
 * back to the NewTube player, if there is one.</p>
 */
public final class CarDisplayBridge {
    /** A video playing outside the NewTube player (see ExternalVideoActivity). */
    public interface ExternalSession {
        void attachCarSurface(@NonNull Surface surface);

        void detachCarSurface();

        @Nullable
        ExoPlayer getCarPlayer();

        @Nullable
        String getCarTitle();

        /** Changes when another video starts. */
        @Nullable
        String getCarKey();

        boolean isCarUnplayable();

        void togglePlayPauseFromCar();

        void seekByFromCar(long deltaMs);
    }

    private static WeakReference<MobilePlaybackActivity> sActivity = new WeakReference<>(null);
    private static WeakReference<ExternalSession> sExternal = new WeakReference<>(null);
    private static Surface sCarSurface;

    private CarDisplayBridge() {
    }

    static void attach(MobilePlaybackActivity activity) {
        sActivity = new WeakReference<>(activity);
        if (sExternal.get() == null && sCarSurface != null && sCarSurface.isValid()) {
            activity.attachCarSurface(sCarSurface);
        }
    }

    static void detach(MobilePlaybackActivity activity) {
        if (sActivity.get() == activity) {
            sActivity = new WeakReference<>(null);
        }
    }

    /** An external video opened: it takes the car screen from the NewTube player. */
    public static void attachExternal(@NonNull ExternalSession session) {
        ExternalSession previous = sExternal.get();
        sExternal = new WeakReference<>(session);
        if (sCarSurface == null || !sCarSurface.isValid()) {
            return;
        }
        if (previous != null && previous != session) {
            previous.detachCarSurface();
        } else if (previous == null) {
            MobilePlaybackActivity activity = sActivity.get();
            if (activity != null) {
                activity.detachCarSurface();
            }
        }
        session.attachCarSurface(sCarSurface);
    }

    /** The external video closed: the car screen goes back to the NewTube player. */
    public static void detachExternal(@NonNull ExternalSession session) {
        if (sExternal.get() != session) {
            return;
        }
        sExternal = new WeakReference<>(null);
        if (sCarSurface != null && sCarSurface.isValid()) {
            session.detachCarSurface();
            MobilePlaybackActivity activity = sActivity.get();
            if (activity != null) {
                activity.attachCarSurface(sCarSurface);
            }
        }
    }

    /**
     * A NewTube video is starting (on the phone or from the car menu): the newest video wins, so
     * an external video still open behind it gives the car screen back and pauses. Nothing
     * happens when no external video is open.
     */
    public static void releaseExternal() {
        ExternalSession external = sExternal.get();
        if (external == null) {
            return;
        }
        sExternal = new WeakReference<>(null);
        ExoPlayer player = external.getCarPlayer();
        if (player != null) {
            player.pause();
        }
        if (sCarSurface != null && sCarSurface.isValid()) {
            external.detachCarSurface();
            MobilePlaybackActivity activity = sActivity.get();
            if (activity != null) {
                activity.attachCarSurface(sCarSurface);
            }
        }
    }

    public static void attachCarSurface(@Nullable Surface surface) {
        if (surface == null || !surface.isValid()) {
            return;
        }
        sCarSurface = surface;
        ExternalSession external = sExternal.get();
        if (external != null) {
            external.attachCarSurface(surface);
            return;
        }
        MobilePlaybackActivity activity = sActivity.get();
        if (activity != null) {
            activity.attachCarSurface(surface);
        }
    }

    public static void detachCarSurface(@Nullable Surface surface) {
        if (surface == null || sCarSurface == null || surface != sCarSurface) {
            return;
        }
        sCarSurface = null;
        ExternalSession external = sExternal.get();
        if (external != null) {
            external.detachCarSurface();
            return;
        }
        MobilePlaybackActivity activity = sActivity.get();
        if (activity != null) {
            activity.detachCarSurface();
        }
    }

    @Nullable
    public static ExoPlayer getPlayer() {
        ExternalSession external = sExternal.get();
        if (external != null) {
            return external.getCarPlayer();
        }
        MobilePlaybackActivity activity = sActivity.get();
        return activity != null ? activity.getSharedPlayer() : null;
    }

    /** What is playing. An external video is described by a Video with its title only. */
    @Nullable
    public static Video getVideo() {
        ExternalSession external = sExternal.get();
        if (external != null) {
            if (external.getCarPlayer() == null) {
                return null;
            }
            Video video = new Video();
            video.title = external.getCarTitle();
            String key = external.getCarKey();
            video.videoId = "external:" + (key != null ? key.hashCode() : 0);
            video.isUnplayable = external.isCarUnplayable();
            return video;
        }
        MobilePlaybackActivity activity = sActivity.get();
        return activity != null ? activity.getVideo() : null;
    }

    /** True while the car shows a video from another app (no NewTube queue, no YouTube ids). */
    public static boolean isExternal() {
        return sExternal.get() != null;
    }

    public static void togglePlayPause() {
        ExternalSession external = sExternal.get();
        if (external != null) {
            external.togglePlayPauseFromCar();
            return;
        }
        MobilePlaybackActivity activity = sActivity.get();
        if (activity != null) {
            activity.togglePlayPauseFromCar();
        }
    }

    public static void seekBy(long deltaMs) {
        ExternalSession external = sExternal.get();
        if (external != null) {
            external.seekByFromCar(deltaMs);
            return;
        }
        MobilePlaybackActivity activity = sActivity.get();
        if (activity != null) {
            activity.seekByFromCar(deltaMs);
        }
    }

    /** No queue for external videos: the car screen then skips 10 s instead. */
    public static boolean playNext() {
        if (sExternal.get() != null) {
            return false;
        }
        MobilePlaybackActivity activity = sActivity.get();
        return activity != null && activity.playNextFromCar();
    }

    public static boolean playPrevious() {
        if (sExternal.get() != null) {
            return false;
        }
        MobilePlaybackActivity activity = sActivity.get();
        return activity != null && activity.playPreviousFromCar();
    }
}
