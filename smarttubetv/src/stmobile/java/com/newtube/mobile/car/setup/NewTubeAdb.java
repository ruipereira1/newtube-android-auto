package com.newtube.mobile.car.setup;

import android.content.Context;
import android.os.Build;

import androidx.annotation.NonNull;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.security.cert.Certificate;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.security.spec.PKCS8EncodedKeySpec;

import io.github.muntashirakon.adb.AbsAdbConnectionManager;
import io.github.muntashirakon.adb.AdbStream;

/**
 * This app as an ADB client of its own phone, over Wireless debugging.
 *
 * <p>Android lets only the shell (ADB), root or the system say "installed by Google Play", and
 * Android Auto lists a sideloaded car app only then. After a one-time pairing with the phone's
 * Wireless debugging, this connection runs the same shell commands as the computer installer
 * (scripts/android-auto) - so the app can reinstall and update itself the way Android Auto
 * accepts, with no cable and no computer.</p>
 *
 * <p>The RSA key and its certificate live in the app's private storage; the phone remembers them
 * from the pairing. Nothing here listens on the network: the app only connects out to the phone's
 * own adbd, which Wireless debugging already exposes.</p>
 */
final class NewTubeAdb extends AbsAdbConnectionManager {
    private static final String DIR = "car_adb";
    private static NewTubeAdb sInstance;

    private final PrivateKey mPrivateKey;
    private final Certificate mCertificate;

    static synchronized NewTubeAdb get(@NonNull Context context) throws Exception {
        if (sInstance == null) {
            sInstance = new NewTubeAdb(context.getApplicationContext());
        }
        return sInstance;
    }

    private NewTubeAdb(Context context) throws Exception {
        setApi(Build.VERSION.SDK_INT);
        setTimeout(10, java.util.concurrent.TimeUnit.SECONDS);
        File dir = new File(context.getFilesDir(), DIR);
        File keyFile = new File(dir, "key.pk8");
        File certFile = new File(dir, "cert.der");
        PrivateKey key = null;
        Certificate cert = null;
        if (keyFile.isFile() && certFile.isFile()) {
            try {
                key = KeyFactory.getInstance("RSA")
                        .generatePrivate(new PKCS8EncodedKeySpec(read(keyFile)));
                try (InputStream in = new FileInputStream(certFile)) {
                    cert = CertificateFactory.getInstance("X.509").generateCertificate(in);
                }
            } catch (Exception e) {
                key = null;
                cert = null;
            }
        }
        if (key == null || cert == null) {
            KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
            generator.initialize(2048);
            KeyPair pair = generator.generateKeyPair();
            X509Certificate created = SelfSignedCertificate.create(pair, "NewTube Auto",
                    20L * 365 * 24 * 3600_000L);
            //noinspection ResultOfMethodCallIgnored
            dir.mkdirs();
            write(keyFile, pair.getPrivate().getEncoded());
            write(certFile, created.getEncoded());
            key = pair.getPrivate();
            cert = created;
        }
        mPrivateKey = key;
        mCertificate = cert;
    }

    @NonNull
    @Override
    protected PrivateKey getPrivateKey() {
        return mPrivateKey;
    }

    @NonNull
    @Override
    protected Certificate getCertificate() {
        return mCertificate;
    }

    @NonNull
    @Override
    protected String getDeviceName() {
        return "NewTube Auto";
    }

    /** Runs a shell command on the phone and returns what it printed. Needs a connection. */
    @NonNull
    String shell(@NonNull String command) throws IOException, InterruptedException {
        try (AdbStream stream = openStream("shell:" + command)) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            InputStream in = stream.openInputStream();
            byte[] buffer = new byte[8192];
            try {
                int n;
                while ((n = in.read(buffer)) != -1) {
                    out.write(buffer, 0, n);
                }
            } catch (IOException closed) {
                // adbd closes the stream when the command ends; what it printed is already read.
            }
            return out.toString(StandardCharsets.UTF_8.name()).trim();
        }
    }

    /**
     * Copies a file to the phone's shared storage over adb's sync protocol (what "adb push" does),
     * so the shell - and so the package installer - can read it.
     *
     * <p>Each write is one adb packet: the vendored stream waits for the daemon's go-ahead per
     * write call but not per packet, so a write never exceeds {@link #CHUNK}.</p>
     */
    void push(@NonNull File source, @NonNull String destination) throws IOException, InterruptedException {
        try (AdbStream stream = openStream("sync:")) {
            java.io.OutputStream out = stream.openOutputStream();
            InputStream reply = stream.openInputStream();
            byte[] spec = (destination + ",33188").getBytes(StandardCharsets.UTF_8); // 0100644
            byte[] send = new byte[8 + spec.length];
            header(send, "SEND", spec.length);
            System.arraycopy(spec, 0, send, 8, spec.length);
            out.write(send);
            byte[] packet = new byte[CHUNK];
            try (InputStream in = new FileInputStream(source)) {
                int n;
                while ((n = readUpTo(in, packet, 8, CHUNK - 8)) > 0) {
                    header(packet, "DATA", n);
                    out.write(packet, 0, 8 + n);
                }
            }
            byte[] done = new byte[8];
            header(done, "DONE", (int) (System.currentTimeMillis() / 1000));
            out.write(done);
            out.flush();
            byte[] status = new byte[8];
            readFully(reply, status);
            String id = new String(status, 0, 4, StandardCharsets.US_ASCII);
            if (!"OKAY".equals(id)) {
                int length = (status[4] & 0xff) | (status[5] & 0xff) << 8 | (status[6] & 0xff) << 16
                        | (status[7] & 0xff) << 24;
                byte[] message = new byte[Math.max(0, Math.min(length, 1024))];
                readFully(reply, message);
                throw new IOException("push: " + new String(message, StandardCharsets.UTF_8));
            }
            byte[] quit = new byte[8];
            header(quit, "QUIT", 0);
            out.write(quit);
        }
    }

    private static final int CHUNK = 32 * 1024;

    private static void header(byte[] into, String id, int value) {
        for (int i = 0; i < 4; i++) {
            into[i] = (byte) id.charAt(i);
        }
        into[4] = (byte) value;
        into[5] = (byte) (value >> 8);
        into[6] = (byte) (value >> 16);
        into[7] = (byte) (value >> 24);
    }

    private static int readUpTo(InputStream in, byte[] into, int offset, int length) throws IOException {
        int total = 0;
        while (total < length) {
            int n = in.read(into, offset + total, length - total);
            if (n == -1) {
                break;
            }
            total += n;
        }
        return total;
    }

    private static void readFully(InputStream in, byte[] into) throws IOException {
        int total = 0;
        while (total < into.length) {
            int n = in.read(into, total, into.length - total);
            if (n == -1) {
                throw new IOException("push: the phone closed the connection");
            }
            total += n;
        }
    }

    private static byte[] read(File file) throws IOException {
        try (InputStream in = new FileInputStream(file)) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buffer = new byte[4096];
            int n;
            while ((n = in.read(buffer)) != -1) {
                out.write(buffer, 0, n);
            }
            return out.toByteArray();
        }
    }

    private static void write(File file, byte[] bytes) throws IOException {
        try (FileOutputStream out = new FileOutputStream(file)) {
            out.write(bytes);
        }
    }
}
