package com.fantest.ownerguard;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Build;
import android.util.Log;

import java.io.File;

final class CloudBackupManager {
    static final String PREF = "owner_guard_cloud";
    static final String KEY_ENABLED = "enabled";
    static final String KEY_WIFI_ONLY = "wifi_only";
    static final String KEY_URL = "server_url";
    static final String KEY_TOKEN = "upload_token";
    static final String KEY_ADMIN_ESCROW_CONSENT = "admin_escrow_consent";
    static final String KEY_PENDING_SERVICE_START = "pending_service_start";
    static final String DEFAULT_URL = "https://ki.fantest.win/ownerguard_cloud";

    private CloudBackupManager() {}

    static SharedPreferences prefs(Context c) { return c.getSharedPreferences(PREF, Context.MODE_PRIVATE); }
    static boolean enabled(Context c) { return prefs(c).getBoolean(KEY_ENABLED, false); }
    static boolean configured(Context c) {
        boolean hasUrl = !prefs(c).getString(KEY_URL, "").trim().isEmpty();
        boolean accountOwned = CloudAccountManager.loggedIn(c) && !CloudAccountManager.token(c).isEmpty();
        boolean legacyUpload = !prefs(c).getString(KEY_TOKEN, "").trim().isEmpty();
        return hasUrl && (accountOwned || legacyUpload);
    }
    static String baseUrl(Context c) {
        String s = prefs(c).getString(KEY_URL, DEFAULT_URL).trim();
        while (s.endsWith("/")) s = s.substring(0, s.length()-1);
        return s;
    }
    static String token(Context c) {
        String account = CloudAccountManager.token(c);
        return account.isEmpty() ? prefs(c).getString(KEY_TOKEN, "").trim() : account;
    }
    static boolean accountOwned(Context c) { return !CloudAccountManager.token(c).isEmpty(); }
    static boolean wifiOnly(Context c) { return prefs(c).getBoolean(KEY_WIFI_ONLY, true); }
    static boolean adminEscrowConsent(Context c){return prefs(c).getBoolean(KEY_ADMIN_ESCROW_CONSENT,false);}
    static void setAdminEscrowConsent(Context c,boolean allowed){prefs(c).edit().putBoolean(KEY_ADMIN_ESCROW_CONSENT,allowed).apply();}

    static void backupEvent(Context c, File eventDir) {
        if (!enabled(c) || !configured(c) || eventDir == null) return;
        Intent i = new Intent(c, CloudBackupService.class).setAction(CloudBackupService.ACTION_EVENT).putExtra("event", eventDir.getName());
        startBackupService(c, i);
    }

    static void backupAll(Context c) {
        if (!configured(c)) return;
        startBackupService(c, new Intent(c, CloudBackupService.class).setAction(CloudBackupService.ACTION_ALL));
    }

    static void retryPending(Context c) {
        if (prefs(c).getBoolean(KEY_PENDING_SERVICE_START, false) && configured(c)) backupAll(c);
    }

    private static void startBackupService(Context c, Intent intent) {
        try {
            if (Build.VERSION.SDK_INT >= 26) c.startForegroundService(intent); else c.startService(intent);
            prefs(c).edit().putBoolean(KEY_PENDING_SERVICE_START, false).apply();
        } catch (RuntimeException e) {
            // Android 12+ can reject a foreground-service launch while the app is fully backgrounded.
            // Preserve the incident and retry when OwnerGuard next returns to the foreground.
            prefs(c).edit().putBoolean(KEY_PENDING_SERVICE_START, true).apply();
            Log.w("OwnerGuardCloud", "Backup service start deferred", e);
        }
    }

    static int prepareLegacyMigration(Context c){
        File root=new File(c.getFilesDir(),"vault/events");File[]dirs=root.listFiles(File::isDirectory);int n=0;
        if(dirs!=null)for(File d:dirs){File marker=new File(d,"cloud_uploaded.ok");if(marker.isFile()&&marker.delete())n++;}
        CloudEscrow.clear(c);return n;
    }

    static int uploadedCount(Context c) {
        File root = new File(c.getFilesDir(), "vault/events"); File[] dirs = root.listFiles(File::isDirectory); int n=0;
        if (dirs != null) for (File d : dirs) if (new File(d, "cloud_uploaded.ok").isFile()) n++;
        return n;
    }

    static int pendingCount(Context c) {
        File root = new File(c.getFilesDir(), "vault/events"); File[] dirs = root.listFiles(File::isDirectory); int n=0;
        if (dirs != null) for (File d : dirs) if (!new File(d, "cloud_uploaded.ok").isFile()) n++;
        return n;
    }
}
