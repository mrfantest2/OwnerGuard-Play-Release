#!/usr/bin/env python3
"""Apply OwnerGuard Android 1.0.30 forced-update gating without stopping protection capture."""
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
GRADLE = ROOT / "app" / "build.gradle"
UPDATER = ROOT / "app" / "src" / "main" / "java" / "com" / "fantest" / "ownerguard" / "AppUpdateManager.java"
APP = ROOT / "app" / "src" / "main" / "java" / "com" / "fantest" / "ownerguard" / "OwnerGuardApp.java"
WORKER = ROOT / "app" / "src" / "main" / "java" / "com" / "fantest" / "ownerguard" / "UpdateCheckWorker.java"
ACCOUNT = ROOT / "app" / "src" / "main" / "java" / "com" / "fantest" / "ownerguard" / "CloudAccountManager.java"
ESCROW = ROOT / "app" / "src" / "main" / "java" / "com" / "fantest" / "ownerguard" / "CloudEscrow.java"


def replace_once(text: str, old: str, new: str, label: str) -> str:
    if old not in text:
        raise SystemExit(f"OwnerGuard 1.0.30 patch anchor missing in {label}: {old[:100]!r}")
    return text.replace(old, new, 1)


build = GRADLE.read_text(encoding="utf-8")
for old in ("versionCode 10027", "versionCode 10028", "versionCode 10029"):
    build = build.replace(old, "versionCode 10030")
for old in ("versionName '1.0.27'", "versionName '1.0.28'", "versionName '1.0.29'"):
    build = build.replace(old, "versionName '1.0.30'")
if "versionCode 10030" not in build or "versionName '1.0.30'" not in build:
    raise SystemExit("OwnerGuard Android 1.0.30 version anchors were not found")
GRADLE.write_text(build, encoding="utf-8")

for path in (ACCOUNT, ESCROW):
    text = path.read_text(encoding="utf-8")
    for old in ("OwnerGuard-Android/1.0.26", "OwnerGuard-Android/1.0.27", "OwnerGuard-Android/1.0.28", "OwnerGuard-Android/1.0.29"):
        text = text.replace(old, "OwnerGuard-Android/1.0.30")
    path.write_text(text, encoding="utf-8")

text = UPDATER.read_text(encoding="utf-8")
for old in (
    'private static final String APP_VERSION = "1.0.24";',
    'private static final String APP_VERSION = "1.0.27";',
    'private static final String APP_VERSION = "1.0.28";',
    'private static final String APP_VERSION = "1.0.29";',
):
    text = text.replace(old, 'private static final String APP_VERSION = "1.0.30";')
if 'private static final String APP_VERSION = "1.0.30";' not in text:
    raise SystemExit("AppUpdateManager version anchor was not found")

text = replace_once(
    text,
    '    private static final String KEY_LAST_NOTIFIED_CODE = "last_notified_code";\n',
    '    private static final String KEY_LAST_NOTIFIED_CODE = "last_notified_code";\n'
    '    private static final String KEY_REQUIRED_CODE = "required_code";\n'
    '    private static final String KEY_REQUIRED_NAME = "required_name";\n'
    '    private static final String KEY_REQUIRED_CHECKED_AT = "required_checked_at";\n',
    "AppUpdateManager constants",
)
text = text.replace(
    '    private static final long AUTO_INTERVAL = 24L * 60L * 60L * 1000L;',
    '    private static final long AUTO_INTERVAL = 60L * 60L * 1000L;\n'
    '    private static final long FOREGROUND_GATE_TTL = 15L * 60L * 1000L;',
)
text = replace_once(
    text,
    '    private static volatile boolean downloading;\n',
    '    private static volatile boolean downloading;\n'
    '    private static volatile boolean gateChecking;\n'
    '    private static volatile AlertDialog gateDialog;\n',
    "AppUpdateManager gate state",
)
text = text.replace(
    'PeriodicWorkRequest request = new PeriodicWorkRequest.Builder(UpdateCheckWorker.class, 24, TimeUnit.HOURS)',
    'PeriodicWorkRequest request = new PeriodicWorkRequest.Builder(UpdateCheckWorker.class, 1, TimeUnit.HOURS)',
)
text = text.replace(
    'UNIQUE_WORK, ExistingPeriodicWorkPolicy.KEEP, request);',
    'UNIQUE_WORK, ExistingPeriodicWorkPolicy.UPDATE, request);',
)
text = text.replace('Daily signed update check scheduled', 'Hourly signed update check scheduled')
text = text.replace('if (checking || downloading) {', 'if (checking || downloading || gateChecking) {', 1)

