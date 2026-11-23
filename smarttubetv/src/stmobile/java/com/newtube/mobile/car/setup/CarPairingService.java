package com.newtube.mobile.car.setup;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;
import androidx.core.app.RemoteInput;

import com.liskovsoft.smartyoutubetv2.tv.R;

import java.io.File;
import java.net.InetAddress;

import io.github.muntashirakon.adb.android.AdbMdns;

/**
 * The one-time pairing, while the person is in Settings.
 *
 * <p>Started from the setup screen, it keeps looking (mDNS) for Wireless debugging's pairing
 * port, which exists only while the "Pair device with pairing code" dialog is open. Once found,
 * a notification asks for the six digits; the reply comes back here, and in one go the app
 * pairs, then reopens its own APK in the installer with Play as installer. A foreground service
 * because all of this happens with Settings, not this app, on screen.</p>
 */
public final class CarPairingService extends Service {
    private static final String TAG = "CarPairing";
    private static final String ACTION_START = "com.newtube.mobile.car.setup.START";
    private static final String ACTION_CODE = "com.newtube.mobile.car.setup.CODE";
    private static final String ACTION_STOP = "com.newtube.mobile.car.setup.STOP";
    private static final String KEY_CODE = "code";
    private static final String CHANNEL_STATUS = "car_setup_status";
    private static final String CHANNEL_CODE = "car_setup";
    private static final int ID_STATUS = 0xCA5E6;
    private static final int ID_CODE = 0xCA5E7;
    private static final long GIVE_UP_MS = 10 * 60_000L;

    private final Handler mMain = new Handler(Looper.getMainLooper());
    private AdbMdns mMdns;
    @Nullable private volatile InetAddress mHost;
    private volatile int mPort = -1;
    private volatile boolean mBusy;

    public static void start(@NonNull Context context) {
        Intent intent = new Intent(context, CarPairingService.class).setAction(ACTION_START);
        if (Build.VERSION.SDK_INT >= 26) {
            context.startForegroundService(intent);
        } else {
            context.startService(intent);
        }
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent != null ? intent.getAction() : null;
        if (ACTION_START.equals(action) || action == null) {
            goForeground(getString(R.string.car_setup_notif_searching));
            startSearching();
            mMain.removeCallbacksAndMessages(null);
            mMain.postDelayed(this::finish, GIVE_UP_MS);
        } else if (ACTION_CODE.equals(action)) {
            onCode(intent);
        } else if (ACTION_STOP.equals(action)) {
            finish();
        }
        return START_NOT_STICKY;
    }

    private void startSearching() {
        if (mMdns != null) {
            return;
        }
        mMdns = new AdbMdns(this, AdbMdns.SERVICE_TYPE_TLS_PAIRING, (host, port) -> {
            Log.d(TAG, "pairing port " + port);
            if (port > 0) {
                mHost = host;
                mPort = port;
                if (!mBusy) {
                    mMain.post(() -> askForCode(false));
                }
            } else if (!mBusy) {
                mPort = -1;
                mMain.post(() -> {
                    cancel(ID_CODE);
                    status(getString(R.string.car_setup_notif_searching), true);
                });
            }
        });
        mMdns.start();
    }

    private void onCode(Intent intent) {
        Bundle results = RemoteInput.getResultsFromIntent(intent);
        CharSequence typed = results != null ? results.getCharSequence(KEY_CODE) : null;
        String code = typed != null ? typed.toString().replaceAll("\\D", "") : "";
        int port = mPort;
        if (code.length() != 6 || port <= 0 || mBusy) {
            askForCode(true);
            return;
        }
        mBusy = true;
        cancel(ID_CODE);
        status(getString(R.string.car_setup_notif_pairing), true);
        Context app = getApplicationContext();
        InetAddress host = mHost;
        new Thread(() -> {
            String done;
            boolean retry = false;
            try {
                CarSetup.pair(app, host, port, code);
                stopSearching();
                status(getString(R.string.car_setup_step_connect), true);
                File apk = new File(app.getApplicationInfo().sourceDir);
                // The person switched Wireless debugging on for this; it goes off again at the end.
                CarSetup.install(app, apk, false, step -> { });
                done = getString(R.string.car_setup_notif_done);
            } catch (CarSetup.NeedsPerson e) {
                Log.w(TAG, "needs person " + e.what);
                done = getString(e.what == CarSetup.CONNECT_WIFI ? R.string.car_setup_wifi_needed
                        : e.what == CarSetup.LOCAL_NETWORK_DENIED ? R.string.car_setup_local_network_needed
                        : R.string.car_setup_turn_on_wireless);
            } catch (Exception e) {
                Log.w(TAG, "pairing/install failed", e);
                boolean paired = CarSetup.isPaired(app);
                retry = !paired;
                done = paired ? getString(R.string.car_setup_error, describe(e)) : null;
            }
            String text = done;
            boolean again = retry;
            mMain.post(() -> {
                mBusy = false;
                if (again) {
                    askForCode(true);
                    return;
                }
                finalStatus(text);
                finish();
            });
        }, "car-pairing").start();
    }

