package com.strek.assistbutton;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.provider.Settings;
import android.util.Log;
import android.view.WindowManager;
import android.widget.Toast;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * The button. Invisible: it opens Circle to Search (when set up with Wireless
 * debugging) or the default assistant's overlay, then closes itself.
 */
public class MainActivity extends Activity {

    /** One ADB call at a time: extra presses while one is running are ignored. */
    private static final AtomicBoolean BUSY = new AtomicBoolean(false);

    private final Handler handler = new Handler(Looper.getMainLooper());
    private boolean triggered;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        L.init(this);
        // Never swallow touches: the screen must stay usable even if something stalls.
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE);
    }

    @Override
    protected void onResume() {
        super.onResume();
        // Fallback in case window focus never arrives.
        handler.postDelayed(this::trigger, 400);
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) {
            trigger();
        }
    }

    private void trigger() {
        if (triggered) {
            return;
        }
        triggered = true;
        handler.removeCallbacksAndMessages(null);

        if (!Prefs.isPaired(this)) {
            showAssistant();
            return;
        }
        L.i("press, busy=" + BUSY.get());
        if (!BUSY.compareAndSet(false, true)) {
            // A previous press is still talking to ADB; don't pile up work.
            close(0);
            return;
        }
        // Watchdog: this invisible window must never stay on top of the screen.
        handler.postDelayed(() -> onAdbResult(false, true), ADB_TIMEOUT_MS);
        Context app = getApplicationContext();
        new Thread(() -> {
            boolean ok = false;
            try {
                ok = openCircleToSearch(app);
            } catch (Exception e) {
                L.i("open failed: " + e);
            } finally {
                BUSY.set(false);
            }
            boolean success = ok;
            runOnUiThread(() -> onAdbResult(success, false));
        }, "circle-to-search").start();
    }

    /**
     * 1) Ask the background helper (no Wi-Fi, no ADB needed).
     * 2) If it isn't running (first press after a reboot, or after an app update),
     *    start it over ADB and open Circle to Search in the same shell call.
     */
    private static boolean openCircleToSearch(Context app) {
        // The helper sends its Binder as soon as our process starts.
        IBinder helper = HelperClient.await(1200);
        if (helper != null) {
            int version = HelperClient.ping(helper);
            if (version == Helper.VERSION) {
                String reply = HelperClient.openCircleToSearch(helper);
                L.i("helper: " + reply);
                if ("ok".equals(reply)) {
                    return true;
                }
                if (reply != null && reply.startsWith("fail")) {
                    // Helper is alive; Circle to Search itself refused. ADB won't do better.
                    return false;
                }
            } else if (version > 0) {
                L.i("outdated helper v" + version + ", stopping it");
                HelperClient.exit(helper);
            }
        } else {
            L.i("no helper binder");
        }
        // Restarting the helper needs Wireless debugging, i.e. a Wi-Fi network.
        // Without one, don't make the user wait: fall back right away.
        if (!onWifi(app)) {
            L.i("no helper and no Wi-Fi: falling back");
            return false;
        }
        String apk = app.getApplicationInfo().sourceDir;
        int uid = app.getApplicationInfo().uid;
        String out = AdbManager.runIsolated(app,
                Helper.startCommand(apk, uid) + " " + Commands.OPEN_CIRCLE_TO_SEARCH, 6000);
        L.i("adb start+cts: " + (out == null ? "null" : out.trim()));
        return out != null && out.contains(Commands.SUCCESS);
    }

    private static boolean onWifi(Context context) {
        android.net.ConnectivityManager cm = context.getSystemService(android.net.ConnectivityManager.class);
        for (android.net.Network network : cm.getAllNetworks()) {
            android.net.NetworkCapabilities caps = cm.getNetworkCapabilities(network);
            if (caps != null && caps.hasTransport(android.net.NetworkCapabilities.TRANSPORT_WIFI)) {
                return true;
            }
        }
        return false;
    }

    // Leaves room for a reconnect plus restarting a stuck Google app.
    private static final long ADB_TIMEOUT_MS = 8000;
    private volatile boolean adbDone;

    private void onAdbResult(boolean success, boolean timedOut) {
        if (adbDone) {
            return;
        }
        adbDone = true;
        L.i("result success=" + success + " timedOut=" + timedOut);
        handler.removeCallbacksAndMessages(null);
        if (timedOut) {
            // Never lock the button out because of one stuck attempt.
            BUSY.set(false);
            // Kill the stuck connection so the next press starts fresh.
            new Thread(() -> {
                try {
                    AdbManager.get(getApplicationContext()).reset();
                } catch (Exception ignored) {
                    // Nothing to reset.
                }
            }, "adb-reset").start();
        }
        if (success) {
            close(0);
        } else {
            Toast.makeText(this, R.string.adb_unavailable, Toast.LENGTH_SHORT).show();
            showAssistant();
        }
    }

    /** Opens the default assistant's overlay (no screen access for apps). */
    private void showAssistant() {
        boolean shown = false;
        try {
            // Only works while this activity is in the foreground.
            shown = showAssist(null);
            L.i("showAssist -> " + shown);
        } catch (RuntimeException ignored) {
            // Treated as "no assistant available" below.
        }
        // showAssist() also returns false when we've lost focus (e.g. after a slow
        // attempt), so only send the user to the picker if no assistant is set at all.
        String assistant = Settings.Secure.getString(getContentResolver(), "assistant");
        if (!shown && (assistant == null || assistant.isEmpty())) {
            Toast.makeText(this, R.string.choose_assistant, Toast.LENGTH_LONG).show();
            try {
                startActivity(new Intent(Settings.ACTION_VOICE_INPUT_SETTINGS)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            } catch (ActivityNotFoundException ignored) {
                startActivity(new Intent(Settings.ACTION_SETTINGS)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            }
        }
        // Give the assistant session a moment to attach before we disappear.
        close(shown ? 300 : 0);
    }

    private void close(long delayMs) {
        handler.postDelayed(() -> {
            finish();
            overridePendingTransition(0, 0);
        }, delayMs);
    }
}
