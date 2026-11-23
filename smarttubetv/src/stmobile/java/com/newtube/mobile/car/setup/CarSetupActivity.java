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
    private static final int REQUEST_PERMISSIONS = 7301;

    private final Handler mMain = new Handler(Looper.getMainLooper());
    private ImageView mStatusIcon;
    private TextView mStatus;
    private TextView mStatusDetail;
    private TextView mVersion;
    private TextView mProgress;
    private MaterialButton mPrimary;
    private MaterialButton mSecondary;
    private View mSteps;
    private android.widget.LinearLayout mStepList;
    private MaterialButton mRepair;
    /** "Pair again" was tapped: show the guide although a pairing exists. */
    private boolean mRepairing;
    /** Wireless debugging was found advertising (so Developer options are on too). */
    private boolean mWirelessSeen;
    private boolean mLooking;
    /** Developer options can't be read: steps 1 and 2 are both open. */
    private boolean mDevUnknown;
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
        mStepList = findViewById(R.id.car_setup_steps);
        mRepair = findViewById(R.id.car_setup_repair);
        mRepair.setOnClickListener(v -> {
            mRepairing = true;
            bind();
        });
        findViewById(R.id.car_setup_back).setOnClickListener(v -> finish());
        CarUpdates.clearDownload(this);
        if (CarUpdates.isCheckDue(this)) {
            check(false);
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        mWirelessSeen = false;
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
        if (latest != null && CarUpdates.isNewerThanInstalled(latest.tag)) {
            version.append('\n').append(getString(R.string.car_setup_latest, latest.tag));
        } else if (latest != null) {
            version.append('\n').append(getString(R.string.car_setup_up_to_date));
        }
        mVersion.setText(version);

        boolean guide = supported && (!paired || mRepairing);
        mSteps.setVisibility(guide ? View.VISIBLE : View.GONE);
        mRepair.setVisibility(supported && paired && !mRepairing ? View.VISIBLE : View.GONE);
        if (guide) {
            bindGuide();
        }
        mSecondary.setVisibility(View.GONE);
        mPrimary.setEnabled(supported && !mChecking);
        if (!supported) {
            mPrimary.setVisibility(View.GONE);
            return;
        }
        mPrimary.setVisibility(View.VISIBLE);

        if (guide) {
            // The guide's own step buttons lead; nothing else competes with them.
            mPrimary.setVisibility(View.GONE);
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

    /**
     * The one-time setup as steps with the real screens. It moves on by itself: coming back from
     * Settings, the steps already done (Developer options, Wireless debugging) are ticked and the
     * next one is open.
     */
    private void bindGuide() {
        // Android 17 answers 0 to apps for these settings, so "0" only means "not known to be on";
        // a short mDNS look for Wireless debugging (below) settles it and redraws the guide.
        boolean devOn = Settings.Global.getInt(getContentResolver(),
                Settings.Global.DEVELOPMENT_SETTINGS_ENABLED, 0) == 1;
        boolean wirelessOn = mWirelessSeen || CarSetup.isWirelessDebuggingOn(this);
        int current = wirelessOn ? 3 : devOn ? 2 : 1;
        mDevUnknown = !devOn && !wirelessOn;
        if (!wirelessOn && !mLooking) {
            mLooking = true;
            Context app = getApplicationContext();
            new Thread(() -> {
                boolean seen = CarSetup.isWirelessDebuggingAdvertised(app, 2_500);
                mMain.post(() -> {
                    mLooking = false;
                    if (seen && !mWirelessSeen && !isFinishing()) {
                        mWirelessSeen = true;
                        bind();
                    }
                });
            }, "car-setup-look").start();
        }
        mStepList.removeAllViews();
        addStep(1, current, R.string.car_guide_1_title, R.string.car_guide_1_text, R.drawable.guia_1_compilacao,
                R.string.car_guide_1_action, () -> openSettings(Settings.ACTION_DEVICE_INFO_SETTINGS));
        addStep(2, current, R.string.car_guide_2_title, R.string.car_guide_2_text, R.drawable.guia_2_sem_fios,
                R.string.car_guide_2_action, this::openWirelessDebugging);
        addStep(3, current, R.string.car_guide_3_title, R.string.car_guide_3_text, R.drawable.guia_3_codigo,
                R.string.car_setup_action_start, this::startPairing);
        addStep(4, current, R.string.car_guide_4_title, R.string.car_guide_4_text, R.drawable.guia_4_janela,
                0, null);
        addStep(5, current, R.string.car_guide_5_title, R.string.car_guide_5_text, R.drawable.guia_5_instalar,
                0, null);
    }

    private void addStep(int number, int current, int title, int text, int image, int action,
                         @Nullable Runnable onAction) {
        View card = getLayoutInflater().inflate(R.layout.item_car_setup_step, mStepList, false);
        boolean done = number < current;
        // From step 3 on everything happens in Settings, so steps 3-5 are all shown up front.
        boolean open = number == current || (current >= 3 && number > current)
                || (mDevUnknown && number == 2);
        TextView badge = card.findViewById(R.id.car_step_number);
        badge.setText(done ? "\u2713" : String.valueOf(number));
        ((TextView) card.findViewById(R.id.car_step_title)).setText(title);
        TextView body = card.findViewById(R.id.car_step_text);
        body.setText(text);
        body.setVisibility(done ? View.GONE : View.VISIBLE);
        ImageView picture = card.findViewById(R.id.car_step_image);
        if (open && image != 0) {
            picture.setImageResource(image);
            picture.setClipToOutline(true);
            picture.setVisibility(View.VISIBLE);
        } else {
            picture.setVisibility(View.GONE);
        }
        MaterialButton button = card.findViewById(R.id.car_step_action);
        if ((number == current || (mDevUnknown && number == 2)) && onAction != null) {
            button.setText(action);
            button.setOnClickListener(v -> onAction.run());
            button.setVisibility(View.VISIBLE);
        }
        card.setAlpha(open || done ? 1f : 0.55f);
        mStepList.addView(card);
    }

    private void openSettings(String action) {
        try {
            startActivity(new Intent(action).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        } catch (ActivityNotFoundException e) {
            startActivity(new Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        }
    }

    private void startPairing() {
        if (!CarSetup.isOnWifi(this)) {
            Toast.makeText(this, R.string.car_setup_wifi_needed, Toast.LENGTH_LONG).show();
            return;
        }
        java.util.List<String> missing = new java.util.ArrayList<>();
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) {
            missing.add(Manifest.permission.POST_NOTIFICATIONS);
        }
        if (!CarSetup.hasLocalNetwork(this)) {
            missing.add(CarSetup.LOCAL_NETWORK);
        }
        if (!missing.isEmpty()) {
            requestPermissions(missing.toArray(new String[0]), REQUEST_PERMISSIONS);
            return;
        }
        CarPairingService.start(this);
        openWirelessDebugging();
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] results) {
        super.onRequestPermissionsResult(requestCode, permissions, results);
        if (requestCode != REQUEST_PERMISSIONS) {
            return;
        }
        for (int i = 0; i < permissions.length; i++) {
            if (results.length > i && results[i] != PackageManager.PERMISSION_GRANTED) {
                Toast.makeText(this, CarSetup.LOCAL_NETWORK.equals(permissions[i])
                        ? R.string.car_setup_local_network_needed : R.string.car_setup_notifications_needed,
                        Toast.LENGTH_LONG).show();
                return;
            }
        }
        startPairing();
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
        if (!CarSetup.hasLocalNetwork(this)) {
            requestPermissions(new String[]{CarSetup.LOCAL_NETWORK}, REQUEST_PERMISSIONS + 1);
            return;
        }
        Context app = getApplicationContext();
        boolean leaveOnRead = CarSetup.isWirelessDebuggingOn(app);
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
                // On before we started = the person uses it: leave it on afterwards.
                boolean leaveOn = leaveOnRead || CarSetup.isWirelessDebuggingAdvertised(app, 2_000);
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
            case CarSetup.LOCAL_NETWORK_DENIED:
                return R.string.car_setup_local_network_needed;
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
