package com.newtube.mobile.car.setup;

import android.Manifest;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.InstallSourceInfo;
import android.content.pm.PackageManager;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.os.Build;
import android.provider.Settings;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.WorkerThread;

import java.io.File;
import java.io.IOException;
import java.net.InetAddress;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import io.github.muntashirakon.adb.AdbPairingRequiredException;
import io.github.muntashirakon.adb.android.AdbMdns;

/**
 * Makes Android Auto list this app, from the phone alone.
 *
 * <p>Android Auto shows a sideloaded car app only when the package installer recorded Google Play
 * as its installer, and only the shell can ask for that. Over Wireless debugging this app is its
 * own ADB client ({@link NewTubeAdb}) and runs the same steps as the computer installer
 * (scripts/android-auto/install.ps1): copy the APK to Download, have the media store index it, and
 * open the system installer with Play as the installer. The person still confirms the install.</p>
 *
 * <p>The pairing is once. After it, the shell grants this app WRITE_SECURE_SETTINGS, so later
 * updates can switch Wireless debugging on themselves and off again at the end.</p>
 */
public final class CarSetup {
    public static final String PLAY = "com.android.vending";
    private static final String PREFS = "newtube_car_setup";
    private static final String KEY_PAIRED = "paired";
    private static final String ADB_WIFI = "adb_wifi_enabled";
    private static final String DEST = "/sdcard/Download/NewTube-Auto.apk";
    private static final Pattern ID = Pattern.compile("_id=(\\d+)");

    /** Something the person has to do on the phone; the message says what. */
    public static final class NeedsPerson extends Exception {
        public final int what;

        NeedsPerson(int what) {
            super("needs person: " + what);
            this.what = what;
        }
    }

    /** Wireless debugging is off and this app can't switch it on yet. */
    public static final int TURN_ON_WIRELESS_DEBUGGING = 1;
    /** The phone doesn't know this app's key (never paired, or the pairings were revoked). */
    public static final int PAIR = 2;
    /** Wireless debugging only works on Wi-Fi. */
    public static final int CONNECT_WIFI = 3;
    /** The pairing code dialog wasn't open, or the code was wrong. */
    public static final int PAIRING_FAILED = 4;
    /** Android 16+: the local-network permission was refused. */
    public static final int LOCAL_NETWORK_DENIED = 5;

    public interface Progress {
        void onStep(@NonNull String step);
    }

    private CarSetup() {
    }

    /** True when Android Auto will list the app: the installer of record is Google Play. */
    public static boolean isReadyForAndroidAuto(@NonNull Context context) {
        return PLAY.equals(installerOf(context));
    }

    @Nullable
    static String installerOf(@NonNull Context context) {
        PackageManager pm = context.getPackageManager();
        try {
            if (Build.VERSION.SDK_INT >= 30) {
                InstallSourceInfo info = pm.getInstallSourceInfo(context.getPackageName());
                return info.getInstallingPackageName();
            }
            //noinspection deprecation
            return pm.getInstallerPackageName(context.getPackageName());
        } catch (Exception e) {
            return null;
        }
    }

    public static boolean isPaired(@NonNull Context context) {
        return prefs(context).getBoolean(KEY_PAIRED, false);
    }

    /** After the first setup this app may switch Wireless debugging on and off itself. */
    public static boolean canSwitchWirelessDebugging(@NonNull Context context) {
        return context.checkSelfPermission(Manifest.permission.WRITE_SECURE_SETTINGS)
                == PackageManager.PERMISSION_GRANTED;
    }

