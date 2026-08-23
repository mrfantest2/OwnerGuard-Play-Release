#!/usr/bin/env python3
"""Enable default photo/video evidence for failed and successful device unlock events."""
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
JAVA = ROOT / "app" / "src" / "main" / "java" / "com" / "fantest" / "ownerguard"
MAIN = JAVA / "MainActivity.java"
ADMIN = JAVA / "TheftAdminReceiver.java"
SERVICE = JAVA / "ProtectionService.java"
BOOT = JAVA / "BootReceiver.java"
UNLOCK = JAVA / "UnlockEventReceiver.java"
MANIFEST = ROOT / "app" / "src" / "main" / "AndroidManifest.xml"


def replace_once(text: str, old: str, new: str, label: str) -> str:
    if old not in text:
        raise SystemExit(f"Credential evidence anchor missing in {label}: {old[:180]!r}")
    return text.replace(old, new, 1)


def replace_method(text: str, signature: str, replacement: str) -> str:
    start = text.find(signature)
    if start < 0:
        raise SystemExit(f"Credential evidence method anchor missing: {signature}")
    brace = text.find("{", start)
    if brace < 0:
        raise SystemExit(f"Credential evidence method brace missing: {signature}")
    depth = 0
    in_string = False
    escaped = False
    quote = ""
    i = brace
    while i < len(text):
        ch = text[i]
        if in_string:
            if escaped:
                escaped = False
            elif ch == "\\":
                escaped = True
            elif ch == quote:
                in_string = False
        else:
            if ch in ('"', "'"):
                in_string = True
                quote = ch
            elif ch == "{":
                depth += 1
            elif ch == "}":
                depth -= 1
                if depth == 0:
                    return text[:start] + replacement.rstrip() + text[i + 1:]
        i += 1
    raise SystemExit(f"Credential evidence method did not terminate: {signature}")


def insert_after_method_open(text: str, signature: str, statement: str) -> str:
    start = text.find(signature)
    if start < 0:
        raise SystemExit(f"Credential evidence insertion anchor missing: {signature}")
    brace = text.find("{", start)
    if brace < 0:
        raise SystemExit(f"Credential evidence insertion brace missing: {signature}")
    return text[:brace + 1] + "\n" + statement.rstrip() + text[brace + 1:]


# Device Administrator is the strongest credential-level source. Trigger both
# failed and successful unlock evidence directly so capture does not depend only
# on a receiver registered inside an already-running service.
admin = ADMIN.read_text(encoding="utf-8")
admin = replace_method(admin, "    @Override\n    public void onPasswordFailed(Context context, Intent intent)", r'''    @Override
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
        boolean captureFailed = settings.getBoolean("instant_failed_capture", true);
        if (!armed || !captureFailed || !cameraGranted(context)) return;

        startEvidenceService(context,
                new Intent(context, ProtectionService.class)
                        .setAction(ProtectionService.ACTION_FAILED_CREDENTIAL)
                        .putExtra(ProtectionService.EXTRA_FAILED_COUNT, count)
                        .putExtra(ProtectionService.EXTRA_FAILED_AT, failedAt),
                "failed_capture_start_error");
    }
''')
admin = replace_method(admin, "    @Override\n    public void onPasswordSucceeded(Context context, Intent intent)", r'''    @Override
    public void onPasswordSucceeded(Context context, Intent intent) {
        SharedPreferences runtime = context.getSharedPreferences(PREF, Context.MODE_PRIVATE);
        int failures = runtime.getInt("failed_credentials", 0);
        long succeededAt = System.currentTimeMillis();
        runtime.edit()
                .putBoolean("success_after_failure", failures > 0)
                .putLong("credential_success_at", succeededAt)
                .putInt("successful_unlocks", runtime.getInt("successful_unlocks", 0) + 1)
                .apply();

        SharedPreferences settings = context.getSharedPreferences("owner_guard_settings", Context.MODE_PRIVATE);
        boolean armed = settings.getBoolean("armed", false);
        boolean captureSuccess = settings.getBoolean("capture_successful_unlock", true);
        if (!armed || !captureSuccess || !cameraGranted(context)) return;

        startEvidenceService(context,
                new Intent(context, ProtectionService.class)
                        .setAction(ProtectionService.ACTION_SUCCESSFUL_CREDENTIAL)
                        .putExtra(ProtectionService.EXTRA_SUCCESS_AT, succeededAt),
                "success_capture_start_error");
    }
''')
admin = replace_once(
    admin,
    "\n}\n",
    r'''

    private static boolean cameraGranted(Context context) {
        return context.checkSelfPermission(android.Manifest.permission.CAMERA)
                == PackageManager.PERMISSION_GRANTED;
    }

    private static void startEvidenceService(Context context, Intent capture, String errorKey) {
        SharedPreferences runtime = context.getSharedPreferences(PREF, Context.MODE_PRIVATE);
        try {
            if (Build.VERSION.SDK_INT >= 26) context.startForegroundService(capture);
            else context.startService(capture);
            runtime.edit().remove(errorKey).apply();
        } catch (Throwable error) {
            runtime.edit().putString(errorKey,
                    error.getClass().getSimpleName() + ":" + String.valueOf(error.getMessage())).apply();
        }
    }
}
''',
    "Device Administrator helpers",
)
ADMIN.write_text(admin, encoding="utf-8")