text = replace_once(
    text,
    '                p.edit().putString(KEY_LAST_STATE, "Version " + result.versionName + " is available").apply();\n'
    '                event(activity, "manifest_update_available", result.versionName + " (" + result.versionCode + ")");\n'
    '                showAvailable(activity, result);',
    '                rememberRequired(activity, result);\n'
    '                p.edit().putString(KEY_LAST_STATE, "Version " + result.versionName + " is required").apply();\n'
    '                event(activity, "manifest_update_required", result.versionName + " (" + result.versionCode + ")");\n'
    '                showAvailable(activity, result);',
    "foreground update result",
)
text = replace_once(
    text,
    '            p.edit().putString(KEY_LAST_STATE, "Version " + info.versionName + " is available").apply();\n'
    '            if (p.getLong(KEY_LAST_NOTIFIED_CODE, 0L) != info.versionCode) {',
    '            rememberRequired(context, info);\n'
    '            p.edit().putString(KEY_LAST_STATE, "Version " + info.versionName + " is required").apply();\n'
    '            if (p.getLong(KEY_LAST_NOTIFIED_CODE, 0L) != info.versionCode) {',
    "background update result",
)
text = text.replace('event(context, "background_update_available", info.versionName + " (" + info.versionCode + ")");',
                    'event(context, "background_update_required", info.versionName + " (" + info.versionCode + ")");')

old_permission = '''                new AlertDialog.Builder(activity)
                        .setTitle("Update ready to install")
                        .setMessage("Installation permission is enabled. OwnerGuard will re-verify the pending APK and open Android’s installer.")
                        .setPositiveButton("Continue", (d, w) -> openPendingInstaller(activity))
                        .setNegativeButton("Later", null)
                        .show();'''
new_permission = '''                AlertDialog dialog = new AlertDialog.Builder(activity)
                        .setTitle("OwnerGuard update required")
                        .setMessage("Installation permission is enabled. OwnerGuard will re-verify the pending APK and open Android’s installer. Protection remains armed and photo/video evidence capture continues while the update screen is active.")
                        .setPositiveButton("Install now", (d, w) -> openPendingInstaller(activity))
                        .setNegativeButton("Exit", (d, w) -> activity.finishAndRemoveTask())
                        .create();
                dialog.setCancelable(false);
                dialog.setCanceledOnTouchOutside(false);
                dialog.show();'''
text = replace_once(text, old_permission, new_permission, "permission continuation")

