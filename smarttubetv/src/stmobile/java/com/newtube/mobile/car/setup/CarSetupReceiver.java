package com.newtube.mobile.car.setup;

import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.Bundle;

import androidx.annotation.NonNull;
import androidx.core.app.NotificationCompat;
import androidx.core.app.RemoteInput;

import com.liskovsoft.smartyoutubetv2.tv.R;

import java.io.File;

/**
 * The pairing code arrives here, typed into the setup notification while the phone's
 * "Pair device with pairing code" dialog stays open (leaving it closes the pairing port).
 * Then, in the same go: pair, and reopen this app's own APK in the installer with Play as
 * its installer.
 */
public final class CarSetupReceiver extends BroadcastReceiver {
    private static final String ACTION_CODE = "com.newtube.mobile.car.setup.CODE";
    private static final String KEY_CODE = "code";
    private static final String CHANNEL = "car_setup";
    private static final int NOTIFICATION = 0xCA5E7;

    /** Posts (or re-posts) the notification that asks for the code. */
    public static void askForCode(@NonNull Context context, boolean retry) {
        Intent intent = new Intent(context, CarSetupReceiver.class).setAction(ACTION_CODE);
        int flags = PendingIntent.FLAG_UPDATE_CURRENT
                | (Build.VERSION.SDK_INT >= 31 ? PendingIntent.FLAG_MUTABLE : 0);
        PendingIntent reply = PendingIntent.getBroadcast(context, 0, intent, flags);
        RemoteInput input = new RemoteInput.Builder(KEY_CODE)
                .setLabel(context.getString(R.string.car_setup_notif_reply))
                .build();
        NotificationCompat.Action action = new NotificationCompat.Action.Builder(
                R.drawable.ic_car, context.getString(R.string.car_setup_notif_reply), reply)
                .addRemoteInput(input)
                .setAllowGeneratedReplies(false)
                .build();
        String text = context.getString(retry ? R.string.car_setup_notif_failed : R.string.car_setup_notif_text);
        notify(context, new NotificationCompat.Builder(context, CHANNEL)
                .setSmallIcon(R.drawable.ic_car)
                .setContentTitle(context.getString(R.string.car_setup_notif_title))
                .setContentText(text)
                .setStyle(new NotificationCompat.BigTextStyle().bigText(text))
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setCategory(NotificationCompat.CATEGORY_STATUS)
                .setOnlyAlertOnce(retry)
                .addAction(action));
    }

    public static void cancel(@NonNull Context context) {
        NotificationManager nm = context.getSystemService(NotificationManager.class);
        if (nm != null) {
            nm.cancel(NOTIFICATION);
        }
    }

    @Override
    public void onReceive(Context context, Intent intent) {
        if (!ACTION_CODE.equals(intent.getAction())) {
            return;
        }
        Bundle results = RemoteInput.getResultsFromIntent(intent);
        CharSequence typed = results != null ? results.getCharSequence(KEY_CODE) : null;
        String code = typed != null ? typed.toString().replaceAll("\\D", "") : "";
        Context app = context.getApplicationContext();
        if (code.length() != 6) {
            askForCode(app, true);
            return;
        }
        status(app, app.getString(R.string.car_setup_notif_pairing), true);
        PendingResult pending = goAsync();
        new Thread(() -> {
            try {
                CarSetup.pair(app, code);
                File apk = new File(app.getApplicationInfo().sourceDir);
                // The person switched Wireless debugging on for this; it goes off again at the end.
                CarSetup.install(app, apk, false, step -> {
                    if ("install".equals(step)) {
                        status(app, app.getString(R.string.car_setup_notif_done), false);
                    }
                });
            } catch (CarSetup.NeedsPerson e) {
                if (e.what == CarSetup.PAIRING_FAILED) {
                    askForCode(app, true);
                } else {
                    status(app, app.getString(e.what == CarSetup.CONNECT_WIFI
                            ? R.string.car_setup_wifi_needed : R.string.car_setup_turn_on_wireless), false);
                }
            } catch (Exception e) {
                status(app, app.getString(R.string.car_setup_error, String.valueOf(e.getMessage())), false);
            } finally {
                pending.finish();
            }
        }, "car-setup").start();
    }

    private static void status(Context context, String text, boolean ongoing) {
        Intent open = new Intent(context, CarSetupActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        PendingIntent content = PendingIntent.getActivity(context, 1, open,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        notify(context, new NotificationCompat.Builder(context, CHANNEL)
                .setSmallIcon(R.drawable.ic_car)
                .setContentTitle(context.getString(R.string.car_setup_title))
                .setContentText(text)
                .setStyle(new NotificationCompat.BigTextStyle().bigText(text))
                .setContentIntent(content)
                .setOngoing(ongoing)
                .setAutoCancel(!ongoing)
                .setOnlyAlertOnce(true)
                .setProgress(ongoing ? 1 : 0, 0, ongoing));
    }

    private static void notify(Context context, NotificationCompat.Builder builder) {
        NotificationManager nm = context.getSystemService(NotificationManager.class);
        if (nm == null) {
            return;
        }
        if (Build.VERSION.SDK_INT >= 26 && nm.getNotificationChannel(CHANNEL) == null) {
            nm.createNotificationChannel(new NotificationChannel(CHANNEL,
                    context.getString(R.string.car_setup_notif_channel), NotificationManager.IMPORTANCE_HIGH));
        }
        try {
            nm.notify(NOTIFICATION, builder.build());
        } catch (SecurityException ignored) {
            // Notifications not allowed; the setup screen asks for them first.
        }
    }
}