# A manifest USER_PRESENT receiver supplies a vendor/biometric fallback when the
# long-running service was reclaimed. The service deduplicates this signal against
# DeviceAdminReceiver and its own dynamic USER_PRESENT listener.
UNLOCK.write_text(r'''package com.fantest.ownerguard;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.os.Build;

/** Best-effort successful-unlock fallback for biometric and vendor unlock paths. */
public final class UnlockEventReceiver extends BroadcastReceiver {
    @Override public void onReceive(Context context, Intent intent) {
        if (intent == null || !Intent.ACTION_USER_PRESENT.equals(intent.getAction())) return;
        SharedPreferences settings = context.getSharedPreferences("owner_guard_settings", Context.MODE_PRIVATE);
        if (!settings.getBoolean("armed", false)
                || !settings.getBoolean("capture_successful_unlock", true)
                || context.checkSelfPermission(android.Manifest.permission.CAMERA)
                != PackageManager.PERMISSION_GRANTED) {
            return;
        }

        long succeededAt = System.currentTimeMillis();
        Intent capture = new Intent(context, ProtectionService.class)
                .setAction(ProtectionService.ACTION_SUCCESSFUL_CREDENTIAL)
                .putExtra(ProtectionService.EXTRA_SUCCESS_AT, succeededAt);
        try {
            if (Build.VERSION.SDK_INT >= 26) context.startForegroundService(capture);
            else context.startService(capture);
            context.getSharedPreferences(TheftAdminReceiver.PREF, Context.MODE_PRIVATE)
                    .edit().remove("user_present_capture_start_error").apply();
        } catch (Throwable error) {
            context.getSharedPreferences(TheftAdminReceiver.PREF, Context.MODE_PRIVATE)
                    .edit().putString("user_present_capture_start_error",
                            error.getClass().getSimpleName() + ":" + String.valueOf(error.getMessage()))
                    .apply();
        }
    }
}
''', encoding="utf-8")

manifest = MANIFEST.read_text(encoding="utf-8")
manifest = replace_once(
    manifest,
    '        <receiver android:name=".BootReceiver" android:exported="false">\n',
    '        <receiver android:name=".UnlockEventReceiver" android:exported="false">\n'
    '            <intent-filter>\n'
    '                <action android:name="android.intent.action.USER_PRESENT" />\n'
    '            </intent-filter>\n'
    '        </receiver>\n\n'
    '        <receiver android:name=".BootReceiver" android:exported="false">\n',
    "manifest successful unlock receiver",
)
MANIFEST.write_text(manifest, encoding="utf-8")


