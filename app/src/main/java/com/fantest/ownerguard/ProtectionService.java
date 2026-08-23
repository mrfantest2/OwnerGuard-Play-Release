package com.fantest.ownerguard;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;

import java.io.File;

public class ProtectionService extends Service {
    public static final String ACTION_ARM = "com.fantest.ownerguard.ARM";
    public static final String ACTION_DISARM = "com.fantest.ownerguard.DISARM";
    public static final String ACTION_TEST = "com.fantest.ownerguard.TEST";
    public static final String ACTION_FAILED_CREDENTIAL = "com.fantest.ownerguard.FAILED_CREDENTIAL_CAPTURE";
    public static final String EXTRA_FAILED_COUNT = "failed_count";
    public static final String EXTRA_FAILED_AT = "failed_at";

    private static final int NOTIFICATION_ID = 1201;
    private static final long DUPLICATE_FAILED_CALLBACK_MS = 1_500L;
    private final Handler main = new Handler(Looper.getMainLooper());
    private boolean receiverRegistered;
    private volatile boolean captureRunning;
    private volatile boolean pendingFailedCapture;
    private volatile boolean pendingUnlockCapture;
    private volatile long lastFailedCaptureRequestAt;

    private final BroadcastReceiver unlockReceiver = new BroadcastReceiver() {
        @Override public void onReceive(Context c, Intent i) {
            if (Intent.ACTION_USER_PRESENT.equals(i.getAction()) && isArmed()) {
                main.postDelayed(() -> requestCapture(false, false), 800L);
            }
        }
    };

    @Override public void onCreate() {
        super.onCreate();
        registerReceiver(unlockReceiver, new IntentFilter(Intent.ACTION_USER_PRESENT));
        receiverRegistered = true;
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        startForeground(NOTIFICATION_ID, notification("Protection is active. Camera use remains visible."));
        String action = intent == null ? ACTION_ARM : intent.getAction();
        if (ACTION_DISARM.equals(action)) {
            getSharedPreferences("owner_guard_settings", MODE_PRIVATE).edit().putBoolean("armed", false).apply();
            pendingFailedCapture = false;
            pendingUnlockCapture = false;
            stopForeground(STOP_FOREGROUND_REMOVE);
            stopSelf();
            return START_NOT_STICKY;
        }
        if (ACTION_TEST.equals(action)) {
            requestCapture(true, false);
            return START_NOT_STICKY;
        }
        if (ACTION_FAILED_CREDENTIAL.equals(action)) {
            if (isArmed() && getSharedPreferences("owner_guard_settings", MODE_PRIVATE).getBoolean("instant_failed_capture", true)) {
                requestImmediateFailedCapture();
            }
            return START_STICKY;
        }
        getSharedPreferences("owner_guard_settings", MODE_PRIVATE).edit().putBoolean("armed", true).apply();
        return START_STICKY;
    }

    private boolean isArmed() {
        return getSharedPreferences("owner_guard_settings", MODE_PRIVATE).getBoolean("armed", false);
    }

    private void requestImmediateFailedCapture() {
        long now = System.currentTimeMillis();
        if (now - lastFailedCaptureRequestAt < DUPLICATE_FAILED_CALLBACK_MS) return;
        lastFailedCaptureRequestAt = now;
        requestCapture(false, true);
    }

    private void requestCapture(boolean test, boolean immediateFailed) {
        if (checkSelfPermission(android.Manifest.permission.CAMERA) != android.content.pm.PackageManager.PERMISSION_GRANTED) return;
        if (captureRunning) {
            if (immediateFailed) pendingFailedCapture = true;
            else if (!test) pendingUnlockCapture = true;
            return;
        }
        captureNow(test, immediateFailed);
    }

