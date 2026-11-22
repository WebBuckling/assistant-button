package com.strek.assistbutton;

import android.content.Context;
import android.content.SharedPreferences;

final class Prefs {
    private static final String FILE = "assistant_button";
    private static final String PAIRED = "paired";
    private static final String PORT = "adb_port";

    private Prefs() {
    }

    private static SharedPreferences prefs(Context context) {
        return context.getSharedPreferences(FILE, Context.MODE_PRIVATE);
    }

    static boolean isPaired(Context context) {
        return prefs(context).getBoolean(PAIRED, false);
    }

    static void setPaired(Context context, boolean paired) {
        prefs(context).edit().putBoolean(PAIRED, paired).apply();
    }

    /** Last known Wireless debugging (adb-tls-connect) port, or -1. */
    static int getPort(Context context) {
        return prefs(context).getInt(PORT, -1);
    }

    static void setPort(Context context, int port) {
        prefs(context).edit().putInt(PORT, port).apply();
    }
}
