package com.fantest.ownerguard;

import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Build;

public class BootReceiver extends BroadcastReceiver {
    @Override public void onReceive(Context c, Intent intent) {
        NotificationManager nm = (NotificationManager) c.getSystemService(Context.NOTIFICATION_SERVICE);
        String channel = "owner_guard_status";
        if (Build.VERSION.SDK_INT >= 26) nm.createNotificationChannel(new NotificationChannel(channel, "OwnerGuard status", NotificationManager.IMPORTANCE_DEFAULT));
        PendingIntent pi = PendingIntent.getActivity(c, 1, new Intent(c, MainActivity.class), PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        android.app.Notification n = new android.app.Notification.Builder(c, channel)
                .setSmallIcon(com.fantest.ownerguard.R.drawable.ic_shield)
                .setContentTitle("OwnerGuard needs re-arming")
                .setContentText("Open OwnerGuard after reboot to re-arm camera protection.")
                .setContentIntent(pi).setAutoCancel(true).build();
        nm.notify(1202, n);
    }
}
