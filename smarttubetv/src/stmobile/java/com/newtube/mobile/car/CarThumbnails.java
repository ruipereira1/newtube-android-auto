package com.newtube.mobile.car;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.text.TextUtils;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.liskovsoft.smartyoutubetv2.common.app.models.data.Video;
import com.liskovsoft.smartyoutubetv2.common.app.models.playback.service.VideoStateService;

import java.util.Locale;

/**
 * Extra information drawn onto a car thumbnail: the length of the video, a red bar for the part
 * already watched and a "LIVE" label.
 *
 * <p>Android Auto rows and grid items allow a title and one or two short lines of text only, so
 * this information goes into the picture itself, as on YouTube. Because it is part of the bitmap
 * it looks the same on every car and in every template.</p>
 */
final class CarThumbnails {
    /** Watched up to here (or more) counts as "watched to the end". */
    static final float WATCHED_TO_END = 0.95f;
    private static final int RED = Color.rgb(0xFF, 0x4E, 0x45);

    private CarThumbnails() {
    }

    /**
     * How much of the video was watched, 0..1, or -1 when unknown: what the phone remembers
     * first (exact, also for videos never synced to YouTube), else what YouTube reports.
     */
    static float watchedFraction(@NonNull Context context, @NonNull Video video) {
        VideoStateService.State state = state(context, video);
        if (state != null && state.durationMs > 0) {
            return clamp(state.positionMs / (float) state.durationMs);
        }
        return video.percentWatched > 0 ? clamp(video.percentWatched / 100f) : -1;
    }

    /** What the phone remembers about this video (position and length), or null. */
    @Nullable
    static VideoStateService.State state(@NonNull Context context, @NonNull Video video) {
        if (video.videoId == null) {
            return null;
        }
        try {
            return VideoStateService.instance(context).getByVideoId(video.videoId);
        } catch (RuntimeException e) {
            return null;
        }
    }

    /** A copy of {@code source} with the length, the watched bar and the live label drawn on it. */
    @NonNull
    static Bitmap decorate(@NonNull Bitmap source, @NonNull Video video, float watched,
            @NonNull String liveLabel) {
        // The car draws list and grid images in a square box: a 16:9 picture ends up a thin strip
        // in the middle. The centre square of it fills the box (the labels go on after the crop).
        int side = Math.min(source.getWidth(), source.getHeight());
        Bitmap square = Bitmap.createBitmap(source, (source.getWidth() - side) / 2,
                (source.getHeight() - side) / 2, side, side);
        Bitmap bitmap = square.copy(Bitmap.Config.ARGB_8888, true);
        if (bitmap == null) {
            return source;
        }
        Canvas canvas = new Canvas(bitmap);
        float width = bitmap.getWidth();
        float height = bitmap.getHeight();
        float unit = height / 180f; // sizes below are for a 180-pixel-high picture

        boolean hasBar = watched > 0 && !video.isLive;
        float barHeight = 8 * unit;
        if (hasBar) {
            Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
            paint.setColor(Color.argb(110, 255, 255, 255));
            canvas.drawRect(0, height - barHeight, width, height, paint);
            paint.setColor(RED);
            canvas.drawRect(0, height - barHeight, width * clamp(watched), height, paint);
        }

        if (video.isLive) {
            label(canvas, liveLabel.toUpperCase(Locale.getDefault()), RED, unit,
                    8 * unit, 8 * unit, false);
        } else if (!TextUtils.isEmpty(video.badge) && looksLikeLength(video.badge)) {
            float bottom = height - (hasBar ? barHeight : 0) - 8 * unit;
            label(canvas, video.badge, Color.argb(210, 0, 0, 0), unit,
                    width - 8 * unit, bottom, true);
        }
        return bitmap;
    }

    /** A small rounded label; anchored at its top-left, or at its bottom-right when {@code fromEnd}. */
    private static void label(Canvas canvas, String text, int background, float unit,
            float x, float y, boolean fromEnd) {
        Paint textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        textPaint.setColor(Color.WHITE);
        textPaint.setTextSize(24 * unit);
        textPaint.setTypeface(Typeface.create(Typeface.DEFAULT, Typeface.BOLD));
        Paint.FontMetrics metrics = textPaint.getFontMetrics();
        float padX = 8 * unit;
        float padY = 3 * unit;
        float boxWidth = textPaint.measureText(text) + 2 * padX;
        float boxHeight = (metrics.descent - metrics.ascent) + 2 * padY;
        float left = fromEnd ? x - boxWidth : x;
        float top = fromEnd ? y - boxHeight : y;

        Paint boxPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        boxPaint.setColor(background);
        float radius = 6 * unit;
        canvas.drawRoundRect(new RectF(left, top, left + boxWidth, top + boxHeight),
                radius, radius, boxPaint);
        canvas.drawText(text, left + padX, top + padY - metrics.ascent, textPaint);
    }

    /** "12:34" or "1:02:03": the badge is the length, not another label (e.g. "SHORTS"). */
    private static boolean looksLikeLength(String badge) {
        return badge.matches("\\d{1,2}(:\\d{2}){1,2}");
    }

    /** 1:05, 12:34 or 1:02:03. */
    static String formatTime(long ms) {
        long total = Math.max(0, ms) / 1000;
        long hours = total / 3600;
        long minutes = (total / 60) % 60;
        long seconds = total % 60;
        return hours > 0
                ? String.format(Locale.ROOT, "%d:%02d:%02d", hours, minutes, seconds)
                : String.format(Locale.ROOT, "%d:%02d", minutes, seconds);
    }

    private static float clamp(float value) {
        return Math.max(0f, Math.min(1f, value));
    }
}
