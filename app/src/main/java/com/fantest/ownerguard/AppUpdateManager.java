package com.fantest.ownerguard;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.ProgressDialog;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.Signature;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.text.format.DateFormat;
import android.widget.Toast;

import androidx.work.Constraints;
import androidx.work.ExistingPeriodicWorkPolicy;
import androidx.work.NetworkType;
import androidx.work.PeriodicWorkRequest;
import androidx.work.WorkManager;

import org.json.JSONObject;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/** Secure OwnerGuard self-updater. Android always keeps the final installer confirmation. */
final class AppUpdateManager {
    private static final String APP_VERSION = "1.0.24";
    private static final String PREF = "owner_guard_updates";
    private static final String KEY_LAST_CHECK = "last_check";
    private static final String KEY_LAST_SUCCESS = "last_success";
    private static final String KEY_LAST_ERROR = "last_error";
    private static final String KEY_LAST_STATE = "last_state";
    private static final String KEY_LAST_INSTALLED_CODE = "last_installed_code";
    private static final String KEY_LAST_INSTALLED_NAME = "last_installed_name";
    private static final String KEY_PENDING_PATH = "pending_path";
    private static final String KEY_PENDING_CODE = "pending_code";
    private static final String KEY_PENDING_NAME = "pending_name";
    private static final String KEY_PENDING_SHA = "pending_sha";
    private static final String KEY_PENDING_SIZE = "pending_size";
    private static final String KEY_AWAITING_PERMISSION = "awaiting_permission";
    private static final String KEY_LAST_NOTIFIED_CODE = "last_notified_code";
    private static final String UNIQUE_WORK = "owner_guard_signed_update_check";
    private static final String CHANNEL = "owner_guard_updates";
    private static final int NOTIFICATION_ID = 1240;
    private static final long AUTO_INTERVAL = 24L * 60L * 60L * 1000L;
    private static final long MAX_APK = 200L * 1024L * 1024L;
    private static final String APK_MIME = "application/vnd.android.package-archive";
    private static final ExecutorService EXECUTOR = Executors.newSingleThreadExecutor();
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static volatile boolean checking;
    private static volatile boolean downloading;

    private AppUpdateManager() {}

    static void initialize(Context context) {
        if (context == null) return;
        reconcileInstalledVersion(context.getApplicationContext());
        scheduleBackgroundChecks(context.getApplicationContext());
    }

