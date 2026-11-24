package com.newtube.mobile.car;

import android.content.ActivityNotFoundException;
import android.text.TextUtils;

import androidx.annotation.NonNull;
import androidx.car.app.CarContext;
import androidx.car.app.CarToast;
import androidx.car.app.Screen;
import androidx.car.app.constraints.ConstraintManager;
import androidx.car.app.model.Action;
import androidx.car.app.model.ItemList;
import androidx.car.app.model.ListTemplate;
import androidx.car.app.model.Row;
import androidx.car.app.model.Template;

import com.liskovsoft.smartyoutubetv2.tv.R;
import com.newtube.mobile.external.ExternalHistory;

import java.util.List;

/**
 * "Other apps" on the car screen: the videos Nuvio, Stremio and other apps sent to NewTube
 * lately ({@link ExternalHistory}), newest first, each with the time left. A tap plays it again
 * from where it stopped, on the car screen, without touching the phone.
 */
public class CarExternalListScreen extends Screen {
    private static final int DEFAULT_LIMIT = 6;

    public CarExternalListScreen(@NonNull CarContext carContext) {
        super(carContext);
    }

    @NonNull
    @Override
    public Template onGetTemplate() {
        CarContext context = getCarContext();
        ItemList.Builder list = new ItemList.Builder();
        List<ExternalHistory.Entry> entries = ExternalHistory.load(context);
        int limit = rowLimit(context);
        int shown = 0;
        for (ExternalHistory.Entry entry : entries) {
            if (shown >= limit) {
                break;
            }
            list.addItem(row(entry));
            shown++;
        }
        if (shown == 0) {
            list.setNoItemsMessage(context.getString(R.string.car_external_empty));
        }
        return new ListTemplate.Builder()
                .setTitle(context.getString(R.string.car_menu_external))
                .setHeaderAction(Action.BACK)
                .setSingleList(list.build())
                .build();
    }

    private Row row(ExternalHistory.Entry entry) {
        CarContext context = getCarContext();
        String title = !TextUtils.isEmpty(entry.title) ? entry.title
                : context.getString(R.string.car_menu_external);
        Row.Builder row = new Row.Builder().setTitle(title);
        if (entry.isFinished()) {
            row.addText(context.getString(R.string.car_watched_all));
        } else if (entry.durationMs > 0 && entry.positionMs > 0) {
            row.addText(context.getString(R.string.car_time_left,
                    CarThumbnails.formatTime(entry.durationMs - entry.positionMs)));
        }
        return row.setOnClickListener(() -> play(entry)).build();
    }

    private void play(ExternalHistory.Entry entry) {
        CarContext context = getCarContext();
        try {
            context.getApplicationContext().startActivity(
                    entry.toIntent(context.getApplicationContext()));
        } catch (ActivityNotFoundException | SecurityException e) {
            CarToast.makeText(context, context.getString(R.string.car_video_unavailable_title),
                    CarToast.LENGTH_SHORT).show();
            return;
        }
        // Back to the player screen, where the video shows up.
        getScreenManager().popToRoot();
    }

    private static int rowLimit(CarContext context) {
        try {
            if (context.getCarAppApiLevel() >= 2) {
                return Math.max(1, context.getCarService(ConstraintManager.class)
                        .getContentLimit(ConstraintManager.CONTENT_LIMIT_TYPE_LIST));
            }
        } catch (RuntimeException e) {
            // Older hosts: the common in-motion limit.
        }
        return DEFAULT_LIMIT;
    }
}
