package com.newtube.mobile.player;

/**
 * NEWTUBE(car): the video quality cap while the video is on the Android Auto screen.
 *
 * <p>The phone fetches what fits the car screen, at most 720p: a car display is rarely bigger
 * than that, so anything above is mobile data spent on pixels nobody sees (1080p60 is about
 * 11 Mbit/s, 720p about half). It reuses the PiP rung cap ({@link VideoViewportCap}), so the
 * buffer is kept and the switch is an ordinary quality change, never a stall. The phone screen
 * is not affected once the car screen is gone.</p>
 */
public final class CarVideoCap {
    /** The largest picture worth fetching for a car: 720p. */
    static final int MAX_WIDTH = 1280;
    static final int MAX_HEIGHT = 720;

    private CarVideoCap() {
    }

    /** The car screen is {@code width x height} px; fetch no more than that, nor above 720p. */
    public static void set(int width, int height) {
        int[] box = box(width, height);
        if (box != null) {
            VideoViewportCap.shared().setCar(box[0], box[1]);
        }
    }

    public static void clear() {
        VideoViewportCap.shared().clearCar();
    }

    /** Pure: the screen clamped to the 720p box, or null for an unknown size. */
    static int[] box(int width, int height) {
        if (width <= 0 || height <= 0) {
            return null;
        }
        return new int[] {Math.min(width, MAX_WIDTH), Math.min(height, MAX_HEIGHT)};
    }
}