    public static boolean isOnWifi(@NonNull Context context) {
        ConnectivityManager cm = (ConnectivityManager) context.getSystemService(Context.CONNECTIVITY_SERVICE);
        if (cm == null) {
            return false;
        }
        Network network = cm.getActiveNetwork();
        NetworkCapabilities caps = network != null ? cm.getNetworkCapabilities(network) : null;
        return caps != null && caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI);
    }

    static boolean isWirelessDebuggingOn(@NonNull Context context) {
        try {
            return Settings.Global.getInt(context.getContentResolver(), ADB_WIFI, 0) == 1;
        } catch (Exception e) {
            return false;
        }
    }

    /** Local-network access, which Android 16+ asks for: mDNS to find Wireless debugging's ports. */
    public static final String LOCAL_NETWORK = "android.permission.ACCESS_LOCAL_NETWORK";

    public static boolean hasLocalNetwork(@NonNull Context context) {
        return Build.VERSION.SDK_INT < 36
                || context.checkSelfPermission(LOCAL_NETWORK) == PackageManager.PERMISSION_GRANTED;
    }

    /**
     * Pairs with the phone's Wireless debugging using the six-digit code from its "Pair device
     * with pairing code" dialog, on the pairing port {@link CarPairingService} found while that
     * dialog is open. Loopback first, as the port belongs to this very phone.
     */
    @WorkerThread
    public static void pair(@NonNull Context context, @Nullable InetAddress host, int port, @NonNull String code)
            throws Exception {
        NewTubeAdb adb = NewTubeAdb.get(context);
        Exception last = null;
        for (String address : new String[]{"127.0.0.1", host != null ? host.getHostAddress() : null}) {
            if (address == null) {
                continue;
            }
            try {
                adb.pair(address, port, code.trim());
                prefs(context).edit().putBoolean(KEY_PAIRED, true).apply();
                return;
            } catch (Exception e) {
                last = e;
            }
        }
        throw last != null ? last : new NeedsPerson(PAIRING_FAILED);
    }

    /** Finds a Wireless debugging port by mDNS: _adb-tls-pairing or _adb-tls-connect. */
    @WorkerThread
    @Nullable
    static InetSocketAddressCompat discover(@NonNull Context context, @NonNull String type, long timeoutMs)
            throws InterruptedException {
        AtomicReference<InetAddress> host = new AtomicReference<>();
        AtomicInteger port = new AtomicInteger(-1);
        CountDownLatch found = new CountDownLatch(1);
        AdbMdns mdns = new AdbMdns(context, type, (address, p) -> {
            if (address != null && p > 0) {
                host.set(address);
                port.set(p);
                found.countDown();
            }
        });
        mdns.start();
        try {
            return found.await(timeoutMs, TimeUnit.MILLISECONDS)
                    ? new InetSocketAddressCompat(host.get(), port.get()) : null;
        } finally {
            mdns.stop();
        }
    }

    static final class InetSocketAddressCompat {
        final InetAddress host;
        final int port;

        InetSocketAddressCompat(InetAddress host, int port) {
            this.host = host;
            this.port = port;
        }
    }

    /** Connects to Wireless debugging (loopback first), or false when it isn't advertising. */
    @WorkerThread
    private static boolean connect(@NonNull Context context, @NonNull NewTubeAdb adb, long timeoutMs)
            throws Exception {
        if (adb.isConnected()) {
            return true;
        }
        InetSocketAddressCompat target = discover(context, AdbMdns.SERVICE_TYPE_TLS_CONNECT, timeoutMs);
        if (target == null) {
            return false;
        }
        try {
            return adb.connect("127.0.0.1", target.port);
        } catch (AdbPairingRequiredException e) {
            throw e;
        } catch (Exception loopbackRefused) {
            return adb.connect(target.host.getHostAddress(), target.port);
        }
    }

    /**
     * Opens the system installer for {@code apk} with Google Play as the installer of record.
     * Returns once the installer is on screen; installing replaces (and so closes) this app.
     *
     * @param leaveWirelessDebuggingOn keep it on at the end (the person had it on before)
     */
    @WorkerThread
    public static void install(@NonNull Context context, @NonNull File apk, boolean leaveWirelessDebuggingOn,
                               @NonNull Progress progress) throws Exception {
        if (!isOnWifi(context)) {
            throw new NeedsPerson(CONNECT_WIFI);
        }
        if (!hasLocalNetwork(context)) {
            throw new NeedsPerson(LOCAL_NETWORK_DENIED);
        }
        if (!isPaired(context)) {
            throw new NeedsPerson(PAIR);
        }
        NewTubeAdb adb = NewTubeAdb.get(context);
        boolean switched = canSwitchWirelessDebugging(context);
        if (!isWirelessDebuggingOn(context)) {
            if (!switched) {
                throw new NeedsPerson(TURN_ON_WIRELESS_DEBUGGING);
            }
            progress.onStep("wireless");
            Settings.Global.putInt(context.getContentResolver(), ADB_WIFI, 1);
        }
        progress.onStep("connect");
        boolean connected;
        try {
            // Wireless debugging needs a few seconds to start and advertise its port; on a network
            // it hasn't seen, the phone first asks whether to allow it there.
            connected = connect(context, adb, 25_000);
        } catch (AdbPairingRequiredException e) {
            prefs(context).edit().putBoolean(KEY_PAIRED, false).apply();
            throw new NeedsPerson(PAIR);
        }
        if (!connected) {
            throw new NeedsPerson(switched ? CONNECT_WIFI : TURN_ON_WIRELESS_DEBUGGING);
        }
        try {
            String pkg = context.getPackageName();
            if (!switched) {
                adb.shell("pm grant " + pkg + " " + Manifest.permission.WRITE_SECURE_SETTINGS);
            }
            progress.onStep("copy");
            adb.push(apk, DEST);
            adb.shell("am broadcast -a android.intent.action.MEDIA_SCANNER_SCAN_FILE -d file://" + DEST);
            String id = null;
            for (int i = 0; i < 20 && id == null; i++) {
                String rows = adb.shell("content query --uri content://media/external/file --projection _id"
                        + " --where \"_data LIKE '%/Download/NewTube-Auto.apk'\"");
                Matcher m = ID.matcher(rows);
                while (m.find()) {
                    id = m.group(1);
                }
                if (id == null) {
                    Thread.sleep(500);
                }
            }
            if (id == null) {
                throw new IOException("The media store didn't index the APK");
            }
            progress.onStep("install");
            adb.shell("input keyevent KEYCODE_WAKEUP");
            String started = adb.shell("am start -a android.intent.action.INSTALL_PACKAGE"
                    + " -d content://media/external/file/" + id
                    + " -t application/vnd.android.package-archive"
                    + " --es android.intent.extra.INSTALLER_PACKAGE_NAME " + PLAY
                    + " --grant-read-uri-permission");
            if (started.contains("Error")) {
                throw new IOException(started);
            }
        } finally {
            if (!leaveWirelessDebuggingOn) {
                try {
                    // Last: this ends the connection it is sent over.
                    adb.shell("settings put global " + ADB_WIFI + " 0");
                } catch (Exception ignored) {
                    // Expected - the daemon goes away mid-reply.
                }
            }
            try {
                adb.disconnect();
            } catch (Exception ignored) {
                // Already gone.
            }
        }
    }

    private static SharedPreferences prefs(Context context) {
        return context.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }
}