    private static String describe(Exception e) {
        String message = e.getMessage();
        return e.getClass().getSimpleName() + (message != null ? ": " + message : "");
    }

    private void askForCode(boolean retry) {
        Intent intent = new Intent(this, CarPairingService.class).setAction(ACTION_CODE);
        int mutable = Build.VERSION.SDK_INT >= 31 ? PendingIntent.FLAG_MUTABLE : 0;
        PendingIntent reply = PendingIntent.getService(this, 1, intent,
                PendingIntent.FLAG_UPDATE_CURRENT | mutable);
        RemoteInput input = new RemoteInput.Builder(KEY_CODE)
                .setLabel(getString(R.string.car_setup_notif_reply))
                .build();
        NotificationCompat.Action action = new NotificationCompat.Action.Builder(
                R.drawable.ic_car, getString(R.string.car_setup_notif_reply), reply)
                .addRemoteInput(input)
                .setAllowGeneratedReplies(false)
                .build();
        String text = getString(retry ? R.string.car_setup_notif_failed : R.string.car_setup_notif_text);
        channels();
        notify(ID_CODE, new NotificationCompat.Builder(this, CHANNEL_CODE)
                .setSmallIcon(R.drawable.ic_car)
                .setContentTitle(getString(R.string.car_setup_notif_title))
                .setContentText(text)
                .setStyle(new NotificationCompat.BigTextStyle().bigText(text))
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setCategory(NotificationCompat.CATEGORY_MESSAGE)
                .addAction(action)
                .build());
        status(getString(R.string.car_setup_notif_found), true);
    }

    private void goForeground(String text) {
        Notification n = statusNotification(text, true);
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(ID_STATUS, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC);
        } else {
            startForeground(ID_STATUS, n);
        }
    }

    private void status(String text, boolean ongoing) {
        notify(ID_STATUS, statusNotification(text, ongoing));
    }

    /** The result stays after the service is gone (its own id, not the foreground one). */
    private void finalStatus(String text) {
        notify(ID_STATUS + 2, statusNotification(text, false));
    }

    private Notification statusNotification(String text, boolean ongoing) {
        channels();
        Intent open = new Intent(this, CarSetupActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        PendingIntent content = PendingIntent.getActivity(this, 2, open,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        NotificationCompat.Builder b = new NotificationCompat.Builder(this, CHANNEL_STATUS)
                .setSmallIcon(R.drawable.ic_car)
                .setContentTitle(getString(R.string.car_setup_title))
                .setContentText(text)
                .setStyle(new NotificationCompat.BigTextStyle().bigText(text))
                .setContentIntent(content)
                .setOngoing(ongoing)
                .setAutoCancel(!ongoing)
                .setOnlyAlertOnce(true);
        if (ongoing) {
            PendingIntent stop = PendingIntent.getService(this, 3,
                    new Intent(this, CarPairingService.class).setAction(ACTION_STOP),
                    PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
            b.addAction(0, getString(android.R.string.cancel), stop);
        }
        return b.build();
    }

    private void channels() {
        NotificationManager nm = getSystemService(NotificationManager.class);
        if (Build.VERSION.SDK_INT < 26 || nm == null) {
            return;
        }
        if (nm.getNotificationChannel(CHANNEL_CODE) == null) {
            nm.createNotificationChannel(new NotificationChannel(CHANNEL_CODE,
                    getString(R.string.car_setup_notif_channel), NotificationManager.IMPORTANCE_HIGH));
        }
        if (nm.getNotificationChannel(CHANNEL_STATUS) == null) {
            nm.createNotificationChannel(new NotificationChannel(CHANNEL_STATUS,
                    getString(R.string.car_setup_notif_channel_status), NotificationManager.IMPORTANCE_LOW));
        }
    }

    private void notify(int id, Notification n) {
        NotificationManager nm = getSystemService(NotificationManager.class);
        if (nm != null) {
            try {
                nm.notify(id, n);
            } catch (SecurityException ignored) {
                // Notifications refused; the setup screen asks for them first.
            }
        }
    }

    private void cancel(int id) {
        NotificationManager nm = getSystemService(NotificationManager.class);
        if (nm != null) {
            nm.cancel(id);
        }
    }

    private void stopSearching() {
        AdbMdns mdns = mMdns;
        mMdns = null;
        if (mdns != null) {
            mdns.stop();
        }
    }

    private void finish() {
        if (mBusy) {
            return;
        }
        mMain.removeCallbacksAndMessages(null);
        stopSearching();
        cancel(ID_CODE);
        stopForeground(true);
        stopSelf();
    }

    @Override
    public void onDestroy() {
        stopSearching();
        mMain.removeCallbacksAndMessages(null);
        super.onDestroy();
    }
}