# ProtectionService accepts a dedicated successful-credential action and merges
# all success sources into one incident using a process-persistent debounce.
service = SERVICE.read_text(encoding="utf-8")
service = replace_once(
    service,
    '    public static final String ACTION_FAILED_CREDENTIAL = "com.fantest.ownerguard.FAILED_CREDENTIAL_CAPTURE";\n',
    '    public static final String ACTION_FAILED_CREDENTIAL = "com.fantest.ownerguard.FAILED_CREDENTIAL_CAPTURE";\n'
    '    public static final String ACTION_SUCCESSFUL_CREDENTIAL = "com.fantest.ownerguard.SUCCESSFUL_CREDENTIAL_CAPTURE";\n',
    "successful credential action",
)
service = replace_once(
    service,
    '    public static final String EXTRA_FAILED_AT = "failed_at";\n',
    '    public static final String EXTRA_FAILED_AT = "failed_at";\n'
    '    public static final String EXTRA_SUCCESS_AT = "success_at";\n',
    "successful credential extra",
)
service = replace_once(
    service,
    '    private static final long DUPLICATE_FAILED_CALLBACK_MS = 1_500L;\n',
    '    private static final long DUPLICATE_FAILED_CALLBACK_MS = 1_500L;\n'
    '    private static final long DUPLICATE_SUCCESS_CALLBACK_MS = 4_000L;\n',
    "successful callback debounce",
)
service = replace_once(
    service,
    '''            if (Intent.ACTION_USER_PRESENT.equals(i.getAction()) && isArmed()) {
                main.postDelayed(() -> requestCapture(false, false), 800L);
            }
''',
    '''            if (Intent.ACTION_USER_PRESENT.equals(i.getAction()) && isArmed()
                    && getSharedPreferences("owner_guard_settings", MODE_PRIVATE)
                    .getBoolean("capture_successful_unlock", true)) {
                long signaledAt = System.currentTimeMillis();
                main.postDelayed(() -> requestSuccessfulUnlockCapture(signaledAt), 800L);
            }
''',
    "dynamic successful unlock receiver",
)
service = replace_once(
    service,
    '''        if (ACTION_FAILED_CREDENTIAL.equals(action)) {
            if (isArmed() && getSharedPreferences("owner_guard_settings", MODE_PRIVATE).getBoolean("instant_failed_capture", true)) {
                requestImmediateFailedCapture();
            }
            return START_STICKY;
        }
''',
    '''        if (ACTION_FAILED_CREDENTIAL.equals(action)) {
            if (isArmed() && getSharedPreferences("owner_guard_settings", MODE_PRIVATE).getBoolean("instant_failed_capture", true)) {
                requestImmediateFailedCapture();
            }
            return START_STICKY;
        }
        if (ACTION_SUCCESSFUL_CREDENTIAL.equals(action)) {
            if (isArmed() && getSharedPreferences("owner_guard_settings", MODE_PRIVATE)
                    .getBoolean("capture_successful_unlock", true)) {
                long signaledAt = intent == null
                        ? System.currentTimeMillis()
                        : intent.getLongExtra(EXTRA_SUCCESS_AT, System.currentTimeMillis());
                requestSuccessfulUnlockCapture(signaledAt);
            }
            return START_STICKY;
        }
''',
    "successful credential service dispatch",
)
service = replace_once(
    service,
    '    private void requestCapture(boolean test, boolean immediateFailed) {\n',
    '''    private void requestSuccessfulUnlockCapture(long signaledAt) {
        if (!isArmed()) return;
        SharedPreferences settings = getSharedPreferences("owner_guard_settings", MODE_PRIVATE);
        if (!settings.getBoolean("capture_successful_unlock", true)) return;

        long now = System.currentTimeMillis();
        SharedPreferences runtime = getSharedPreferences(TheftAdminReceiver.PREF, MODE_PRIVATE);
        long previous = runtime.getLong("last_success_capture_request_at", 0L);
        if (previous > 0L && now - previous < DUPLICATE_SUCCESS_CALLBACK_MS) return;
        runtime.edit()
                .putLong("last_success_capture_request_at", now)
                .putLong("last_successful_unlock_signal_at", signaledAt > 0L ? signaledAt : now)
                .apply();
        requestCapture(false, false);
    }

    private void requestCapture(boolean test, boolean immediateFailed) {
''',
    "successful unlock request method",
)
service = service.replace(
    '"Capturing front-camera photos and 5-second video…"',
    '"Capturing three front-camera photos and a 5-second video…"',
)
for token in (
    "ACTION_SUCCESSFUL_CREDENTIAL",
    "EXTRA_SUCCESS_AT",
    "DUPLICATE_SUCCESS_CALLBACK_MS",
    "requestSuccessfulUnlockCapture(signaledAt)",
    'getBoolean("capture_successful_unlock", true)',
    "last_success_capture_request_at",
):
    if token not in service:
        raise SystemExit("ProtectionService successful unlock output incomplete: " + token)
SERVICE.write_text(service, encoding="utf-8")