    private void captureNow(boolean test, boolean immediateFailed) {
        captureRunning = true;
        SharedPreferences runtime = getSharedPreferences(TheftAdminReceiver.PREF, MODE_PRIVATE);
        int failures = test ? 0 : runtime.getInt("failed_credentials", 0);
        boolean successAfterFailure = !test && runtime.getBoolean("success_after_failure", false);
        String reason;
        if (test) reason = "VISIBLE_TEST";
        else if (immediateFailed) reason = "FAILED_PATTERN_PIN_OR_PASSWORD_IMMEDIATE";
        else if (failures > 0 || successAfterFailure) reason = "FAILED_PATTERN_PIN_OR_PASSWORD_THEN_UNLOCK";
        else reason = "COMPLETED_UNLOCK_OWNER_CHECK";

        LocationSnapshot.refresh(this);
        PowerManager.WakeLock wake = ((PowerManager) getSystemService(POWER_SERVICE))
                .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "OwnerGuard:Capture");
        wake.acquire(45_000L);
        updateNotification(immediateFailed
                ? "Failed credential detected — capturing evidence now…"
                : "Capturing front-camera photos and 5-second video…");

        new CameraCaptureManager(this).capture(reason, failures, result -> main.post(() -> {
            try {
                if (CloudBackupManager.enabled(this) && result.eventDirectory != null && result.eventDirectory.isDirectory()) {
                    CloudBackupManager.backupEvent(this, result.eventDirectory);
                }
                if (!immediateFailed) {
                    boolean autoDelete = getSharedPreferences("owner_guard_settings", MODE_PRIVATE).getBoolean("auto_delete_owner", false);
                    // Permanent cloud backup takes priority over local auto-delete so the uploader can read the incident.
                    if (!CloudBackupManager.enabled(this) && !test && failures == 0 && autoDelete && result.ownerSimilarity >= 0.94) {
                        deleteRecursively(result.eventDirectory);
                    }
                    runtime.edit().putInt("failed_credentials", 0).putBoolean("success_after_failure", false).apply();
                }
            } finally {
                captureRunning = false;
                updateNotification((immediateFailed ? "Failed-attempt evidence saved: " : "Last capture saved: ")
                        + result.photosSaved + " photos, video " + (result.videoSaved ? "saved" : "failed") + ".");
                if (wake.isHeld()) wake.release();

                boolean runFailedNext = pendingFailedCapture;
                boolean runUnlockNext = pendingUnlockCapture;
                pendingFailedCapture = false;
                pendingUnlockCapture = false;
                if (runFailedNext && isArmed()) {
                    main.postDelayed(() -> requestCapture(false, true), 800L);
                } else if (runUnlockNext && isArmed()) {
                    main.postDelayed(() -> requestCapture(false, false), 1_000L);
                } else if (test && !isArmed()) {
                    stopForeground(STOP_FOREGROUND_REMOVE);
                    stopSelf();
                }
            }
        }));
    }

    private void updateNotification(String message) {
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        nm.notify(NOTIFICATION_ID, notification(message));
    }

    private Notification notification(String message) {
        String channel = "owner_guard_protection";
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        NotificationChannel ch = new NotificationChannel(channel, "OwnerGuard protection", NotificationManager.IMPORTANCE_LOW);
        ch.setShowBadge(false);
        ch.setSound(null, null);
        nm.createNotificationChannel(ch);
        boolean minimal = getSharedPreferences("owner_guard_settings", MODE_PRIVATE).getBoolean("minimal_notification", true);
        PendingIntent pi = PendingIntent.getActivity(this, 0, new Intent(this, MainActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP),
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        Notification.Builder b = new Notification.Builder(this, channel)
                .setSmallIcon(R.drawable.ic_shield)
                .setContentTitle("OwnerGuard protection active")
                .setContentText(minimal ? "Protection service running" : message)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setVisibility(Notification.VISIBILITY_SECRET)
                .setContentIntent(pi)
                .setCategory(Notification.CATEGORY_SERVICE);
        return b.build();
    }

    private static void deleteRecursively(File f) {
        if (f == null) return;
        File[] children = f.listFiles();
        if (children != null) for (File c : children) deleteRecursively(c);
        f.delete();
    }

    @Override public void onDestroy() {
        if (receiverRegistered) unregisterReceiver(unlockReceiver);
        super.onDestroy();
    }

    @Override public IBinder onBind(Intent intent) { return null; }
}
