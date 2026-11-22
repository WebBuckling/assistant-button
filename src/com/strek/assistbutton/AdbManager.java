package com.strek.assistbutton;

import android.content.ContentResolver;
import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Build;
import android.provider.Settings;
import android.util.Log;
import android.sun.security.x509.AlgorithmId;
import android.sun.security.x509.CertificateAlgorithmId;
import android.sun.security.x509.CertificateExtensions;
import android.sun.security.x509.CertificateIssuerName;
import android.sun.security.x509.CertificateSerialNumber;
import android.sun.security.x509.CertificateSubjectName;
import android.sun.security.x509.CertificateValidity;
import android.sun.security.x509.CertificateVersion;
import android.sun.security.x509.CertificateX509Key;
import android.sun.security.x509.KeyIdentifier;
import android.sun.security.x509.PrivateKeyUsageExtension;
import android.sun.security.x509.SubjectKeyIdentifierExtension;
import android.sun.security.x509.X500Name;
import android.sun.security.x509.X509CertImpl;
import android.sun.security.x509.X509CertInfo;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.SecureRandom;
import java.security.cert.Certificate;
import java.security.cert.CertificateFactory;
import java.security.spec.PKCS8EncodedKeySpec;
import java.util.Date;
import java.util.Random;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import io.github.muntashirakon.adb.AbsAdbConnectionManager;
import io.github.muntashirakon.adb.AdbStream;
import io.github.muntashirakon.adb.android.AdbMdns;

/**
 * Talks to this phone's own adbd over Wireless debugging, the way Shizuku does,
 * so the app can run commands with shell (adb) privileges without a PC.
 */
final class AdbManager extends AbsAdbConnectionManager {

    static final String TAG = "AssistBtn";

    private static final String KEY_FILE = "adb_key.pk8";
    private static final String CERT_FILE = "adb_cert.der";
    private static final long TEN_YEARS_MS = 10L * 365 * 24 * 60 * 60 * 1000;

    private static AdbManager instance;

    private final PrivateKey privateKey;
    private final Certificate certificate;

    static synchronized AdbManager get(Context context) throws Exception {
        if (instance == null) {
            instance = new AdbManager(context.getApplicationContext());
        }
        return instance;
    }

    /**
     * Runs one command on its own short-lived connection with a hard timeout.
     * A stuck attempt is abandoned (its connection closed) and can't block the
     * next press, unlike a shared, synchronized connection.
     *
     * @return the command output, or null on failure or timeout.
     */
    static String runIsolated(Context context, String command, long timeoutMs) {
        // The command itself always finishes in a few seconds (each step is bounded
        // with `timeout`), but the ADB stream occasionally hangs inside libadb.
        // Such hangs are random, so abandon the attempt and retry on a new connection.
        long first = Math.min(4000, timeoutMs / 2);
        String out = runIsolatedOnce(context, command, first);
        if (out == null) {
            L.i("retrying on a new connection");
            out = runIsolatedOnce(context, command, timeoutMs - first);
        }
        return out;
    }

    private static String runIsolatedOnce(Context context, String command, long timeoutMs) {
        Context app = context.getApplicationContext();
        final AdbManager adb;
        try {
            adb = new AdbManager(app);
        } catch (Exception e) {
            L.i("key load failed: " + e);
            return null;
        }
        java.util.concurrent.FutureTask<String> task =
                new java.util.concurrent.FutureTask<>(() -> adb.runOnce(app, command));
        Thread worker = new Thread(task, "adb-cmd");
        worker.setDaemon(true);
        worker.start();
        try {
            return task.get(timeoutMs, TimeUnit.MILLISECONDS);
        } catch (Exception e) {
            L.i("command timed out or failed: " + e);
            task.cancel(true);
            new Thread(() -> {
                try {
                    adb.close();
                } catch (Exception ignored) {
                    // Abandoned.
                }
            }, "adb-close").start();
            return null;
        }
    }

    private AdbManager(Context context) throws Exception {
        L.init(context);
        setApi(Build.VERSION.SDK_INT);
        // libadb waits forever by default; a stale connection must fail fast instead.
        setTimeout(2, TimeUnit.SECONDS);
        File dir = context.getFilesDir();
        File keyFile = new File(dir, KEY_FILE);
        File certFile = new File(dir, CERT_FILE);
        if (keyFile.exists() && certFile.exists()) {
            privateKey = KeyFactory.getInstance("RSA")
                    .generatePrivate(new PKCS8EncodedKeySpec(Files.readAllBytes(keyFile.toPath())));
            try (InputStream in = new FileInputStream(certFile)) {
                certificate = CertificateFactory.getInstance("X.509").generateCertificate(in);
            }
        } else {
            KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
            generator.initialize(2048, new SecureRandom());
            KeyPair pair = generator.generateKeyPair();
            privateKey = pair.getPrivate();
            certificate = selfSign(pair.getPublic(), privateKey);
            try (FileOutputStream out = new FileOutputStream(keyFile)) {
                out.write(privateKey.getEncoded());
            }
            try (FileOutputStream out = new FileOutputStream(certFile)) {
                out.write(certificate.getEncoded());
            }
        }
    }