    static void scheduleBackgroundChecks(Context context) {
        try {
            Constraints constraints = new Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.CONNECTED)
                    .build();
            PeriodicWorkRequest request = new PeriodicWorkRequest.Builder(UpdateCheckWorker.class, 24, TimeUnit.HOURS)
                    .setConstraints(constraints)
                    .build();
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                    UNIQUE_WORK, ExistingPeriodicWorkPolicy.KEEP, request);
            event(context, "background_schedule_ready", "Daily signed update check scheduled");
        } catch (Throwable e) {
            setState(context, "Automatic update scheduling unavailable", safe(e.getMessage()));
            event(context, "background_schedule_failed", safe(e.getMessage()));
        }
    }

    static void check(Activity activity, boolean userInitiated) {
        if (activity == null || activity.isFinishing()) return;
        if (checking || downloading) {
            if (userInitiated) Toast.makeText(activity, "An update operation is already running", Toast.LENGTH_SHORT).show();
            return;
        }
        SharedPreferences p = prefs(activity);
        long now = System.currentTimeMillis();
        if (!userInitiated && now - p.getLong(KEY_LAST_CHECK, 0L) < AUTO_INTERVAL) return;
        checking = true;
        p.edit().putLong(KEY_LAST_CHECK, now).putString(KEY_LAST_STATE, "Checking signed release manifest…").apply();
        event(activity, "manifest_check_started", userInitiated ? "manual" : "automatic");
        if (userInitiated) Toast.makeText(activity, "Checking for a signed OwnerGuard update…", Toast.LENGTH_SHORT).show();
        EXECUTOR.execute(() -> {
            UpdateInfo info = null;
            String error = null;
            try {
                info = fetchManifest(activity);
                p.edit().putLong(KEY_LAST_SUCCESS, System.currentTimeMillis()).putString(KEY_LAST_ERROR, "").apply();
            } catch (Exception e) {
                error = safe(e.getMessage());
                p.edit().putString(KEY_LAST_ERROR, error).putString(KEY_LAST_STATE, "Update check failed").apply();
                event(activity, "manifest_check_failed", error);
            }
            final UpdateInfo result = info;
            final String failure = error;
            MAIN.post(() -> {
                checking = false;
                if (activity.isFinishing() || activity.isDestroyed()) return;
                if (failure != null) {
                    if (userInitiated) new AlertDialog.Builder(activity).setTitle("Update check failed").setMessage(failure).setPositiveButton("Close", null).show();
                    return;
                }
                if (result == null || result.versionCode <= currentVersionCode(activity)) {
                    p.edit().putString(KEY_LAST_STATE, "OwnerGuard is current").apply();
                    event(activity, "manifest_current", currentVersionName(activity));
                    if (userInitiated) new AlertDialog.Builder(activity).setTitle("OwnerGuard is current")
                            .setMessage("Installed version: " + currentVersionName(activity) + "\n\nAutomatic signed-update checks run daily when a network connection is available.")
                            .setPositiveButton("Close", null).show();
                    return;
                }
                p.edit().putString(KEY_LAST_STATE, "Version " + result.versionName + " is available").apply();
                event(activity, "manifest_update_available", result.versionName + " (" + result.versionCode + ")");
                showAvailable(activity, result);
            });
        });
    }

    static boolean backgroundCheck(Context context) {
        try {
            SharedPreferences p = prefs(context);
            p.edit().putLong(KEY_LAST_CHECK, System.currentTimeMillis()).putString(KEY_LAST_STATE, "Background update check running…").apply();
            UpdateInfo info = fetchManifest(context);
            p.edit().putLong(KEY_LAST_SUCCESS, System.currentTimeMillis()).putString(KEY_LAST_ERROR, "").apply();
            if (info == null || info.versionCode <= currentVersionCode(context)) {
                p.edit().putString(KEY_LAST_STATE, "OwnerGuard is current").apply();
                return true;
            }
            p.edit().putString(KEY_LAST_STATE, "Version " + info.versionName + " is available").apply();
            if (p.getLong(KEY_LAST_NOTIFIED_CODE, 0L) != info.versionCode) {
                notifyAvailable(context, info);
                p.edit().putLong(KEY_LAST_NOTIFIED_CODE, info.versionCode).apply();
            }
            event(context, "background_update_available", info.versionName + " (" + info.versionCode + ")");
            return true;
        } catch (Exception e) {
            String error = safe(e.getMessage());
            prefs(context).edit().putString(KEY_LAST_ERROR, error).putString(KEY_LAST_STATE, "Background update check failed").apply();
            event(context, "background_check_failed", error);
            return false;
        }
    }

    static void onActivityResumed(Activity activity) {
        if (activity == null || activity.isFinishing()) return;
        SharedPreferences p = prefs(activity);
        if (!p.getBoolean(KEY_AWAITING_PERMISSION, false)) return;
        File pending = pendingFile(activity);
        if (pending == null || !pending.isFile()) {
            clearPending(activity, "Verified update file is no longer available");
            return;
        }
        if (Build.VERSION.SDK_INT >= 26 && !activity.getPackageManager().canRequestPackageInstalls()) return;
        p.edit().putBoolean(KEY_AWAITING_PERMISSION, false).apply();
        MAIN.postDelayed(() -> {
            if (!activity.isFinishing() && !activity.isDestroyed()) {
                new AlertDialog.Builder(activity)
                        .setTitle("Update ready to install")
                        .setMessage("Installation permission is enabled. OwnerGuard will re-verify the pending APK and open Android’s installer.")
                        .setPositiveButton("Continue", (d, w) -> openPendingInstaller(activity))
                        .setNegativeButton("Later", null)
                        .show();
            }
        }, 250L);
    }

    static String statusSummary(Context context) {
        SharedPreferences p = prefs(context);
        StringBuilder s = new StringBuilder();
        s.append("Installed: ").append(currentVersionName(context)).append(" (").append(currentVersionCode(context)).append(")");
        long last = p.getLong(KEY_LAST_CHECK, 0L);
        s.append("\nAutomatic checks: daily on a connected network");
        s.append("\nLast check: ").append(last > 0 ? DateFormat.format("yyyy-MM-dd HH:mm", last) : "Not yet");
        String state = p.getString(KEY_LAST_STATE, "Ready");
        if (state != null && !state.trim().isEmpty()) s.append("\nStatus: ").append(state);
        long pending = p.getLong(KEY_PENDING_CODE, 0L);
        if (pending > currentVersionCode(context)) s.append("\nVerified pending update: ").append(p.getString(KEY_PENDING_NAME, "new version"));
        String error = p.getString(KEY_LAST_ERROR, "");
        if (error != null && !error.trim().isEmpty()) s.append("\nLast error: ").append(error);
        return s.toString();
    }

    private static UpdateInfo fetchManifest(Context context) throws Exception {
        String base = CloudBackupManager.baseUrl(context);
        if (!base.startsWith("https://")) throw new IllegalStateException("Secure updates require an HTTPS OwnerGuard Cloud URL");
        URL manifestUrl = new URL(base + "/api/update_manifest.php");
        HttpURLConnection c = openSameHost(manifestUrl, manifestUrl.getHost(), 15_000, 30_000);
        int code = c.getResponseCode();
        String body = read(code >= 400 ? c.getErrorStream() : c.getInputStream(), 512 * 1024);
        c.disconnect();
        if (code < 200 || code >= 300) throw new IllegalStateException("Update server returned HTTP " + code);
        JSONObject j = new JSONObject(body);
        if (!j.optBoolean("available", false)) return null;
        String pkg = j.optString("packageName", "");
        if (!context.getPackageName().equals(pkg)) throw new SecurityException("Update package name does not match OwnerGuard");
        long versionCode = j.optLong("versionCode", 0L);
        String versionName = j.optString("versionName", "").trim();
        String hash = j.optString("sha256", "").toLowerCase(Locale.US);
        String apk = j.optString("apkUrl", "").trim();
        long size = j.optLong("size", 0L);
        int minSdk = j.optInt("minSdk", 28);
        String notes = j.optString("releaseNotes", "").trim();
        if (versionCode < 1 || versionName.isEmpty() || !hash.matches("[a-f0-9]{64}") || apk.isEmpty()) throw new IllegalStateException("Update manifest is incomplete");
        if (size < 10_000 || size > MAX_APK) throw new IllegalStateException("Update size is invalid");
        if (minSdk > Build.VERSION.SDK_INT) throw new IllegalStateException("This update requires a newer Android version");
        URL apkUrl = new URL(manifestUrl, apk);
        if (!"https".equalsIgnoreCase(apkUrl.getProtocol()) || !manifestUrl.getHost().equalsIgnoreCase(apkUrl.getHost())) throw new SecurityException("Update download must remain on the OwnerGuard Cloud HTTPS host");
        return new UpdateInfo(versionCode, versionName, hash, size, minSdk, notes, apkUrl);
    }

    private static void showAvailable(Activity activity, UpdateInfo info) {
        StringBuilder message = new StringBuilder();
        message.append("Version ").append(info.versionName).append(" is available.\n\n");
        if (!info.notes.isEmpty()) message.append(info.notes).append("\n\n");
        message.append("OwnerGuard will download atomically, verify the file size and SHA-256, parse the package/version, and require the exact installed signing certificate before Android opens the installer.");
        new AlertDialog.Builder(activity).setTitle("Signed update available").setMessage(message.toString())
                .setPositiveButton("Download and verify", (d,w) -> download(activity, info))
                .setNegativeButton("Later", null).show();
    }

    private static void download(Activity activity, UpdateInfo info) {
        if (downloading) return;
        downloading = true;
        ProgressDialog progress = new ProgressDialog(activity);
        progress.setTitle("Downloading signed OwnerGuard update");
        progress.setMessage("Connecting securely…");
        progress.setProgressStyle(ProgressDialog.STYLE_HORIZONTAL);
        progress.setIndeterminate(false);
        progress.setMax(100);
        progress.setProgress(0);
        progress.setCancelable(false);
        progress.show();
        prefs(activity).edit().putString(KEY_LAST_STATE, "Downloading version " + info.versionName).putString(KEY_LAST_ERROR, "").apply();
        event(activity, "download_started", info.versionName + " (" + info.size + " bytes)");

        EXECUTOR.execute(() -> {
            File ready = null;
            String error = null;
            try {
                File dir = updateDir(activity);
                cleanupStaleDownloads(dir);
                File partial = new File(dir, "OwnerGuard_" + safeVersion(info.versionName) + ".apk.download");
                ready = new File(dir, "OwnerGuard_" + safeVersion(info.versionName) + ".apk");
                if (partial.exists() && !partial.delete()) throw new IllegalStateException("Cannot clear previous partial update");
                if (ready.exists() && !ready.delete()) throw new IllegalStateException("Cannot replace previous verified update");
                downloadTo(info.apkUrl, partial, info.size, (done, total) -> MAIN.post(() -> {
                    if (!progress.isShowing()) return;
                    int percent = total > 0 ? (int)Math.min(100L, (done * 100L) / total) : 0;
                    progress.setProgress(percent);
                    progress.setMessage("Downloaded " + readableBytes(done) + " of " + readableBytes(total));
                }));
                if (!partial.renameTo(ready)) throw new IllegalStateException("Cannot finalize the downloaded update");
                String actual = sha256(ready);
                if (!actual.equalsIgnoreCase(info.sha256)) throw new SecurityException("Downloaded APK SHA-256 does not match the signed release manifest");
                verifyArchive(activity, ready, info);
                savePending(activity, ready, info);
                prefs(activity).edit().putString(KEY_LAST_STATE, "Version " + info.versionName + " downloaded and verified").apply();
                event(activity, "download_verified", info.versionName + " sha256=" + actual);
            } catch (Exception e) {
                error = safe(e.getMessage());
                if (ready != null) ready.delete();
                prefs(activity).edit().putString(KEY_LAST_ERROR, error).putString(KEY_LAST_STATE, "Update rejected").apply();
                event(activity, "download_rejected", error);
            }
            final File verified = ready;
            final String failure = error;
            MAIN.post(() -> {
                downloading = false;
                if (progress.isShowing()) progress.dismiss();
                if (activity.isFinishing() || activity.isDestroyed()) return;
                if (failure != null) {
                    new AlertDialog.Builder(activity).setTitle("Update rejected").setMessage(failure).setPositiveButton("Close", null).show();
                    return;
                }
                new AlertDialog.Builder(activity).setTitle("Update verified")
                        .setMessage("The APK package, newer version, exact byte count, SHA-256, and OwnerGuard signing certificate passed verification. Android will now ask you to confirm the in-place update. Existing encrypted evidence and settings are preserved.")
                        .setPositiveButton("Install now", (d,w) -> openInstaller(activity, verified))
                        .setNegativeButton("Later", null).show();
            });
        });
    }

    private static void downloadTo(URL initial, File outFile, long expectedSize, Progress progress) throws Exception {
        HttpURLConnection c = openSameHost(initial, initial.getHost(), 20_000, 120_000);
        int code = c.getResponseCode();
        if (code < 200 || code >= 300) throw new IllegalStateException("APK download returned HTTP " + code);
        long declared = c.getContentLengthLong();
        if (declared > MAX_APK || (expectedSize > 0 && declared > 0 && declared != expectedSize)) throw new IllegalStateException("APK size does not match the release manifest");
        long target = expectedSize > 0 ? expectedSize : declared;
        long total = 0L;
        try (InputStream in = new BufferedInputStream(c.getInputStream());
             FileOutputStream fileOut = new FileOutputStream(outFile);
             OutputStream out = new BufferedOutputStream(fileOut)) {
            byte[] buffer = new byte[64 * 1024];
            int n;
            while ((n = in.read(buffer)) != -1) {
                total += n;
                if (total > MAX_APK) throw new IllegalStateException("APK exceeds the maximum update size");
                out.write(buffer, 0, n);
                progress.changed(total, target);
            }
            out.flush();
            fileOut.getFD().sync();
        } finally {
            c.disconnect();
        }
        if (total < 10_000 || (expectedSize > 0 && total != expectedSize)) throw new IllegalStateException("APK download is incomplete");
    }

    private static HttpURLConnection openSameHost(URL initial, String allowedHost, int connectTimeout, int readTimeout) throws Exception {
        URL current = initial;
        for (int i = 0; i < 4; i++) {
            if (!"https".equalsIgnoreCase(current.getProtocol()) || !allowedHost.equalsIgnoreCase(current.getHost())) throw new SecurityException("Update redirected outside the trusted HTTPS host");
            HttpURLConnection c = (HttpURLConnection) current.openConnection();
            c.setInstanceFollowRedirects(false);
            c.setConnectTimeout(connectTimeout);
            c.setReadTimeout(readTimeout);
            c.setRequestProperty("Accept", "application/json, application/vnd.android.package-archive, */*");
            c.setRequestProperty("User-Agent", "OwnerGuard-Android/" + APP_VERSION);
            int code = c.getResponseCode();
            if (code >= 300 && code < 400) {
                String loc = c.getHeaderField("Location");
                c.disconnect();
                if (loc == null || loc.trim().isEmpty()) throw new IllegalStateException("Update redirect has no destination");
                current = new URL(current, loc);
                continue;
            }
            return c;
        }
        throw new IllegalStateException("Too many update redirects");
    }

    private static void verifyArchive(Context context, File apk, UpdateInfo info) throws Exception {
        if (apk == null || !apk.isFile() || apk.length() != info.size) throw new SecurityException("Verified APK file size is invalid");
        PackageManager pm = context.getPackageManager();
        PackageInfo archive = pm.getPackageArchiveInfo(apk.getAbsolutePath(), PackageManager.GET_SIGNING_CERTIFICATES);
        if (archive == null) throw new SecurityException("Android cannot parse the downloaded APK");
        if (!context.getPackageName().equals(archive.packageName)) throw new SecurityException("Downloaded APK belongs to a different application");
        long archiveCode = archive.getLongVersionCode();
        if (archiveCode != info.versionCode) throw new SecurityException("APK version code does not match the release manifest");
        if (archiveCode <= currentVersionCode(context)) throw new SecurityException("Downloaded APK is not newer than the installed OwnerGuard version");
        PackageInfo installed = pm.getPackageInfo(context.getPackageName(), PackageManager.GET_SIGNING_CERTIFICATES);
        Set<String> current = signerDigests(installed);
        Set<String> candidate = signerDigests(archive);
        if (current.isEmpty() || candidate.isEmpty() || !current.equals(candidate)) throw new SecurityException("APK signing certificate does not match the installed OwnerGuard application");
    }

    private static void verifyPending(Context context, File apk) throws Exception {
        SharedPreferences p = prefs(context);
        long code = p.getLong(KEY_PENDING_CODE, 0L);
        String name = p.getString(KEY_PENDING_NAME, "");
        String hash = p.getString(KEY_PENDING_SHA, "");
        long size = p.getLong(KEY_PENDING_SIZE, 0L);
        if (code <= currentVersionCode(context) || name == null || name.isEmpty() || hash == null || !hash.matches("[a-f0-9]{64}") || size < 10_000) throw new SecurityException("Pending update metadata is invalid");
        if (!apk.isFile() || apk.length() != size) throw new SecurityException("Pending update file is incomplete");
        if (!hash.equalsIgnoreCase(sha256(apk))) throw new SecurityException("Pending update SHA-256 no longer matches");
        verifyArchive(context, apk, new UpdateInfo(code, name, hash, size, 28, "", new URL("https://localhost/verified.apk")));
    }

    private static Set<String> signerDigests(PackageInfo info) throws Exception {
        Set<String> out = new HashSet<>();
        if (info == null || info.signingInfo == null) return out;
        Signature[] signatures = info.signingInfo.getApkContentsSigners();
        if (signatures == null) return out;
        for (Signature signature : signatures) out.add(hex(MessageDigest.getInstance("SHA-256").digest(signature.toByteArray())));
        return out;
    }

    private static void openPendingInstaller(Activity activity) {
        File apk = pendingFile(activity);
        if (apk == null) {
            clearPending(activity, "No verified update is pending");
            return;
        }
        try {
            verifyPending(activity, apk);
            openInstaller(activity, apk);
        } catch (Exception e) {
            clearPending(activity, safe(e.getMessage()));
            new AlertDialog.Builder(activity).setTitle("Pending update rejected").setMessage(safe(e.getMessage())).setPositiveButton("Close", null).show();
        }
    }

    private static void openInstaller(Activity activity, File apk) {
        try {
            verifyPending(activity, apk);
            if (Build.VERSION.SDK_INT >= 26 && !activity.getPackageManager().canRequestPackageInstalls()) {
                prefs(activity).edit().putBoolean(KEY_AWAITING_PERMISSION, true).putString(KEY_LAST_STATE, "Waiting for installation permission").apply();
                event(activity, "install_permission_requested", apk.getName());
                OwnerGuardApp.markInternalTransition();
                Intent settings = new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:" + activity.getPackageName()));
                activity.startActivity(settings);
                Toast.makeText(activity, "Enable ‘Allow from this source’. OwnerGuard will resume automatically when you return and unlock the app.", Toast.LENGTH_LONG).show();
                return;
            }
            Uri uri = SecureShareProvider.uriForUpdate(activity, apk);
            Intent install = new Intent(Intent.ACTION_VIEW)
                    .setDataAndType(uri, APK_MIME)
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_ACTIVITY_NEW_TASK);
            prefs(activity).edit().putBoolean(KEY_AWAITING_PERMISSION, false).putString(KEY_LAST_STATE, "Android installer opened").apply();
            event(activity, "installer_opened", apk.getName());
            OwnerGuardApp.markInternalTransition();
            activity.startActivity(install);
        } catch (Exception e) {
            String error = safe(e.getMessage());
            prefs(activity).edit().putString(KEY_LAST_ERROR, error).putString(KEY_LAST_STATE, "Could not open installer").apply();
            event(activity, "installer_failed", error);
            new AlertDialog.Builder(activity).setTitle("Could not open installer").setMessage(error).setPositiveButton("Close", null).show();
        }
    }

    private static void savePending(Context context, File apk, UpdateInfo info) {
        prefs(context).edit()
                .putString(KEY_PENDING_PATH, apk.getAbsolutePath())
                .putLong(KEY_PENDING_CODE, info.versionCode)
                .putString(KEY_PENDING_NAME, info.versionName)
                .putString(KEY_PENDING_SHA, info.sha256)
                .putLong(KEY_PENDING_SIZE, info.size)
                .putBoolean(KEY_AWAITING_PERMISSION, false)
                .apply();
    }

    private static File pendingFile(Context context) {
        String path = prefs(context).getString(KEY_PENDING_PATH, "");
        if (path == null || path.trim().isEmpty()) return null;
        try {
            File root = updateDir(context).getCanonicalFile();
            File file = new File(path).getCanonicalFile();
            if (!file.getPath().startsWith(root.getPath() + File.separator)) return null;
            return file;
        } catch (Exception e) {
            return null;
        }
    }

    private static void reconcileInstalledVersion(Context context) {
        SharedPreferences p = prefs(context);
        long currentCode = currentVersionCode(context);
        String currentName = currentVersionName(context);
        long previousCode = p.getLong(KEY_LAST_INSTALLED_CODE, 0L);
        String previousName = p.getString(KEY_LAST_INSTALLED_NAME, "");
        if (previousCode > 0 && currentCode > previousCode) {
            String message = "Updated successfully from " + (previousName == null || previousName.isEmpty() ? previousCode : previousName) + " to " + currentName + ". App storage, PIN preferences, and encrypted vault location remain in place.";
            p.edit().putString(KEY_LAST_STATE, message).putString(KEY_LAST_ERROR, "").apply();
            event(context, "update_completed", message);
        }
        p.edit().putLong(KEY_LAST_INSTALLED_CODE, currentCode).putString(KEY_LAST_INSTALLED_NAME, currentName).apply();
        if (p.getLong(KEY_PENDING_CODE, 0L) <= currentCode) clearPending(context, null);
    }

    private static void clearPending(Context context, String reason) {
        File file = pendingFile(context);
        if (file != null) file.delete();
        SharedPreferences.Editor e = prefs(context).edit()
                .remove(KEY_PENDING_PATH).remove(KEY_PENDING_CODE).remove(KEY_PENDING_NAME)
                .remove(KEY_PENDING_SHA).remove(KEY_PENDING_SIZE).putBoolean(KEY_AWAITING_PERMISSION, false);
        if (reason != null && !reason.trim().isEmpty()) e.putString(KEY_LAST_ERROR, reason).putString(KEY_LAST_STATE, "Pending update cleared");
        e.apply();
        if (reason != null) event(context, "pending_update_cleared", reason);
    }

    private static void notifyAvailable(Context context, UpdateInfo info) {
        NotificationManager nm = (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm == null) return;
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationChannel channel = new NotificationChannel(CHANNEL, "OwnerGuard signed updates", NotificationManager.IMPORTANCE_DEFAULT);
            channel.setDescription("Notifies when a newer signed OwnerGuard release is available");
            nm.createNotificationChannel(channel);
        }
        Intent open = new Intent(context, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        PendingIntent pending = PendingIntent.getActivity(context, 1240, open, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        android.app.Notification.Builder b = Build.VERSION.SDK_INT >= 26
                ? new android.app.Notification.Builder(context, CHANNEL)
                : new android.app.Notification.Builder(context);
        b.setSmallIcon(R.drawable.ic_shield)
                .setContentTitle("OwnerGuard " + info.versionName + " is available")
                .setContentText("Open OwnerGuard to download and verify the signed update")
                .setStyle(new android.app.Notification.BigTextStyle().bigText(info.notes.isEmpty() ? "A newer signed OwnerGuard release is ready." : info.notes))
                .setContentIntent(pending).setAutoCancel(true);
        nm.notify(NOTIFICATION_ID, b.build());
    }

    private static SharedPreferences prefs(Context context) { return context.getSharedPreferences(PREF, Context.MODE_PRIVATE); }
    private static File updateDir(Context context) throws Exception {
        File dir = new File(context.getCacheDir(), "updates");
        if (!dir.mkdirs() && !dir.isDirectory()) throw new IllegalStateException("Cannot create update cache");
        return dir;
    }
    private static void cleanupStaleDownloads(File dir) {
        File[] files = dir.listFiles();
        if (files == null) return;
        long cutoff = System.currentTimeMillis() - 7L * 24L * 60L * 60L * 1000L;
        for (File file : files) if (file.getName().endsWith(".download") || file.lastModified() < cutoff) file.delete();
    }
    private static void setState(Context context, String state, String error) {
        prefs(context).edit().putString(KEY_LAST_STATE, state).putString(KEY_LAST_ERROR, error == null ? "" : error).apply();
    }
    private static void event(Context context, String name, String detail) {
        try {
            File file = new File(context.getFilesDir(), "update_events.log");
            String safeDetail = detail == null ? "" : detail.replace('\n', ' ').replace('\r', ' ');
            if (safeDetail.length() > 500) safeDetail = safeDetail.substring(0, 500);
            String line = System.currentTimeMillis() + "\t" + name + "\t" + safeDetail + "\n";
            try (FileOutputStream out = new FileOutputStream(file, true)) { out.write(line.getBytes(StandardCharsets.UTF_8)); }
            if (file.length() > 256 * 1024) {
                byte[] all;
                try (FileInputStream in = new FileInputStream(file); ByteArrayOutputStream bytes = new ByteArrayOutputStream()) {
                    byte[] b = new byte[8192]; int n; while ((n = in.read(b)) != -1) bytes.write(b, 0, n); all = bytes.toByteArray();
                }
                int keep = Math.min(all.length, 128 * 1024);
                try (FileOutputStream out = new FileOutputStream(file, false)) { out.write(all, all.length - keep, keep); }
            }
        } catch (Exception ignored) {}
    }
    private static long currentVersionCode(Context context) { try { return context.getPackageManager().getPackageInfo(context.getPackageName(),0).getLongVersionCode(); } catch(Exception e){ return 0L; } }
    private static String currentVersionName(Context context) { try { String n=context.getPackageManager().getPackageInfo(context.getPackageName(),0).versionName; return n==null?"unknown":n; } catch(Exception e){ return "unknown"; } }
    private static String sha256(File file) throws Exception { MessageDigest md=MessageDigest.getInstance("SHA-256");try(InputStream in=new BufferedInputStream(new FileInputStream(file))){byte[]b=new byte[65536];int n;while((n=in.read(b))!=-1)md.update(b,0,n);}return hex(md.digest()); }
    private static String hex(byte[] bytes){StringBuilder s=new StringBuilder(bytes.length*2);for(byte b:bytes)s.append(String.format(Locale.US,"%02x",b&0xff));return s.toString();}
    private static String read(InputStream in,int max) throws Exception { if(in==null)return"";try(InputStream x=in;ByteArrayOutputStream out=new ByteArrayOutputStream()){byte[]b=new byte[4096];int n,total=0;while((n=x.read(b))!=-1){total+=n;if(total>max)throw new IllegalStateException("Server response is too large");out.write(b,0,n);}return out.toString(StandardCharsets.UTF_8.name());} }
    private static String safeVersion(String s){String v=s==null?"update":s.replaceAll("[^0-9A-Za-z._-]","");return v.isEmpty()?"update":v;}
    private static String readableBytes(long bytes){if(bytes<=0)return"unknown size";if(bytes<1024)return bytes+" B";if(bytes<1024*1024)return String.format(Locale.US,"%.1f KB",bytes/1024.0);return String.format(Locale.US,"%.2f MB",bytes/(1024.0*1024.0));}
    private static String safe(String s){return s==null||s.trim().isEmpty()?"Unknown update error":s.trim();}

    interface Progress { void changed(long done, long total); }

    static final class UpdateInfo {
        final long versionCode,size;
        final int minSdk;
        final String versionName,sha256,notes;
        final URL apkUrl;
        UpdateInfo(long code,String name,String hash,long bytes,int sdk,String releaseNotes,URL url){versionCode=code;versionName=name;sha256=hash;size=bytes;minSdk=sdk;notes=releaseNotes;apkUrl=url;}
    }
}
