package com.strek.assistbutton;

import android.content.AttributionSource;
import android.os.Binder;
import android.os.Bundle;
import android.os.IBinder;
import android.os.Looper;
import android.os.Parcel;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.InputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;

/**
 * Background helper with shell (adb) privileges, the way the Shizuku server works.
 *
 * <p>Started once over ADB with {@code app_process} from this app's own APK, it
 * keeps running until reboot, so pressing the button then needs neither Wi-Fi nor
 * an ADB connection.
 *
 * <p>SELinux doesn't let apps connect to sockets of shell processes, so the helper
 * talks Binder instead. It hands its Binder to the app through the app's
 * {@link HelperProvider}, now and whenever the app's process (re)starts, which it
 * learns from an {@code IProcessObserver}. Only the app's UID may call it.
 *
 * <p>Runs outside the Android app runtime (no Context), so hidden framework APIs
 * are reached by reflection. Hidden-API restrictions don't apply to shell.
 */
public final class Helper {

    static final int VERSION = 2;

    static final int TX_PING = IBinder.FIRST_CALL_TRANSACTION;
    static final int TX_CTS = IBinder.FIRST_CALL_TRANSACTION + 1;
    static final int TX_EXIT = IBinder.FIRST_CALL_TRANSACTION + 2;

    static final String PROVIDER = "com.strek.assistbutton.helper";
    static final String METHOD_BINDER = "binder";
    static final String EXTRA_BINDER = "binder";

    private static int appUid;
    private static Object activityManager;
    private static final Binder SERVICE = new ServiceBinder();
    private static volatile int lastDeliveredPid = -1;

    private Helper() {
    }

    /** Args: app UID, APK path (the helper exits when the APK is gone or replaced). */
    public static void main(String[] args) throws Exception {
        appUid = Integer.parseInt(args[0]);
        File apk = new File(args[1]);
        Looper.prepareMainLooper();
        activityManager = Class.forName("android.app.ActivityManager")
                .getMethod("getService").invoke(null);

        registerProcessObserver();
        deliverBinder();

        // Exit when the app is updated or removed: the next press starts a fresh helper.
        Thread watcher = new Thread(() -> {
            while (true) {
                try {
                    Thread.sleep(30_000);
                } catch (InterruptedException ignored) {
                    // Keep watching.
                }
                if (!apk.exists()) {
                    System.exit(0);
                }
            }
        }, "apk-watcher");
        watcher.setDaemon(true);
        watcher.start();

        Looper.loop();
    }

    // ------------------------------------------------------------ Binder service

    private static final class ServiceBinder extends Binder {
        @Override
        protected boolean onTransact(int code, Parcel data, Parcel reply, int flags) {
            int caller = Binder.getCallingUid();
            if (caller != appUid && caller != 0 && caller != 2000) {
                return false;
            }
            switch (code) {
                case TX_PING:
                    reply.writeInt(VERSION);
                    return true;
                case TX_CTS:
                    String result = sh(Commands.OPEN_CIRCLE_TO_SEARCH, 8);
                    reply.writeString(result.contains(Commands.SUCCESS)
                            ? "ok" : "fail " + result.replace('\n', ' ').trim());
                    return true;
                case TX_EXIT:
                    reply.writeInt(0);
                    new Thread(() -> {
                        try {
                            Thread.sleep(200);
                        } catch (InterruptedException ignored) {
                            // Exit anyway.
                        }
                        System.exit(0);
                    }).start();
                    return true;
                default:
                    return false;
            }
        }
    }

    // ------------------------------------------------- delivering the Binder

    /**
     * Calls the app's HelperProvider with our Binder, like Shizuku's
     * sendBinderToUserApp(). Starts the app process if needed.
     */
    private static synchronized void deliverBinder() {
        Object holder = null;
        try {
            Method get = findMethod(activityManager.getClass(), "getContentProviderExternal", 4);
            holder = get.invoke(activityManager, PROVIDER, 0, null, PROVIDER);
            if (holder == null) {
                return;
            }
            Field providerField = holder.getClass().getField("provider");
            Object provider = providerField.get(holder);
            Bundle extras = new Bundle();
            extras.putBinder(EXTRA_BINDER, SERVICE);
            Method call = findCall(provider.getClass());
            AttributionSource source = new AttributionSource.Builder(2000)
                    .setPackageName("com.android.shell")
                    .build();
            call.invoke(provider, source, PROVIDER, METHOD_BINDER, null, extras);
        } catch (Throwable e) {
            System.err.println("deliverBinder failed: " + e);
        } finally {
            if (holder != null) {
                try {
                    Method remove = findMethod(activityManager.getClass(),
                            "removeContentProviderExternal", 2);
                    remove.invoke(activityManager, PROVIDER, null);
                } catch (Throwable ignored) {
                    // Best effort.
                }
            }
        }
    }

