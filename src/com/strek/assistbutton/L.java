package com.strek.assistbutton;

import android.content.Context;
import android.util.Log;

import java.io.File;
import java.io.FileWriter;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * Tiny diagnostic log. Some ROMs (Honor) drop third-party apps' logcat output, so
 * entries also go to files/debug.log (debug builds: {@code adb shell run-as
 * com.strek.assistbutton cat files/debug.log}). The file is trimmed at 64 KB.
 */
final class L {
    private static File file;

    private L() {
    }

    static synchronized void init(Context context) {
        if (file == null) {
            file = new File(context.getApplicationContext().getFilesDir(), "debug.log");
        }
    }

    static synchronized void i(String message) {
        Log.i(AdbManager.TAG, message);
        if (file == null) {
            return;
        }
        try {
            if (file.length() > 64 * 1024) {
                file.delete();
            }
            try (FileWriter writer = new FileWriter(file, true)) {
                String time = new SimpleDateFormat("HH:mm:ss.SSS", Locale.US).format(new Date());
                writer.write(time + " [" + Thread.currentThread().getName() + "] " + message + "\n");
            }
        } catch (Exception ignored) {
            // Diagnostics only.
        }
    }
}
