package com.strek.assistbutton;

import android.content.ContentResolver;
import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Build;
import android.provider.Settings;
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

import io.github.muntashirakon.adb.AbsAdbConnectionManager;
import io.github.muntashirakon.adb.AdbStream;

/**
 * Talks to this phone's own adbd over Wireless debugging, the way Shizuku does,
 * so the app can run commands with shell (adb) privileges without a PC.
 */
final class AdbManager extends AbsAdbConnectionManager {

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

    private AdbManager(Context context) throws Exception {
        setApi(Build.VERSION.SDK_INT);
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
     * Makes sure we are connected to adbd. If Wireless debugging was switched off
     * (it is after every reboot), turns it back on when we hold WRITE_SECURE_SETTINGS.
     */
    synchronized boolean ensureConnected(Context context) throws Exception {
        if (isConnected()) {
            return true;
        }
        if (tryConnect(context, 3000)) {
            return true;
        }
        if (canWriteSecureSettings(context)) {
            ContentResolver resolver = context.getContentResolver();
            Settings.Global.putInt(resolver, "adb_enabled", 1);
            Settings.Global.putInt(resolver, "adb_wifi_enabled", 1);
            // adbd needs a moment to start listening and announce itself over mDNS.
            for (int attempt = 0; attempt < 4; attempt++) {
                if (tryConnect(context, 2500)) {
                    return true;
                }
            }
        }
        return false;
    }

    private boolean tryConnect(Context context, long timeoutMs) {
        try {
            return connectTls(context, timeoutMs) || isConnected();
        } catch (Exception e) {
            return false;
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
