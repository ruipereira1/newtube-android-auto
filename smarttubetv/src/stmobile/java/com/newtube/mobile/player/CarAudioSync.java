package com.newtube.mobile.player;

import androidx.annotation.NonNull;
import androidx.media3.exoplayer.audio.AudioSink;
import androidx.media3.exoplayer.audio.ForwardingAudioSink;

/**
 * NEWTUBE(car): lines the sound up with the picture on the Android Auto screen.
 *
 * <p>In the car the sound travels to the head unit on its own path (and, wireless, with its own
 * buffer), which can reach the speakers later than the picture reaches the screen. The player's
 * clock is the audio position, so holding that clock back by the same amount makes the video wait
 * for the sound: {@link Sink} reports a position {@link #delayUs()} behind the real one. media3 never
 * moves its clock backwards, so turning the delay on freezes the picture for that long once, and
 * from then on picture and sound match. Only while the video is on the car screen; the phone
 * screen is never affected.</p>
 */
public final class CarAudioSync {
    /** Steps the car menu offers, in milliseconds. */
    public static final int STEP_MS = 100;
    public static final int MAX_MS = 600;

    private static volatile long sDelayUs;
    private static volatile boolean sOnCar;

    private CarAudioSync() {
    }

    /** How late the car plays the sound, in ms (0 = no correction). */
    public static void setDelayMs(int delayMs) {
        sDelayUs = Math.max(0, Math.min(MAX_MS, delayMs)) * 1000L;
    }

    /** The video is on the car screen (true) or back on the phone (false). */
    public static void setOnCar(boolean onCar) {
        sOnCar = onCar;
    }

    static long delayUs() {
        return sOnCar ? sDelayUs : 0;
    }

    /** The stock audio sink, with its position held back while the car needs it. */
    static final class Sink extends ForwardingAudioSink {
        Sink(@NonNull AudioSink sink) {
            super(sink);
        }

        @Override
        public long getCurrentPositionUs(boolean sourceEnded) {
            long position = super.getCurrentPositionUs(sourceEnded);
            long delay = delayUs();
            if (delay <= 0 || position == AudioSink.CURRENT_POSITION_NOT_SET) {
                return position;
            }
            return Math.max(0, position - delay);
        }
    }
}
