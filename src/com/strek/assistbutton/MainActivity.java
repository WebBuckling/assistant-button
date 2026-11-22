package com.strek.assistbutton;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
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
                String out = AdbManager.runIsolated(app, Commands.OPEN_CIRCLE_TO_SEARCH, 8500);
                ok = out != null && out.contains(Commands.SUCCESS);
            } catch (Exception ignored) {
                // Fall back below.
            } finally {
                BUSY.set(false);
            }
            boolean success = ok;
            runOnUiThread(() -> onAdbResult(success, false));
        }, "circle-to-search").start();
    }

    // Leaves room for a reconnect plus restarting a stuck Google app.
    private static final long ADB_TIMEOUT_MS = 10000;
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
        if (!shown) {
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
