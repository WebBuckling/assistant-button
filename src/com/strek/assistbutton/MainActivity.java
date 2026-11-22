package com.strek.assistbutton;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.widget.Toast;

/**
 * The button. Invisible: it opens Circle to Search (when set up with Wireless
 * debugging) or the default assistant's overlay, then closes itself.
 */
public class MainActivity extends Activity {

    private final Handler handler = new Handler(Looper.getMainLooper());
    private boolean triggered;

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
        new Thread(() -> {
            boolean ok = false;
            try {
                AdbManager adb = AdbManager.get(this);
                if (adb.ensureConnected(this)) {
                    ok = adb.shell(Commands.CIRCLE_TO_SEARCH).contains(Commands.SUCCESS);
                }
            } catch (Exception ignored) {
                // Fall back below.
            }
            boolean success = ok;
            runOnUiThread(() -> {
                if (success) {
                    close(0);
                } else {
                    Toast.makeText(this, R.string.adb_unavailable, Toast.LENGTH_SHORT).show();
                    showAssistant();
                }
            });
        }, "circle-to-search").start();
    }

    /** Opens the default assistant's overlay (no screen access for apps). */
    private void showAssistant() {
        boolean shown = false;
        try {
            // Only works while this activity is in the foreground.
            shown = showAssist(null);
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