gate_methods = r'''
    static void enforceLatest(Activity activity) {
        if (activity == null || activity.isFinishing() || activity.isDestroyed()) return;
        SharedPreferences p = prefs(activity);
        long current = currentVersionCode(activity);
        long pendingCode = p.getLong(KEY_PENDING_CODE, 0L);
        File pending = pendingFile(activity);
        if (pendingCode > current && pending != null && pending.isFile()) {
            p.edit().putLong(KEY_REQUIRED_CODE, pendingCode)
                    .putString(KEY_REQUIRED_NAME, p.getString(KEY_PENDING_NAME, "new version")).apply();
            showRequiredGate(activity, null, true);
            return;
        }
        long required = p.getLong(KEY_REQUIRED_CODE, 0L);
        if (required > current) {
            showRequiredGate(activity, null, false);
            if (!gateChecking && !downloading) startGateCheck(activity, false);
            return;
        }
        long checkedAt = p.getLong(KEY_REQUIRED_CHECKED_AT, 0L);
        if (System.currentTimeMillis() - checkedAt <= FOREGROUND_GATE_TTL) {
            dismissGate();
            return;
        }
        startGateCheck(activity, true);
    }

    private static void startGateCheck(Activity activity, boolean blockWhileChecking) {
        if (activity == null || activity.isFinishing() || activity.isDestroyed()) return;
        if (gateChecking || downloading) {
            if (blockWhileChecking && !downloading) showCheckingGate(activity);
            return;
        }
        gateChecking = true;
        if (blockWhileChecking) showCheckingGate(activity);
        SharedPreferences p = prefs(activity);
        EXECUTOR.execute(() -> {
            UpdateInfo info = null;
            String error = null;
            try {
                info = fetchManifest(activity);
            } catch (Exception e) {
                error = safe(e.getMessage());
            }
            final UpdateInfo result = info;
            final String failure = error;
            MAIN.post(() -> {
                gateChecking = false;
                if (activity.isFinishing() || activity.isDestroyed()) return;
                if (failure != null) {
                    p.edit().putString(KEY_LAST_ERROR, failure).putString(KEY_LAST_STATE, "Required update check failed").apply();
                    event(activity, "required_update_check_failed", failure);
                    showGateFailure(activity, failure);
                    return;
                }
                p.edit().putLong(KEY_REQUIRED_CHECKED_AT, System.currentTimeMillis())
                        .putLong(KEY_LAST_SUCCESS, System.currentTimeMillis())
                        .putString(KEY_LAST_ERROR, "").apply();
                if (result != null && result.versionCode > currentVersionCode(activity)) {
                    rememberRequired(activity, result);
                    event(activity, "required_update_detected", result.versionName + " (" + result.versionCode + ")");
                    showRequiredGate(activity, result, false);
                } else {
                    clearRequiredIfInstalled(activity);
                    p.edit().putString(KEY_LAST_STATE, "OwnerGuard is current").apply();
                    dismissGate();
                }
            });
        });
    }

    private static void showCheckingGate(Activity activity) {
        dismissGate();
        AlertDialog dialog = new AlertDialog.Builder(activity)
                .setTitle("Checking required OwnerGuard update")
                .setMessage("OwnerGuard is verifying the signed release manifest before opening the app. Protection remains armed; photo/video evidence capture and encrypted uploads continue in the background.")
                .create();
        dialog.setCancelable(false);
        dialog.setCanceledOnTouchOutside(false);
        gateDialog = dialog;
        dialog.show();
    }

    private static void showRequiredGate(Activity activity, UpdateInfo info, boolean verifiedPending) {
        dismissGate();
        SharedPreferences p = prefs(activity);
        long current = currentVersionCode(activity);
        long requiredCode = info != null ? info.versionCode : Math.max(p.getLong(KEY_REQUIRED_CODE, 0L), p.getLong(KEY_PENDING_CODE, 0L));
        String requiredName = info != null ? info.versionName : p.getString(KEY_REQUIRED_NAME, p.getString(KEY_PENDING_NAME, "new version"));
        StringBuilder message = new StringBuilder();
        message.append("Installed: ").append(currentVersionName(activity)).append(" (").append(current).append(")\n");
        message.append("Required: ").append(requiredName == null ? "new version" : requiredName).append(" (").append(requiredCode).append(")\n\n");
        message.append("Normal OwnerGuard controls are locked until the signed update is installed. ProtectionService and CloudBackupService are not stopped: armed protection continues taking photos/videos and uploading encrypted evidence.");
        if (info != null && !info.notes.isEmpty()) message.append("\n\n").append(info.notes);
        AlertDialog.Builder builder = new AlertDialog.Builder(activity)
                .setTitle("OwnerGuard update required")
                .setMessage(message.toString())
                .setNegativeButton("Exit", (d, w) -> activity.finishAndRemoveTask());
        if (verifiedPending) {
            builder.setPositiveButton("Install verified update", (d, w) -> openPendingInstaller(activity));
        } else if (info != null) {
            builder.setPositiveButton("Download and install", (d, w) -> download(activity, info));
        } else {
            builder.setPositiveButton("Check and download", (d, w) -> {
                prefs(activity).edit().putLong(KEY_REQUIRED_CHECKED_AT, 0L).apply();
                startGateCheck(activity, true);
            });
        }
        AlertDialog dialog = builder.create();
        dialog.setCancelable(false);
        dialog.setCanceledOnTouchOutside(false);
        gateDialog = dialog;
        dialog.show();
    }

    private static void showGateFailure(Activity activity, String error) {
        dismissGate();
        AlertDialog dialog = new AlertDialog.Builder(activity)
                .setTitle("Update verification required")
                .setMessage("OwnerGuard could not verify the required signed release: " + safe(error) + "\n\nNormal app controls remain locked. Armed protection and encrypted photo/video capture continue in the background.")
                .setPositiveButton("Retry", (d, w) -> {
                    prefs(activity).edit().putLong(KEY_REQUIRED_CHECKED_AT, 0L).apply();
                    startGateCheck(activity, true);
                })
                .setNegativeButton("Exit", (d, w) -> activity.finishAndRemoveTask())
                .create();
        dialog.setCancelable(false);
        dialog.setCanceledOnTouchOutside(false);
        gateDialog = dialog;
        dialog.show();
    }

    private static void rememberRequired(Context context, UpdateInfo info) {
        prefs(context).edit().putLong(KEY_REQUIRED_CODE, info.versionCode)
                .putString(KEY_REQUIRED_NAME, info.versionName)
                .putLong(KEY_REQUIRED_CHECKED_AT, System.currentTimeMillis()).apply();
    }

    private static void clearRequiredIfInstalled(Context context) {
        SharedPreferences p = prefs(context);
        if (p.getLong(KEY_REQUIRED_CODE, 0L) <= currentVersionCode(context)) {
            p.edit().remove(KEY_REQUIRED_CODE).remove(KEY_REQUIRED_NAME).apply();
        }
    }

    private static boolean isRequired(Context context) {
        SharedPreferences p = prefs(context);
        return Math.max(p.getLong(KEY_REQUIRED_CODE, 0L), p.getLong(KEY_PENDING_CODE, 0L)) > currentVersionCode(context);
    }

    private static void dismissGate() {
        AlertDialog dialog = gateDialog;
        gateDialog = null;
        if (dialog != null) {
            try { if (dialog.isShowing()) dialog.dismiss(); } catch (Throwable ignored) {}
        }
    }
'''
text = replace_once(text, '    static String statusSummary(Context context) {\n', gate_methods + '\n    static String statusSummary(Context context) {\n', "forced update methods")
text = text.replace('Automatic checks: daily on a connected network', 'Automatic checks: hourly and whenever OwnerGuard opens')

