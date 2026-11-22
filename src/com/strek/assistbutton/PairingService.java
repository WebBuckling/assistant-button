package com.strek.assistbutton;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.RemoteInput;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.Bundle;
import android.os.IBinder;

import io.github.muntashirakon.adb.android.AdbMdns;

/**
 * Pairs the app with Wireless debugging, the way Shizuku does: it watches for the
 * "Pair device with pairing code" dialog over mDNS and asks for the 6-digit code
 * in a notification, so the user can type it without leaving Settings (the
 * dialog closes, and the code changes, as soon as Settings goes to background).
 */
public class PairingService extends Service {

    private static final String CHANNEL = "pairing";
    private static final int NOTIFICATION_ID = 1;
    private static final String KEY_CODE = "code";
    private static final String EXTRA_PORT = "port";

    private AdbMdns mdns;

    static void start(Context context) {
        context.startForegroundService(new Intent(context, PairingService.class));
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        createChannel(this);
        startForeground(NOTIFICATION_ID,
                status(this, getString(R.string.pair_searching), true),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
        if (mdns == null) {
            mdns = new AdbMdns(this, AdbMdns.SERVICE_TYPE_TLS_PAIRING, (host, port) -> {
                if (port > 0) {
                    notifyManager(this).notify(NOTIFICATION_ID, askForCode(port));
                }
            });
            mdns.start();
        }
        return START_NOT_STICKY;
    }

    @Override
    public void onDestroy() {
        if (mdns != null) {
            mdns.stop();
        }
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    private Notification askForCode(int port) {
        RemoteInput input = new RemoteInput.Builder(KEY_CODE)
                .setLabel(getString(R.string.pair_code_hint))
                .build();
        Intent reply = new Intent(this, CodeReceiver.class).putExtra(EXTRA_PORT, port);
        PendingIntent pending = PendingIntent.getBroadcast(this, port, reply,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_MUTABLE);
        Notification.Action action = new Notification.Action.Builder(
                null, getString(R.string.pair_enter_code), pending)
                .addRemoteInput(input)
                .build();
        return new Notification.Builder(this, CHANNEL)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle(getString(R.string.pair_found_title))
                .setContentText(getString(R.string.pair_found_text))
                .setOngoing(true)
                .addAction(action)
                .build();
    }

    static Notification status(Context context, String text, boolean ongoing) {
        createChannel(context);
        return new Notification.Builder(context, CHANNEL)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle(context.getString(R.string.app_name))
                .setContentText(text)
                .setStyle(new Notification.BigTextStyle().bigText(text))
                .setOngoing(ongoing)
                .build();
    }

    private static void createChannel(Context context) {
        notifyManager(context).createNotificationChannel(new NotificationChannel(
                CHANNEL, context.getString(R.string.pair_channel), NotificationManager.IMPORTANCE_HIGH));
    }

    private static NotificationManager notifyManager(Context context) {
        return context.getSystemService(NotificationManager.class);
    }

    /** Receives the pairing code typed into the notification. */
    public static class CodeReceiver extends BroadcastReceiver {
        @Override
        public void onReceive(Context context, Intent intent) {
            Bundle results = RemoteInput.getResultsFromIntent(intent);
            CharSequence code = results == null ? null : results.getCharSequence(KEY_CODE);
            int port = intent.getIntExtra(EXTRA_PORT, -1);
            if (code == null || port <= 0) {
                return;
            }
            Context app = context.getApplicationContext();
            notifyManager(app).notify(NOTIFICATION_ID, status(app, app.getString(R.string.pair_working), true));
            PendingResult pending = goAsync();
            new Thread(() -> {
                String message;
                try {
                    AdbManager adb = AdbManager.get(app);
                    if (!adb.pair("127.0.0.1", port, code.toString().trim())) {
                        throw new IllegalStateException("pairing rejected");
                    }
                    if (!adb.ensureConnected(app)) {
                        throw new IllegalStateException("connected = false");
                    }
                    // Lets the app switch Wireless debugging back on after a reboot.
                    adb.shell(Commands.grantSecureSettings(app.getPackageName()));
                    Prefs.setPaired(app, true);
                    message = app.getString(R.string.pair_done);
                } catch (Exception e) {
                    message = app.getString(R.string.pair_failed, String.valueOf(e.getMessage()));
                }
                app.stopService(new Intent(app, PairingService.class));
                notifyManager(app).notify(NOTIFICATION_ID, status(app, message, false));
                pending.finish();
            }, "adb-pairing").start();
        }
    }
}
