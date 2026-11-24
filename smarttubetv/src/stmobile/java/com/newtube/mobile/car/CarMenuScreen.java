package com.newtube.mobile.car;

import android.content.Context;
import android.graphics.Bitmap;
import android.text.TextUtils;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.car.app.CarContext;
import androidx.car.app.Screen;
import androidx.car.app.model.Action;
import androidx.car.app.model.CarIcon;
import androidx.car.app.model.ItemList;
import androidx.car.app.model.ListTemplate;
import androidx.car.app.model.Row;
import androidx.car.app.model.Template;
import androidx.car.app.model.Toggle;
import androidx.core.graphics.drawable.IconCompat;
import androidx.lifecycle.DefaultLifecycleObserver;
import androidx.lifecycle.LifecycleOwner;

import com.bumptech.glide.Glide;
import com.liskovsoft.smartyoutubetv2.common.app.models.data.Video;
import com.liskovsoft.smartyoutubetv2.common.app.models.playback.service.VideoStateService;
import com.liskovsoft.smartyoutubetv2.common.app.presenters.PlaybackPresenter;
import com.liskovsoft.smartyoutubetv2.tv.R;
import com.newtube.mobile.ui.playback.CarDisplayBridge;

import java.util.List;
import java.util.Objects;
import java.util.concurrent.TimeUnit;

import io.reactivex.rxjava3.android.schedulers.AndroidSchedulers;
import io.reactivex.rxjava3.core.Observable;
import io.reactivex.rxjava3.disposables.Disposable;
import io.reactivex.rxjava3.schedulers.Schedulers;

/**
 * The NewTube menu on the car screen: the same main sections as the phone app (Home,
 * Subscriptions, History, Playlists, Trending) plus the passenger-mode switch.
 *
 * <p>Opened from the "Menu" button on the player screen ({@link CarVideoScreen}). Each section
 * opens a {@link CarVideoListScreen}; picking a video there starts it and returns to the player.
 * Each section has a second line saying what is in it, and the first row, "Continue watching",
 * resumes the last unfinished video (from what the phone remembers, so it needs no network).</p>
 */
public class CarMenuScreen extends Screen {
    @Nullable
    private final VideoStateService.State mResume;
    @Nullable
    private Bitmap mResumeThumbnail;
    @Nullable
    private Disposable mThumbnailLoad;

    public CarMenuScreen(@NonNull CarContext carContext) {
        super(carContext);
        mResume = lastUnfinished(carContext);
        getLifecycle().addObserver(new DefaultLifecycleObserver() {
            @Override
            public void onDestroy(@NonNull LifecycleOwner owner) {
                if (mThumbnailLoad != null) {
                    mThumbnailLoad.dispose();
                }
            }
        });
        loadResumeThumbnail();
    }

    @NonNull
    @Override
    public Template onGetTemplate() {
        CarContext context = getCarContext();
        ItemList.Builder list = new ItemList.Builder();

        if (mResume != null) {
            list.addItem(resumeRow(mResume));
        }
        list.addItem(section(R.string.car_menu_home, R.string.car_menu_home_summary,
                R.drawable.ic_nav_home, CarVideoListScreen.Source.HOME));
        list.addItem(section(R.string.car_menu_subscriptions,
                R.string.car_menu_subscriptions_summary, R.drawable.ic_nav_subscriptions,
                CarVideoListScreen.Source.SUBSCRIPTIONS));
        list.addItem(section(R.string.car_menu_history, R.string.car_menu_history_summary,
                R.drawable.ic_nav_history, CarVideoListScreen.Source.HISTORY));
        list.addItem(section(R.string.car_menu_playlists, R.string.car_menu_playlists_summary,
                R.drawable.ic_player_queue, CarVideoListScreen.Source.PLAYLISTS));
        list.addItem(section(R.string.car_menu_trending, R.string.car_menu_trending_summary,
                R.drawable.ic_car_trending, CarVideoListScreen.Source.TRENDING));

        list.addItem(new Row.Builder()
                .setTitle(context.getString(R.string.car_passenger_mode))
                .addText(context.getString(R.string.car_passenger_mode_summary))
                .setToggle(new Toggle.Builder(checked -> {
                    CarModePrefs.setPassengerModeEnabled(context, checked);
                    invalidate();
                }).setChecked(CarModePrefs.isPassengerModeEnabled(context)).build())
                .build());

        return new ListTemplate.Builder()
                .setTitle(context.getString(R.string.car_menu))
                .setHeaderAction(Action.BACK)
                .setSingleList(list.build())
                .build();
    }