old_available = '''        new AlertDialog.Builder(activity).setTitle("Signed update available").setMessage(message.toString())
                .setPositiveButton("Download and verify", (d,w) -> download(activity, info))
                .setNegativeButton("Later", null).show();'''
new_available = '''        rememberRequired(activity, info);
        AlertDialog dialog = new AlertDialog.Builder(activity).setTitle("OwnerGuard update required").setMessage(message.toString() + "\n\nNormal controls remain locked until installation. Armed protection and encrypted photo/video capture continue in the background.")
                .setPositiveButton("Download and install", (d,w) -> download(activity, info))
                .setNegativeButton("Exit", (d,w) -> activity.finishAndRemoveTask())
                .create();
        dialog.setCancelable(false);
        dialog.setCanceledOnTouchOutside(false);
        gateDialog = dialog;
        dialog.show();'''
text = replace_once(text, old_available, new_available, "mandatory available dialog")

old_failure = '''                if (failure != null) {
                    new AlertDialog.Builder(activity).setTitle("Update rejected").setMessage(failure).setPositiveButton("Close", null).show();
                    return;
                }
                new AlertDialog.Builder(activity).setTitle("Update verified")
                        .setMessage("The APK package, newer version, exact byte count, SHA-256, and OwnerGuard signing certificate passed verification. Android will now ask you to confirm the in-place update. Existing encrypted evidence and settings are preserved.")
                        .setPositiveButton("Install now", (d,w) -> openInstaller(activity, verified))
                        .setNegativeButton("Later", null).show();'''