    private static Certificate selfSign(PublicKey publicKey, PrivateKey privateKey) throws Exception {
        String algorithm = "SHA512withRSA";
        X500Name name = new X500Name("CN=Assistant Button");
        Date notBefore = new Date();
        Date notAfter = new Date(notBefore.getTime() + TEN_YEARS_MS);
        CertificateExtensions extensions = new CertificateExtensions();
        extensions.set("SubjectKeyIdentifier", new SubjectKeyIdentifierExtension(
                new KeyIdentifier(publicKey).getIdentifier()));
        extensions.set("PrivateKeyUsage", new PrivateKeyUsageExtension(notBefore, notAfter));
        X509CertInfo info = new X509CertInfo();
        info.set("version", new CertificateVersion(2));
        info.set("serialNumber", new CertificateSerialNumber(new Random().nextInt() & Integer.MAX_VALUE));
        info.set("algorithmID", new CertificateAlgorithmId(AlgorithmId.get(algorithm)));
        info.set("subject", new CertificateSubjectName(name));
        info.set("key", new CertificateX509Key(publicKey));
        info.set("validity", new CertificateValidity(notBefore, notAfter));
        info.set("issuer", new CertificateIssuerName(name));
        info.set("extensions", extensions);
        X509CertImpl cert = new X509CertImpl(info);
        cert.sign(privateKey, algorithm);
        return cert;
    }

    @Override
    protected PrivateKey getPrivateKey() {
        return privateKey;
    }

    @Override
    protected Certificate getCertificate() {
        return certificate;
    }

    @Override
    protected String getDeviceName() {
        return "AssistantButton";
    }

    // ---------------------------------------------------------------- helpers

    /**
     * Opens a fresh connection to adbd. Long-lived connections go stale (Wi-Fi
     * reconnects, adbd restarts) and then block forever, so every command gets its
     * own connection. Order: remembered port, then mDNS discovery, then switching
     * Wireless debugging back on (it is off after every reboot) when we hold
     * WRITE_SECURE_SETTINGS.
     */
    synchronized boolean ensureConnected(Context context) {
        reset();
        int port = Prefs.getPort(context);
        L.i("connect: saved port " + port);
        if (port > 0 && tryConnect(port)) {
            return true;
        }
        if (connectDiscovered(context)) {
            return true;
        }
        if (canWriteSecureSettings(context)) {
            ContentResolver resolver = context.getContentResolver();
            Settings.Global.putInt(resolver, "adb_enabled", 1);
            Settings.Global.putInt(resolver, "adb_wifi_enabled", 1);
            // adbd needs a moment to start listening and announce itself over mDNS.
            for (int attempt = 0; attempt < 3; attempt++) {
                if (connectDiscovered(context)) {
                    return true;
                }
            }
        }
        return false;
    }

    private boolean connectDiscovered(Context context) {
        int port = discoverPort(context, 2000);
        L.i("mdns port " + port);
        if (port > 0 && tryConnect(port)) {
            Prefs.setPort(context, port);
            return true;
        }
        return false;
    }

    private boolean tryConnect(int port) {
        try {
            if (connect("127.0.0.1", port) || isConnected()) {
                L.i("connected on " + port);
                return true;
            }
            L.i("connect returned false on " + port);
        } catch (Exception e) {
            L.i("connect failed on " + port + ": " + e);
        }
        reset();
        return false;
    }

    /** Finds adbd's current Wireless debugging port via mDNS, or -1. */
    private static int discoverPort(Context context, long timeoutMs) {
        AtomicInteger found = new AtomicInteger(-1);
        CountDownLatch latch = new CountDownLatch(1);
        AdbMdns mdns = new AdbMdns(context, AdbMdns.SERVICE_TYPE_TLS_CONNECT, (host, port) -> {
            if (port > 0) {
                found.set(port);
                latch.countDown();
            }
        });
        mdns.start();
        try {
            latch.await(timeoutMs, TimeUnit.MILLISECONDS);
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
        } finally {
            mdns.stop();
        }
        return found.get();
    }

    /** Closes the current connection, if any. */
    void reset() {
        try {
            disconnect();
        } catch (Exception ignored) {
            // Already gone.
        }
    }

    /**
     * Connects, runs one command and disconnects.
     *
     * @return the command output, or null if ADB is unavailable.
     */
    synchronized String runOnce(Context context, String command) {
        try {
            if (!ensureConnected(context)) {
                return null;
            }
            long t = System.currentTimeMillis();
            String out = shell(command);
            L.i("shell done in " + (System.currentTimeMillis() - t) + "ms: " + out.trim());
            return out;
        } catch (Exception e) {
            L.i("runOnce failed: " + e);
            return null;
        } finally {
            reset();
            L.i("disconnected");
        }
    }

    /** Runs a shell command over ADB and returns its output. */
    String shell(String command) throws Exception {
        AdbStream stream = openStream("shell:" + command);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (InputStream in = stream.openInputStream()) {
            byte[] buffer = new byte[4096];
            int n;
            while ((n = in.read(buffer)) != -1) {
                out.write(buffer, 0, n);
            }
        } catch (IOException ignored) {
            // Stream closed by adbd when the command ends.
        } finally {
            stream.close();
        }
        return out.toString(StandardCharsets.UTF_8.name());
    }

    static boolean canWriteSecureSettings(Context context) {
        return context.checkSelfPermission("android.permission.WRITE_SECURE_SETTINGS")
                == PackageManager.PERMISSION_GRANTED;
    }
}