# First completed setup enables both event types and arms protection once. A later
# explicit Disarm remains respected because the initialization marker is retained.
main = MAIN.read_text(encoding="utf-8")
main = insert_after_method_open(
    main,
    "    private void buildDashboard()",
    "        ensureDefaultCapturePolicy();",
)
main = replace_once(
    main,
    '        CheckBox instant=check("Capture immediately after failed pattern / PIN / password","instant_failed_capture",true);page.addView(instant,topMargin(14));\n',
    '        page.addView(labelCard("Credential evidence is enabled by default. Each accepted event records three encrypted front-camera photos followed by an encrypted five-second video. The visible protection notification remains required by Android."),topMargin(14));\n'
    '        CheckBox instant=check("Capture photos and video after a failed unlock attempt","instant_failed_capture",true);page.addView(instant,topMargin(10));\n'
    '        CheckBox successCapture=check("Capture photos and video after a successful unlock","capture_successful_unlock",true);page.addView(successCapture,topMargin(8));\n',
    "Protection capture controls",
)
policy_method = r'''    private void ensureDefaultCapturePolicy(){
        SharedPreferences settings=getSharedPreferences("owner_guard_settings",MODE_PRIVATE);
        SharedPreferences.Editor edit=settings.edit();
        boolean changed=false;
        if(!settings.contains("instant_failed_capture")){
            edit.putBoolean("instant_failed_capture",true);
            changed=true;
        }
        if(!settings.contains("capture_successful_unlock")){
            edit.putBoolean("capture_successful_unlock",true);
            changed=true;
        }
        if(!settings.getBoolean("credential_capture_defaults_initialized",false)){
            edit.putBoolean("credential_capture_defaults_initialized",true)
                    .putBoolean("instant_failed_capture",true)
                    .putBoolean("capture_successful_unlock",true)
                    .putBoolean("armed",true);
            changed=true;
        }
        if(changed)edit.apply();

        if(OwnerGuardRequirements.allReady(this)
                && getSharedPreferences("owner_guard_settings",MODE_PRIVATE).getBoolean("armed",false)){
            try{
                Intent armIntent=new Intent(this,ProtectionService.class)
                        .setAction(ProtectionService.ACTION_ARM);
                if(Build.VERSION.SDK_INT>=26)startForegroundService(armIntent);
                else startService(armIntent);
            }catch(Throwable error){
                getSharedPreferences(TheftAdminReceiver.PREF,MODE_PRIVATE).edit()
                        .putString("default_arm_start_error",
                                error.getClass().getSimpleName()+":"+String.valueOf(error.getMessage()))
                        .apply();
            }
        }
    }

'''
main = replace_once(
    main,
    "    private void showProtectionTab(){\n",
    policy_method + "    private void showProtectionTab(){\n",
    "default capture policy method",
)
for token in (
    "ensureDefaultCapturePolicy();",
    "credential_capture_defaults_initialized",
    "Capture photos and video after a failed unlock attempt",
    "Capture photos and video after a successful unlock",
    '"capture_successful_unlock",true',
    '.putBoolean("armed",true)',
):
    if token not in main:
        raise SystemExit("MainActivity credential evidence output incomplete: " + token)
MAIN.write_text(main, encoding="utf-8")


# Reboot messaging now reflects the persistent armed preference. Credential-event
# receivers can restart the evidence service when the next monitored event occurs.
boot = BOOT.read_text(encoding="utf-8")
boot = replace_method(boot, "    @Override public void onReceive(Context c, Intent intent)", r'''    @Override public void onReceive(Context c, Intent intent) {
        boolean armed = c.getSharedPreferences("owner_guard_settings", Context.MODE_PRIVATE)
                .getBoolean("armed", false);
        NotificationManager nm = (NotificationManager) c.getSystemService(Context.NOTIFICATION_SERVICE);
        String channel = "owner_guard_status";
        if (Build.VERSION.SDK_INT >= 26) {
            nm.createNotificationChannel(new NotificationChannel(
                    channel, "OwnerGuard status", NotificationManager.IMPORTANCE_DEFAULT));
        }
        PendingIntent pi = PendingIntent.getActivity(c, 1,
                new Intent(c, MainActivity.class),
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        android.app.Notification n = new android.app.Notification.Builder(c, channel)
                .setSmallIcon(com.fantest.ownerguard.R.drawable.ic_shield)
                .setContentTitle(armed
                        ? "OwnerGuard credential capture armed"
                        : "OwnerGuard protection is disarmed")
                .setContentText(armed
                        ? "Photos and a five-second video are enabled for failed and successful unlock events."
                        : "Open OwnerGuard to arm credential-event evidence capture.")
                .setContentIntent(pi).setAutoCancel(true).build();
        nm.notify(1202, n);
    }
''')
BOOT.write_text(boot, encoding="utf-8")

combined = (
    ADMIN.read_text(encoding="utf-8")
    + SERVICE.read_text(encoding="utf-8")
    + MAIN.read_text(encoding="utf-8")
    + MANIFEST.read_text(encoding="utf-8")
    + UNLOCK.read_text(encoding="utf-8")
    + BOOT.read_text(encoding="utf-8")
)
for token in (
    "onPasswordFailed",
    "onPasswordSucceeded",
    "SUCCESSFUL_CREDENTIAL_CAPTURE",
    "FAILED_CREDENTIAL_CAPTURE",
    "UnlockEventReceiver",
    "android.intent.action.USER_PRESENT",
    "three encrypted front-camera photos",
    "five-second video",
    "credential_capture_defaults_initialized",
):
    if token not in combined:
        raise SystemExit("Credential-event evidence patch incomplete: " + token)

print("Enabled default encrypted photos and video for failed and successful unlock events")
