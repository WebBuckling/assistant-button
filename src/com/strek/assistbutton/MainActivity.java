package com.strek.assistbutton;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.widget.Toast;

/**
 * Invisible activity that opens the default digital assistant (Gemini, Claude,
 * ChatGPT... whatever is selected in system settings) as its floating overlay,
 * via Activity.showAssist() - the same system path a long press on Home uses.
 * Bind it to any button or gesture that can launch an app (e.g. Honor AI key).
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

        boolean shown = false;
        try {
            // Only works while this activity is in the foreground.
            shown = showAssist(null);
        } catch (RuntimeException ignored) {
            // Treated as "no assistant available" below.
        }

        if (!shown) {
            // No default assistant (or it refused): send the user to the picker.
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
        handler.postDelayed(() -> {
            finish();
            overridePendingTransition(0, 0);
        }, shown ? 300 : 0);
    }
}
