package com.newtube.mobile.car;

import android.content.Context;
import android.content.SharedPreferences;

/**
 * Switch that lets the Android Auto screen keep showing video while the car is moving.
 *
 * <p>On by default. The car app then hands the player surface to the host as soon as a video is
 * playing. It can be turned off from Settings or from the car screen itself, in which case the
 * car app shows a message instead of a video surface. Only passengers may watch; the driver must
 * keep their eyes on the road.</p>
 */
public final class CarModePrefs {
    private static final String PREFS = "newtube_car";
    private static final String KEY_PASSENGER_MODE = "passenger_mode";
    private static final String KEY_GRID = "lists_as_grid";
    private static final String KEY_AUDIO_DELAY = "audio_delay_ms";

    private CarModePrefs() {
    }

    public static boolean isPassengerModeEnabled(Context context) {
        return prefs(context).getBoolean(KEY_PASSENGER_MODE, true);
    }

    public static void setPassengerModeEnabled(Context context, boolean enabled) {
        prefs(context).edit().putBoolean(KEY_PASSENGER_MODE, enabled).apply();
    }

    /** Car video lists as a grid of big thumbnails instead of a list (off by default). */
    public static boolean isGridEnabled(Context context) {
        return prefs(context).getBoolean(KEY_GRID, false);
    }

    public static void setGridEnabled(Context context, boolean enabled) {
        prefs(context).edit().putBoolean(KEY_GRID, enabled).apply();
    }

    /** How late the car plays the sound compared with the picture, in ms (0 by default). */
    public static int getAudioDelayMs(Context context) {
        return prefs(context).getInt(KEY_AUDIO_DELAY, 0);
    }

    public static void setAudioDelayMs(Context context, int delayMs) {
        prefs(context).edit().putInt(KEY_AUDIO_DELAY, delayMs).apply();
    }

    private static SharedPreferences prefs(Context context) {
        return context.getApplicationContext()
                .getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }
}
