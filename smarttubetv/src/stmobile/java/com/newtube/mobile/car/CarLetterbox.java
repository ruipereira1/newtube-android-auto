package com.newtube.mobile.car;

import android.app.Presentation;
import android.content.Context;
import android.graphics.Color;
import android.graphics.Rect;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.view.Gravity;
import android.view.Surface;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.widget.FrameLayout;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.media3.ui.AspectRatioFrameLayout;

/**
 * Keeps the video's aspect ratio on the car display.
 *
 * <p>The host gives a raw {@link Surface} the size of the whole car screen. Handing it straight to
 * the decoder stretches a 16:9 video to the screen's shape (2:1 on the desktop head unit, wider on
 * many cars). Instead, the host surface backs a private {@link VirtualDisplay}; a
 * {@link Presentation} on that display draws a black background with a centred
 * {@link AspectRatioFrameLayout} holding a {@link SurfaceView}. The player renders into that
 * SurfaceView, so the video is letterboxed (pillarboxed on wide screens) and never distorted.</p>
 */
final class CarLetterbox {
    interface Listener {
        /** The video surface is ready: give it to the player. */
        void onVideoSurfaceReady(@NonNull Surface surface);

        /** The video surface is going away: take it from the player now. */
        void onVideoSurfaceGone(@NonNull Surface surface);
    }

    private final VirtualDisplay mDisplay;
    private final Presentation mPresentation;
    private final FrameLayout mVisibleBox;
    private final AspectRatioFrameLayout mFrame;
    @Nullable
    private Surface mVideoSurface;
    private float mAspectRatio;

    /** @throws RuntimeException if the virtual display or its window cannot be created. */
    CarLetterbox(@NonNull Context context, @NonNull Surface hostSurface, int width, int height,
            int dpi, @NonNull Listener listener) {
        DisplayManager displayManager = context.getSystemService(DisplayManager.class);
        mDisplay = displayManager.createVirtualDisplay("NewTubeCar", width, height,
                Math.max(dpi, 1), hostSurface, 0);
        if (mDisplay == null) {
            throw new IllegalStateException("No virtual display for the car surface");
        }

        try {
            mPresentation = new Presentation(context, mDisplay.getDisplay());

            FrameLayout root = new FrameLayout(mPresentation.getContext());
            root.setBackgroundColor(Color.BLACK);

            // The part of the screen the host does not cover (split screen, cards). The video is
            // centred in it rather than in the whole surface.
            mVisibleBox = new FrameLayout(mPresentation.getContext());

            mFrame = new AspectRatioFrameLayout(mPresentation.getContext());
            mFrame.setResizeMode(AspectRatioFrameLayout.RESIZE_MODE_FIT);
            mFrame.setAspectRatio(16f / 9f);

            SurfaceView videoView = new SurfaceView(mPresentation.getContext());
            videoView.getHolder().addCallback(new SurfaceHolder.Callback() {
                @Override
                public void surfaceCreated(@NonNull SurfaceHolder holder) {
                    mVideoSurface = holder.getSurface();
                    listener.onVideoSurfaceReady(mVideoSurface);
                }

                @Override
                public void surfaceChanged(@NonNull SurfaceHolder holder, int format, int w, int h) {
                }

                @Override
                public void surfaceDestroyed(@NonNull SurfaceHolder holder) {
                    Surface surface = mVideoSurface;
                    mVideoSurface = null;
                    if (surface != null) {
                        listener.onVideoSurfaceGone(surface);
                    }
                }
            });

            mFrame.addView(videoView, new FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
            mVisibleBox.addView(mFrame, new FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT,
                    Gravity.CENTER));
            root.addView(mVisibleBox, new FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));

            mPresentation.setContentView(root);
            mPresentation.show();
        } catch (RuntimeException e) {
            mDisplay.release();
            throw e;
        }
    }

    /** Width / height of the picture as it should be shown (pixel aspect ratio included). */
    void setVideoAspectRatio(float aspectRatio) {
        if (aspectRatio <= 0 || Float.isNaN(aspectRatio) || aspectRatio == mAspectRatio) {
            return;
        }
        mAspectRatio = aspectRatio;
        mFrame.setAspectRatio(aspectRatio);
    }

    /** Fits the video into this part of the surface (host coordinates); null = whole surface. */
    void setVisibleArea(@Nullable Rect area) {
        FrameLayout.LayoutParams params;
        if (area == null || area.isEmpty()) {
            params = new FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT);
        } else {
            params = new FrameLayout.LayoutParams(area.width(), area.height(),
                    Gravity.TOP | Gravity.START);
            params.leftMargin = area.left;
            params.topMargin = area.top;
        }
        mVisibleBox.setLayoutParams(params);
    }

    /** Tears down the window and the virtual display (the video surface is reported gone first). */
    void release() {
        try {
            mPresentation.dismiss();
        } catch (RuntimeException e) {
            // The display may already be gone with the host surface.
        }
        mDisplay.release();
    }
}
