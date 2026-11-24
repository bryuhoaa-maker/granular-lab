package com.granularlab;

import android.app.Notification;
import android.app.PendingIntent;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.IBinder;

/** Держит процесс живым, пока синт играет в фоне. Удаление уведомления останавливает движок. */
public class SynthService extends Service {

    private static final String STOP = "com.granularlab.STOP";
    private BroadcastReceiver stopRcv;

    @Override
    public void onCreate() {
        super.onCreate();
        Intent open = new Intent(this, MainActivity.class);
        open.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        PendingIntent pi = PendingIntent.getActivity(this, 0, open, 0);
        PendingIntent di = PendingIntent.getBroadcast(this, 1, new Intent(STOP), 0);

        Notification n = new Notification.Builder(this)
                .setSmallIcon(R.mipmap.ic_launcher)
                .setContentTitle("GranularLab")
                .setContentText("Синт работает · тапни, чтобы открыть")
                .setContentIntent(pi)
                .setDeleteIntent(di)
                .setOngoing(true)
                .build();
        startForeground(1, n);

        stopRcv = new BroadcastReceiver() {
            @Override
            public void onReceive(Context c, Intent i) {
                Engine.get().stop();
                stopForeground(true);
                stopSelf();
                android.os.Process.killProcess(android.os.Process.myPid());
            }
        };
        registerReceiver(stopRcv, new IntentFilter(STOP));
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        return START_STICKY;
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onDestroy() {
        try { unregisterReceiver(stopRcv); } catch (Exception ignored) { }
        super.onDestroy();
    }
}