    /** IContentProvider.call(AttributionSource, authority, method, arg, extras), Android 12+. */
    private static Method findCall(Class<?> cls) throws NoSuchMethodException {
        for (Method m : cls.getMethods()) {
            Class<?>[] p = m.getParameterTypes();
            if (m.getName().equals("call") && p.length == 5 && p[0] == AttributionSource.class) {
                return m;
            }
        }
        throw new NoSuchMethodException("IContentProvider.call");
    }

    private static Method findMethod(Class<?> cls, String name, int params) throws NoSuchMethodException {
        for (Method m : cls.getMethods()) {
            if (m.getName().equals(name) && m.getParameterTypes().length == params) {
                return m;
            }
        }
        throw new NoSuchMethodException(name);
    }

    /**
     * Gets told by ActivityManager about process changes. Every IProcessObserver
     * callback starts with (pid, uid), so it's implemented as a raw Binder: no
     * hidden stub class needed at compile time.
     */
    private static void registerProcessObserver() {
        try {
            String descriptor = "android.app.IProcessObserver";
            Binder observer = new Binder() {
                @Override
                protected boolean onTransact(int code, Parcel data, Parcel reply, int flags) {
                    if (code < FIRST_CALL_TRANSACTION || code > LAST_CALL_TRANSACTION) {
                        return false;
                    }
                    try {
                        data.enforceInterface(descriptor);
                        int pid = data.readInt();
                        int uid = data.readInt();
                        if (uid == appUid && pid != lastDeliveredPid) {
                            lastDeliveredPid = pid;
                            new Thread(Helper::deliverBinder, "deliver").start();
                        }
                    } catch (Throwable ignored) {
                        // Unknown callback shape; ignore.
                    }
                    return true;
                }
            };
            Class<?> stub = Class.forName(descriptor + "$Stub");
            Object proxy = stub.getMethod("asInterface", IBinder.class).invoke(null, observer);
            Method register = findMethod(activityManager.getClass(), "registerProcessObserver", 1);
            register.invoke(activityManager, proxy);
        } catch (Throwable e) {
            System.err.println("registerProcessObserver failed: " + e);
        }
    }

    // ------------------------------------------------------------------ shell

    /** Runs a shell command as this (shell) user and returns stdout + stderr. */
    private static String sh(String command, int timeoutSeconds) {
        try {
            Process process = new ProcessBuilder("sh", "-c", command)
                    .redirectErrorStream(true)
                    .start();
            ByteArrayOutputStream buffer = new ByteArrayOutputStream();
            Thread reader = new Thread(() -> {
                try (InputStream stream = process.getInputStream()) {
                    byte[] chunk = new byte[1024];
                    int n;
                    while ((n = stream.read(chunk)) != -1) {
                        synchronized (buffer) {
                            buffer.write(chunk, 0, n);
                        }
                    }
                } catch (Exception ignored) {
                    // Process ended.
                }
            });
            reader.start();
            if (!process.waitFor(timeoutSeconds, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                return "timeout";
            }
            reader.join(500);
            synchronized (buffer) {
                return buffer.toString(StandardCharsets.UTF_8.name());
            }
        } catch (Exception e) {
            return "error " + e;
        }
    }

    /** Shell command that starts the helper in the background, detached from the ADB session. */
    static String startCommand(String apkPath, int appUid) {
        return "CLASSPATH=" + apkPath
                + " setsid app_process /system/bin " + Helper.class.getName()
                + " " + appUid + " " + apkPath + " </dev/null >/data/local/tmp/assistbutton-helper.log 2>&1 &";
    }
}
