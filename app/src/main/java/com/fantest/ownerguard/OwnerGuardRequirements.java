package com.fantest.ownerguard;

import android.Manifest;
import android.app.Activity;
import android.app.KeyguardManager;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.admin.DevicePolicyManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.PowerManager;
import android.provider.Settings;
import android.view.View;
import android.view.WindowInsets;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Central hard gate for every Android permission and system setting that OwnerGuard
 * needs for dependable protection. Normal application navigation remains unavailable
 * until all verifiable requirements are satisfied.
 */
final class OwnerGuardRequirements {
    static final int REQUEST_RUNTIME = 2040;
    static final int REQUEST_ADMIN = 2041;

    private static final String PREF = "owner_guard_requirements";
    private static final String KEY_SAMSUNG_OPENED = "samsung_never_sleeping_opened";
    private static final String KEY_SAMSUNG_CONFIRMED = "samsung_never_sleeping_confirmed";
    private static final String CHANNEL = "owner_guard_protection";

    private static final String BATTERY = "battery";
    private static final String CAMERA = "camera";
    private static final String NOTIFICATIONS = "notifications";
    private static final String LOCATION = "location";
    private static final String BACKGROUND_LOCATION = "background_location";
    private static final String DEVICE_ADMIN = "device_admin";
    private static final String DEVICE_LOCK = "device_lock";
    private static final String INSTALL_UPDATES = "install_updates";
    private static final String UNUSED_APP = "unused_app";
    private static final String SAMSUNG_NEVER_SLEEPING = "samsung_never_sleeping";

    private OwnerGuardRequirements() {}

    static boolean allReady(Context context) {
        return missing(context).isEmpty();
    }

    static List<String> missing(Context context) {
        ensureProtectionChannel(context);
        ArrayList<String> out = new ArrayList<>();
        if (!batteryUnrestricted(context)) out.add(BATTERY);
        if (!granted(context, Manifest.permission.CAMERA)) out.add(CAMERA);
        if (!notificationsReady(context)) out.add(NOTIFICATIONS);
        if (!preciseLocationReady(context)) out.add(LOCATION);
        if (!backgroundLocationReady(context)) out.add(BACKGROUND_LOCATION);
        if (!deviceAdminReady(context)) out.add(DEVICE_ADMIN);
        if (!deviceLockReady(context)) out.add(DEVICE_LOCK);
        if (!installUpdatesReady(context)) out.add(INSTALL_UPDATES);
        if (!unusedAppProtectionReady(context)) out.add(UNUSED_APP);
        if (!samsungNeverSleepingReady(context)) out.add(SAMSUNG_NEVER_SLEEPING);
        return out;
    }

    static String nextActionLabel(Context context) {
        List<String> missing = missing(context);
        if (missing.isEmpty()) return "Continue to OwnerGuard";
        String next = missing.get(0);
        if (BATTERY.equals(next)) return "Set battery to Unrestricted";
        if (CAMERA.equals(next)) return "Allow camera";
        if (NOTIFICATIONS.equals(next)) return "Allow notifications";
        if (LOCATION.equals(next)) return "Allow precise location";
        if (BACKGROUND_LOCATION.equals(next)) return "Allow location all the time";
        if (DEVICE_ADMIN.equals(next)) return "Enable Device Administrator";
        if (DEVICE_LOCK.equals(next)) return "Set phone PIN / pattern / password";
        if (INSTALL_UPDATES.equals(next)) return "Allow OwnerGuard app updates";
        if (UNUSED_APP.equals(next)) return "Disable pause-if-unused";
        if (SAMSUNG_NEVER_SLEEPING.equals(next)) {
            return prefs(context).getBoolean(KEY_SAMSUNG_OPENED, false)
                    ? "Confirm OwnerGuard is Never sleeping"
                    : "Open Samsung Never sleeping apps";
        }
        return "Fix next required setting";
    }

    static String summary(Context context) {
        StringBuilder out = new StringBuilder();
        append(out, batteryUnrestricted(context), "Battery usage: Unrestricted",
                "Required so protection and encrypted uploads are not suspended.");
        append(out, granted(context, Manifest.permission.CAMERA), "Camera permission",
                "Required for visible incident photos and video.");
        append(out, notificationsReady(context), "Notifications",
                "Required for Android foreground-service visibility.");
        append(out, preciseLocationReady(context), "Precise location",
                "Required for incident evidence metadata.");
        append(out, backgroundLocationReady(context), "Background location",
                "Required when a protected-device event occurs while OwnerGuard is not open.");
        append(out, deviceAdminReady(context), "Device Administrator",
                "Required for failed pattern, PIN, and password callbacks.");
        append(out, deviceLockReady(context), "Secure phone lock",
                "A phone PIN, pattern, or password must be configured.");
        append(out, installUpdatesReady(context), "Install OwnerGuard updates",
                "Required for verified mandatory APK updates.");
        append(out, unusedAppProtectionReady(context), "Pause app activity if unused: Off",
                "Prevents Android from automatically revoking critical permissions.");
        if (isSamsung()) {
            append(out, samsungNeverSleepingReady(context), "Samsung Never sleeping apps",
                    "Samsung does not expose a reliable verification API; this step requires your confirmation.");
        }
        return out.toString();
    }

