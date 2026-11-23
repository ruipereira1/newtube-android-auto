package com.newtube.mobile.car.setup;

import android.Manifest;
import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.text.format.Formatter;
import android.view.View;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.Nullable;

import com.google.android.material.button.MaterialButton;
import com.liskovsoft.smartyoutubetv2.tv.R;
import com.newtube.mobile.ui.common.MobileActivity;

import java.io.File;

/**
 * Tu → Android Auto: makes Android Auto list the app and keeps it updated, from the phone.
 * The work is {@link CarSetup} (the shell steps) and {@link CarUpdates} (GitHub releases).
 */
public class CarSetupActivity extends MobileActivity {
    private static final int REQUEST_NOTIFICATIONS = 7301;

    private final Handler mMain = new Handler(Looper.getMainLooper());
    private ImageView mStatusIcon;
    private TextView mStatus;
    private TextView mStatusDetail;
    private TextView mVersion;
    private TextView mProgress;
    private MaterialButton mPrimary;
    private MaterialButton mSecondary;
    private View mSteps;
    private volatile boolean mWorking;
    private boolean mChecking;

    public static void start(Context context) {
        context.startActivity(new Intent(context, CarSetupActivity.class));
    }

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_car_setup);
        mStatusIcon = findViewById(R.id.car_setup_status_icon);
        mStatus = findViewById(R.id.car_setup_status);
        mStatusDetail = findViewById(R.id.car_setup_status_detail);
        mVersion = findViewById(R.id.car_setup_version);
        mProgress = findViewById(R.id.car_setup_progress);
        mPrimary = findViewById(R.id.car_setup_primary);
        mSecondary = findViewById(R.id.car_setup_secondary);
        mSteps = findViewById(R.id.car_setup_steps_box);
        findViewById(R.id.car_setup_back).setOnClickListener(v -> finish());
        CarUpdates.clearDownload(this);
        if (CarUpdates.isCheckDue(this)) {
            check(false);
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        bind();
    }

    private void bind() {
        if (mWorking) {
            return;
        }
        boolean supported = Build.VERSION.SDK_INT >= 30;
        boolean ready = CarSetup.isReadyForAndroidAuto(this);
        boolean paired = CarSetup.isPaired(this);
        CarUpdates.Release latest = CarUpdates.cached(this);
        String installed = CarUpdates.installedTag();

        mStatusIcon.setImageResource(ready ? R.drawable.ic_mobile_check : R.drawable.ic_car);
        mStatus.setText(ready ? R.string.car_setup_status_ready : R.string.car_setup_status_not_ready_title);
        mStatusDetail.setText(!supported ? R.string.car_setup_status_old_android
                : ready ? R.string.car_setup_status_ready_detail : R.string.car_setup_status_not_ready);

        StringBuilder version = new StringBuilder(getString(R.string.car_setup_version,
                installed.isEmpty() ? getString(R.string.car_setup_version_local) : installed));
        if (latest != null && !latest.tag.equals(installed)) {
            version.append('\n').append(getString(R.string.car_setup_latest, latest.tag));
        } else if (latest != null) {
            version.append('\n').append(getString(R.string.car_setup_up_to_date));
        }
        mVersion.setText(version);

        mSteps.setVisibility(supported && !paired ? View.VISIBLE : View.GONE);
        mSecondary.setVisibility(View.GONE);
        mPrimary.setEnabled(supported && !mChecking);
        if (!supported) {
            mPrimary.setVisibility(View.GONE);
            return;
        }
        mPrimary.setVisibility(View.VISIBLE);

        if (!paired) {
            mPrimary.setText(R.string.car_setup_action_start);
            mPrimary.setOnClickListener(v -> startPairing());
            return;
        }
        if (CarUpdates.hasUpdate(this)) {
            String size = latest.size > 0 ? Formatter.formatShortFileSize(this, latest.size) : "";
            mPrimary.setText(getString(R.string.car_setup_action_update, latest.tag, size));
            mPrimary.setOnClickListener(v -> update(latest));
        } else if (!ready) {
            mPrimary.setText(R.string.car_setup_action_activate);
            mPrimary.setOnClickListener(v -> reinstallThis());
        } else {
            mPrimary.setText(mChecking ? R.string.car_setup_step_checking : R.string.car_setup_action_check);
            mPrimary.setOnClickListener(v -> check(true));
        }
        if (installed.isEmpty() && latest != null) {
            // A computer build: the published one is offered, but never pushed on.
            mSecondary.setVisibility(View.VISIBLE);
            mSecondary.setText(getString(R.string.car_setup_action_install_published, latest.tag));
            mSecondary.setOnClickListener(v -> update(latest));
        }
    }

    private void startPairing() {
        if (!CarSetup.isOnWifi(this)) {
            Toast.makeText(this, R.string.car_setup_wifi_needed, Toast.LENGTH_LONG).show();
            return;
        }
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, REQUEST_NOTIFICATIONS);
            return;
        }
        CarSetupReceiver.askForCode(this, false);
        openWirelessDebugging();
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] results) {
        super.onRequestPermissionsResult(requestCode, permissions, results);
        if (requestCode != REQUEST_NOTIFICATIONS) {
            return;
        }
        if (results.length > 0 && results[0] == PackageManager.PERMISSION_GRANTED) {
            startPairing();
        } else {
            Toast.makeText(this, R.string.car_setup_notifications_needed, Toast.LENGTH_LONG).show();
        }
    }

    /** Developer options, scrolled to Wireless debugging where the phone supports it. */
    private void openWirelessDebugging() {
        Intent intent = new Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS)
                .putExtra(":settings:fragment_args_key", "toggle_adb_wireless")
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try {
            startActivity(intent);
        } catch (ActivityNotFoundException e) {
            startActivity(new Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        }
    }

    private void check(boolean fromTap) {
        mChecking = true;
        bind();
        Context app = getApplicationContext();
        new Thread(() -> {
            String error = null;
            try {
                CarUpdates.check(app);
            } catch (Exception e) {
                error = e.getMessage();
            }
            String message = error;
            mMain.post(() -> {
                mChecking = false;
                if (fromTap && message != null && !isFinishing()) {
                    Toast.makeText(this, getString(R.string.car_setup_error, message), Toast.LENGTH_LONG).show();
                }
                bind();
            });
        }, "car-update-check").start();
    }

    private void reinstallThis() {
        run(null);
    }

    private void update(CarUpdates.Release release) {
        run(release);
    }

    /** Downloads {@code release} (or takes this app's own APK) and opens the installer. */
    private void run(@Nullable CarUpdates.Release release) {
        if (!CarSetup.isOnWifi(this)) {
            Toast.makeText(this, R.string.car_setup_wifi_needed, Toast.LENGTH_LONG).show();
            return;
        }
        Context app = getApplicationContext();
        boolean leaveOn = CarSetup.isWirelessDebuggingOn(app);
        mWorking = true;
        mPrimary.setEnabled(false);
        mSecondary.setEnabled(false);
        show(release != null ? getString(R.string.car_setup_step_download, 0) : getString(R.string.car_setup_step_connect));
        new Thread(() -> {
            try {
                File apk = release != null
                        ? CarUpdates.download(app, release, (bytes, total) -> {
                            if (total > 0) {
                                showFromWorker(getString(R.string.car_setup_step_download, (int) (bytes * 100 / total)));
                            }
                        })
                        : new File(app.getApplicationInfo().sourceDir);
                CarSetup.install(app, apk, leaveOn, step -> showFromWorker(stepText(step)));
            } catch (CarSetup.NeedsPerson e) {
                showFromWorker(getString(needsText(e.what)));
                if (e.what == CarSetup.TURN_ON_WIRELESS_DEBUGGING) {
                    mMain.post(this::openWirelessDebugging);
                }
            } catch (Exception e) {
                showFromWorker(getString(R.string.car_setup_error, String.valueOf(e.getMessage())));
            } finally {
                mMain.post(() -> {
                    mWorking = false;
                    mPrimary.setEnabled(true);
                    mSecondary.setEnabled(true);
                    if (!isFinishing()) {
                        bind();
                    }
                });
            }
        }, "car-setup").start();
    }

    private String stepText(String step) {
        switch (step) {
            case "wireless":
                return getString(R.string.car_setup_step_wireless);
            case "copy":
                return getString(R.string.car_setup_step_copy);
            case "install":
                return getString(R.string.car_setup_step_install);
            default:
                return getString(R.string.car_setup_step_connect);
        }
    }

    private static int needsText(int what) {
        switch (what) {
            case CarSetup.CONNECT_WIFI:
                return R.string.car_setup_wifi_needed;
            case CarSetup.PAIR:
                return R.string.car_setup_pair_again;
            default:
                return R.string.car_setup_turn_on_wireless;
        }
    }

    private void show(String text) {
        mProgress.setVisibility(View.VISIBLE);
        mProgress.setText(text);
    }

    private void showFromWorker(String text) {
        mMain.post(() -> show(text));
    }
}
