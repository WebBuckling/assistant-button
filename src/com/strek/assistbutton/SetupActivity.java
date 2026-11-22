package com.strek.assistbutton;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.provider.Settings;
import android.util.TypedValue;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

/** One-time setup: pair the app with this phone's Wireless debugging. */
public class SetupActivity extends Activity {

    private TextView status;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        int pad = dp(20);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(pad, pad * 2, pad, pad);

        TextView title = text(R.string.setup_name, 24);
        root.addView(title);

        status = text(0, 16);
        status.setPadding(0, dp(12), 0, dp(12));
        root.addView(status);

        root.addView(text(R.string.setup_steps, 15));

        root.addView(button(R.string.setup_open_dev, v -> openDeveloperOptions()));
        root.addView(button(R.string.setup_pair, v -> startPairing()));
        root.addView(button(R.string.setup_test, v ->
                startActivity(new Intent(this, MainActivity.class))));

        root.addView(text(R.string.setup_note, 13));

        ScrollView scroll = new ScrollView(this);
        scroll.addView(root);
        setContentView(scroll);
    }

    @Override
    protected void onResume() {
        super.onResume();
        boolean paired = Prefs.isPaired(this);
        boolean autoStart = AdbManager.canWriteSecureSettings(this);
        status.setText(getString(paired ? R.string.status_paired : R.string.status_not_paired)
                + "\n" + getString(autoStart ? R.string.status_autostart_on : R.string.status_autostart_off));
    }

    private void startPairing() {
        if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 1);
            return;
        }
        PairingService.start(this);
        openDeveloperOptions();
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] results) {
        if (results.length > 0 && results[0] == PackageManager.PERMISSION_GRANTED) {
            startPairing();
        }
    }

    private void openDeveloperOptions() {
        startActivity(new Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS));
    }

    private TextView text(int resId, int sp) {
        TextView view = new TextView(this);
        if (resId != 0) {
            view.setText(resId);
        }
        view.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        view.setPadding(0, dp(6), 0, dp(6));
        return view;
    }

    private Button button(int resId, android.view.View.OnClickListener listener) {
        Button button = new Button(this);
        button.setText(resId);
        button.setAllCaps(false);
        button.setOnClickListener(listener);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        params.topMargin = dp(8);
        button.setLayoutParams(params);
        return button;
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