    private Row section(int titleRes, int summaryRes, int iconRes,
            CarVideoListScreen.Source source) {
        CarContext context = getCarContext();
        return section(context.getString(titleRes), context.getString(summaryRes), iconRes,
                source);
    }

    private Row section(String title, String summary, int iconRes,
            CarVideoListScreen.Source source) {
        CarContext context = getCarContext();
        return new Row.Builder()
                .setTitle(title)
                .addText(summary)
                .setImage(new CarIcon.Builder(
                        IconCompat.createWithResource(context, iconRes)).build())
                .setBrowsable(true)
                .setOnClickListener(() -> getScreenManager().push(
                        new CarVideoListScreen(context, title, source)))
                .build();
    }

    /** "Continue watching": the video, "Continue watching · 18:02 left" and its thumbnail. */
    private Row resumeRow(VideoStateService.State state) {
        CarContext context = getCarContext();
        String left = CarThumbnails.formatTime(state.durationMs - state.positionMs);
        Row.Builder row = new Row.Builder()
                .setTitle(state.video.title)
                .addText(context.getString(R.string.car_continue_watching) + " \u00b7 "
                        + context.getString(R.string.car_time_left, left))
                .setOnClickListener(() -> {
                    // The player resumes from the remembered position by itself.
                    PlaybackPresenter.instance(context.getApplicationContext())
                            .openVideo(state.video);
                    getScreenManager().popToRoot();
                });
        if (mResumeThumbnail != null) {
            row.setImage(new CarIcon.Builder(IconCompat.createWithBitmap(mResumeThumbnail))
                    .build(), Row.IMAGE_TYPE_LARGE);
        } else {
            row.setImage(new CarIcon.Builder(IconCompat.createWithResource(context,
                    R.drawable.ic_player_play)).build());
        }
        return row.build();
    }

    /**
     * The most recent video watched part way (not to the end, not the one on screen now), from
     * what the phone remembers; null if there is none.
     */
    @Nullable
    private static VideoStateService.State lastUnfinished(Context context) {
        List<VideoStateService.State> states;
        try {
            states = VideoStateService.instance(context.getApplicationContext()).getStates();
        } catch (RuntimeException e) {
            return null;
        }
        Video current = CarDisplayBridge.getVideo();
        String currentId = current != null ? current.videoId : null;
        for (int i = states.size() - 1; i >= 0; i--) {
            VideoStateService.State state = states.get(i);
            if (state == null || state.video == null || state.video.videoId == null
                    || TextUtils.isEmpty(state.video.title) || state.durationMs <= 0
                    || state.video.isLive // a live stream has no "where you stopped"
                    || state.positionMs < 30_000
                    || state.positionMs >= state.durationMs * CarThumbnails.WATCHED_TO_END
                    || Objects.equals(state.video.videoId, currentId)) {
                continue;
            }
            return state;
        }
        return null;
    }

    private void loadResumeThumbnail() {
        VideoStateService.State state = mResume;
        if (state == null || TextUtils.isEmpty(state.video.cardImageUrl)) {
            return;
        }
        Context appContext = getCarContext().getApplicationContext();
        String url = CarVideoListScreen.smallThumbnailUrl(state.video.cardImageUrl);
        String liveLabel = getCarContext().getString(R.string.car_live);
        float watched = state.positionMs / (float) state.durationMs;
        mThumbnailLoad = Observable.fromCallable(() -> CarThumbnails.decorate(
                        Glide.with(appContext).asBitmap().load(url).centerCrop()
                                .submit(320, 180).get(10, TimeUnit.SECONDS),
                        state.video, watched, liveLabel))
                .subscribeOn(Schedulers.io())
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe(bitmap -> {
                    mResumeThumbnail = bitmap;
                    invalidate();
                }, error -> { });
    }
}
