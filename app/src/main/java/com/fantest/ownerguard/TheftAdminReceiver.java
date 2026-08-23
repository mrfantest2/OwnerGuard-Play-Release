package com.fantest.ownerguard;

import android.app.admin.DeviceAdminReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.os.Build;

/** Handles failed pattern, PIN, and password attempts through Android Device Administration. */
public class TheftAdminReceiver extends DeviceAdminReceiver {
    static final String PREF = "owner_guard_runtime";

    @Override
    public void onPasswordFailed(Context context, Intent intent) {
        SharedPreferences runtime = context.getSharedPreferences(PREF, Context.MODE_PRIVATE);
        int count = runtime.getInt("failed_credentials", 0) + 1;
        long failedAt = System.currentTimeMillis();
        runtime.edit()
                .putInt("failed_credentials", count)
                .putLong("last_failed_at", failedAt)
                .putBoolean("success_after_failure", false)
                .apply();

        SharedPreferences settings = context.getSharedPreferences("owner_guard_settings", Context.MODE_PRIVATE);
        boolean armed = settings.getBoolean("armed", false);
        boolean instant = settings.getBoolean("instant_failed_capture", true);
        boolean cameraGranted = context.checkSelfPermission(android.Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED;
        if (!armed || !instant || !cameraGranted) return;

        Intent capture = new Intent(context, ProtectionService.class)
                .setAction(ProtectionService.ACTION_FAILED_CREDENTIAL)
                .putExtra(ProtectionService.EXTRA_FAILED_COUNT, count)
                .putExtra(ProtectionService.EXTRA_FAILED_AT, failedAt);
        try {
            if (Build.VERSION.SDK_INT >= 26) context.startForegroundService(capture);
            else context.startService(capture);
            runtime.edit().remove("failed_capture_start_error").apply();
        } catch (Throwable error) {
            runtime.edit().putString("failed_capture_start_error", error.getClass().getSimpleName() + ":" + String.valueOf(error.getMessage())).apply();
        }
    }

    @Override
    public void onPasswordSucceeded(Context context, Intent intent) {
        SharedPreferences p = context.getSharedPreferences(PREF, Context.MODE_PRIVATE);
        if (p.getInt("failed_credentials", 0) > 0) {
            p.edit().putBoolean("success_after_failure", true)
                    .putLong("credential_success_at", System.currentTimeMillis()).apply();
        }
    }
}