    static void performNext(Activity activity) {
        List<String> missing = missing(activity);
        if (missing.isEmpty()) return;
        String next = missing.get(0);
        try {
            if (BATTERY.equals(next)) {
                requestBatteryUnrestricted(activity);
                return;
            }
            if (CAMERA.equals(next)) {
                activity.requestPermissions(new String[]{Manifest.permission.CAMERA}, REQUEST_RUNTIME);
                return;
            }
            if (NOTIFICATIONS.equals(next)) {
                if (Build.VERSION.SDK_INT >= 33
                        && !granted(activity, Manifest.permission.POST_NOTIFICATIONS)) {
                    activity.requestPermissions(
                            new String[]{Manifest.permission.POST_NOTIFICATIONS}, REQUEST_RUNTIME);
                } else {
                    openNotificationSettings(activity);
                }
                return;
            }
            if (LOCATION.equals(next)) {
                activity.requestPermissions(new String[]{
                        Manifest.permission.ACCESS_COARSE_LOCATION,
                        Manifest.permission.ACCESS_FINE_LOCATION
                }, REQUEST_RUNTIME);
                return;
            }
            if (BACKGROUND_LOCATION.equals(next)) {
                requestBackgroundLocation(activity);
                return;
            }
            if (DEVICE_ADMIN.equals(next)) {
                Intent intent = new Intent(DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN);
                intent.putExtra(DevicePolicyManager.EXTRA_DEVICE_ADMIN,
                        new ComponentName(activity, TheftAdminReceiver.class));
                intent.putExtra(DevicePolicyManager.EXTRA_ADD_EXPLANATION,
                        "OwnerGuard needs Device Administrator access to receive failed pattern, PIN, and password events for this personally owned phone.");
                activity.startActivityForResult(intent, REQUEST_ADMIN);
                return;
            }
            if (DEVICE_LOCK.equals(next)) {
                activity.startActivity(new Intent(Settings.ACTION_SECURITY_SETTINGS));
                Toast.makeText(activity,
                        "Configure a phone PIN, pattern, or password, then return to OwnerGuard.",
                        Toast.LENGTH_LONG).show();
                return;
            }
            if (INSTALL_UPDATES.equals(next)) {
                Intent intent = new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                        Uri.parse("package:" + activity.getPackageName()));
                activity.startActivity(intent);
                return;
            }
            if (UNUSED_APP.equals(next)) {
                openAppDetails(activity,
                        "Turn off ‘Pause app activity if unused’ / ‘Remove permissions if app isn't used’, then return.");
                return;
            }
            if (SAMSUNG_NEVER_SLEEPING.equals(next)) {
                SharedPreferences prefs = prefs(activity);
                if (prefs.getBoolean(KEY_SAMSUNG_OPENED, false)) {
                    prefs.edit().putBoolean(KEY_SAMSUNG_CONFIRMED, true).apply();
                    Toast.makeText(activity,
                            "Samsung Never sleeping confirmation saved. OwnerGuard will recheck all verifiable settings.",
                            Toast.LENGTH_LONG).show();
                } else {
                    prefs.edit().putBoolean(KEY_SAMSUNG_OPENED, true).apply();
                    openSamsungBatterySettings(activity);
                }
            }
        } catch (Throwable error) {
            openAppDetails(activity,
                    "Android could not open the specific settings page. Complete the highlighted requirement here, then return.");
        }
    }

    static void applySafeInsets(View view) {
        if (view == null) return;
        final int originalLeft = view.getPaddingLeft();
        final int originalTop = view.getPaddingTop();
        final int originalRight = view.getPaddingRight();
        final int originalBottom = view.getPaddingBottom();
        view.setOnApplyWindowInsetsListener((target, insets) -> {
            int left;
            int top;
            int right;
            int bottom;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                android.graphics.Insets bars = insets.getInsets(
                        WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
                left = bars.left;
                top = bars.top;
                right = bars.right;
                bottom = bars.bottom;
            } else {
                left = insets.getSystemWindowInsetLeft();
                top = insets.getSystemWindowInsetTop();
                right = insets.getSystemWindowInsetRight();
                bottom = insets.getSystemWindowInsetBottom();
            }
            target.setPadding(originalLeft + left, originalTop + top,
                    originalRight + right, originalBottom + bottom);
            return insets;
        });
        view.post(view::requestApplyInsets);
    }

    private static void append(StringBuilder out, boolean ready, String title, String detail) {
        if (out.length() > 0) out.append("\n\n");
        out.append(ready ? "✓  " : "!  ").append(title).append("\n").append(detail);
    }

    private static boolean granted(Context context, String permission) {
        return context.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED;
    }

    private static boolean batteryUnrestricted(Context context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return true;
        PowerManager manager = (PowerManager) context.getSystemService(Context.POWER_SERVICE);
        return manager != null && manager.isIgnoringBatteryOptimizations(context.getPackageName());
    }

    private static boolean notificationsReady(Context context) {
        if (Build.VERSION.SDK_INT >= 33
                && !granted(context, Manifest.permission.POST_NOTIFICATIONS)) return false;
        NotificationManager manager =
                (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
        if (manager == null || !manager.areNotificationsEnabled()) return false;
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationChannel channel = manager.getNotificationChannel(CHANNEL);
            return channel != null && channel.getImportance() != NotificationManager.IMPORTANCE_NONE;
        }
        return true;
    }

    private static boolean preciseLocationReady(Context context) {
        return granted(context, Manifest.permission.ACCESS_FINE_LOCATION);
    }

    private static boolean backgroundLocationReady(Context context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return true;
        return granted(context, Manifest.permission.ACCESS_BACKGROUND_LOCATION);
    }

    private static boolean deviceAdminReady(Context context) {
        DevicePolicyManager manager =
                (DevicePolicyManager) context.getSystemService(Context.DEVICE_POLICY_SERVICE);
        return manager != null && manager.isAdminActive(
                new ComponentName(context, TheftAdminReceiver.class));
    }

    private static boolean deviceLockReady(Context context) {
        KeyguardManager manager =
                (KeyguardManager) context.getSystemService(Context.KEYGUARD_SERVICE);
        return manager != null && manager.isDeviceSecure();
    }

    private static boolean installUpdatesReady(Context context) {
        return Build.VERSION.SDK_INT < Build.VERSION_CODES.O
                || context.getPackageManager().canRequestPackageInstalls();
    }

    private static boolean unusedAppProtectionReady(Context context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return true;
        try {
            return context.getPackageManager().isAutoRevokeWhitelisted();
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static boolean samsungNeverSleepingReady(Context context) {
        return !isSamsung()
                || prefs(context).getBoolean(KEY_SAMSUNG_CONFIRMED, false);
    }

    private static boolean isSamsung() {
        return Build.MANUFACTURER != null
                && Build.MANUFACTURER.toLowerCase(Locale.US).contains("samsung");
    }

    private static SharedPreferences prefs(Context context) {
        return context.getSharedPreferences(PREF, Context.MODE_PRIVATE);
    }

    private static void ensureProtectionChannel(Context context) {
        if (Build.VERSION.SDK_INT < 26) return;
        NotificationManager manager =
                (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
        if (manager == null) return;
        NotificationChannel channel = new NotificationChannel(
                CHANNEL, "OwnerGuard protection", NotificationManager.IMPORTANCE_LOW);
        channel.setDescription("Visible status for active OwnerGuard protection and capture.");
        channel.setShowBadge(false);
        channel.setSound(null, null);
        manager.createNotificationChannel(channel);
    }

    private static void requestBatteryUnrestricted(Activity activity) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return;
        try {
            Intent direct = new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                    Uri.parse("package:" + activity.getPackageName()));
            activity.startActivity(direct);
        } catch (Throwable directFailure) {
            activity.startActivity(new Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS));
        }
        Toast.makeText(activity,
                "Approve unrestricted background operation for OwnerGuard, then return.",
                Toast.LENGTH_LONG).show();
    }

    private static void requestBackgroundLocation(Activity activity) {
        if (Build.VERSION.SDK_INT == Build.VERSION_CODES.Q) {
            activity.requestPermissions(
                    new String[]{Manifest.permission.ACCESS_BACKGROUND_LOCATION}, REQUEST_RUNTIME);
            return;
        }
        openAppDetails(activity,
                "Open Permissions → Location and choose ‘Allow all the time’, then return.");
    }

    private static void openNotificationSettings(Activity activity) {
        Intent intent = new Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                .putExtra(Settings.EXTRA_APP_PACKAGE, activity.getPackageName());
        activity.startActivity(intent);
        Toast.makeText(activity,
                "Enable OwnerGuard notifications and the protection channel, then return.",
                Toast.LENGTH_LONG).show();
    }

    private static void openSamsungBatterySettings(Activity activity) {
        Toast.makeText(activity,
                "Samsung: Battery → Background usage limits → Never sleeping apps → add OwnerGuard. Then return and confirm.",
                Toast.LENGTH_LONG).show();
        try {
            Intent samsung = new Intent("com.samsung.android.sm.ACTION_OPEN_CHECKABLE_LISTACTIVITY");
            samsung.putExtra("package_name", activity.getPackageName());
            activity.startActivity(samsung);
        } catch (Throwable ignored) {
            try {
                activity.startActivity(new Intent(Settings.ACTION_BATTERY_SAVER_SETTINGS));
            } catch (Throwable second) {
                openAppDetails(activity,
                        "Set Battery to Unrestricted and add OwnerGuard to Never sleeping apps.");
            }
        }
    }

    private static void openAppDetails(Activity activity, String instruction) {
        Toast.makeText(activity, instruction, Toast.LENGTH_LONG).show();
        Intent intent = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                Uri.parse("package:" + activity.getPackageName()));
        activity.startActivity(intent);
    }
}