new_failure = '''                if (failure != null) {
                    if (isRequired(activity)) showGateFailure(activity, failure);
                    else new AlertDialog.Builder(activity).setTitle("Update rejected").setMessage(failure).setPositiveButton("Close", null).show();
                    return;
                }
                AlertDialog dialog = new AlertDialog.Builder(activity).setTitle("Update verified")
                        .setMessage("The APK package, newer version, exact byte count, SHA-256, and OwnerGuard signing certificate passed verification. Android will now ask you to confirm the in-place update. Existing encrypted evidence and settings are preserved. Armed protection continues during installation.")
                        .setPositiveButton("Install now", (d,w) -> openInstaller(activity, verified))
                        .setNegativeButton("Exit", (d,w) -> activity.finishAndRemoveTask())
                        .create();
                dialog.setCancelable(false);
                dialog.setCanceledOnTouchOutside(false);
                gateDialog = dialog;
                dialog.show();'''
text = replace_once(text, old_failure, new_failure, "verified update dialog")

text = replace_once(
    text,
    '        if (p.getLong(KEY_PENDING_CODE, 0L) <= currentCode) clearPending(context, null);\n',
    '        if (p.getLong(KEY_PENDING_CODE, 0L) <= currentCode) clearPending(context, null);\n'
    '        clearRequiredIfInstalled(context);\n',
    "installed version reconciliation",
)
text = text.replace('OwnerGuard " + info.versionName + " is available', 'OwnerGuard " + info.versionName + " is required')
text = text.replace('Open OwnerGuard to download and verify the signed update', 'Open OwnerGuard to install the required signed update')
text = text.replace('.setContentIntent(pending).setAutoCancel(true);', '.setContentIntent(pending).setAutoCancel(false).setOngoing(true);')

required_tokens = [
    'APP_VERSION = "1.0.30"',
    'KEY_REQUIRED_CODE',
    'static void enforceLatest(Activity activity)',
    'Normal OwnerGuard controls are locked until the signed update is installed',
    'ProtectionService and CloudBackupService are not stopped',
    'ExistingPeriodicWorkPolicy.UPDATE',
]
missing = [token for token in required_tokens if token not in text]
if missing:
    raise SystemExit("Forced-update patch incomplete: " + ", ".join(missing))
UPDATER.write_text(text, encoding="utf-8")

app = APP.read_text(encoding="utf-8")
app = replace_once(
    app,
    '        AuthSession.onForeground();\n',
    '        AuthSession.onForeground();\n'
    '        MAIN.post(() -> AppUpdateManager.enforceLatest(activity));\n',
    "OwnerGuardApp foreground gate",
)
APP.write_text(app, encoding="utf-8")

worker = WORKER.read_text(encoding="utf-8")
worker = worker.replace('Daily network-constrained check', 'Hourly network-constrained check')
WORKER.write_text(worker, encoding="utf-8")

print("Applied OwnerGuard Android 1.0.30 mandatory signed-update gate; protection capture remains independent")
