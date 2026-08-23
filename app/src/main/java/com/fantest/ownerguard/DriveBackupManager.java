package com.fantest.ownerguard;

import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.net.Uri;
import android.provider.DocumentsContract;

import androidx.work.Constraints;
import androidx.work.ExistingPeriodicWorkPolicy;
import androidx.work.NetworkType;
import androidx.work.PeriodicWorkRequest;
import androidx.work.WorkManager;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

import javax.crypto.Cipher;
import javax.crypto.CipherInputStream;
import javax.crypto.CipherOutputStream;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/** Portable OwnerGuard Pro backup using a user-selected Android document provider such as Google Drive. */
final class DriveBackupManager {
    private static final String PREF = "owner_guard_drive_backup";
    private static final String KEY_TREE = "tree_uri";
    private static final String KEY_AUTO = "automatic";
    private static final String KEY_LAST_TIME = "last_time";
    private static final String KEY_LAST_NAME = "last_name";
    private static final String KEY_LAST_ERROR = "last_error";
    private static final String UNIQUE_WORK = "owner_guard_pro_drive_backup";
    private static final byte[] MAGIC = new byte[] {'O','G','B','1'};
    private static final int VERSION = 1;
    private static final int MAX_RETAINED_BACKUPS = 7;
    private static final long MAX_RESTORE_ENTRY = 128L * 1024L * 1024L;
    private static final long MAX_RESTORE_TOTAL = 1024L * 1024L * 1024L;

    static final class Result {
        final int files;
        final String name;
        Result(int files, String name) { this.files = files; this.name = name; }
    }

    private static final class Doc {
        final Uri uri;
        final String name;
        final long modified;
        Doc(Uri uri, String name, long modified) { this.uri=uri; this.name=name; this.modified=modified; }
    }

    private DriveBackupManager() {}

    static SharedPreferences prefs(Context context) {
        return context.getSharedPreferences(PREF, Context.MODE_PRIVATE);
    }

    static boolean configured(Context context) {
        String value = prefs(context).getString(KEY_TREE, "");
        return value != null && !value.trim().isEmpty();
    }

    static boolean automatic(Context context) {
        return prefs(context).getBoolean(KEY_AUTO, false);
    }

    static String destinationSummary(Context context) {
        String value = prefs(context).getString(KEY_TREE, "");
        if (value == null || value.isEmpty()) return "No Google Drive / cloud folder selected";
        return "Backup folder connected";
    }

    static String lastBackupSummary(Context context) {
        SharedPreferences p = prefs(context);
        long when = p.getLong(KEY_LAST_TIME, 0L);
        String error = p.getString(KEY_LAST_ERROR, "");
        if (error != null && !error.isEmpty()) return "Last backup error: " + error;
        if (when <= 0L) return "No Pro backup has completed yet";
        String name = p.getString(KEY_LAST_NAME, "");
        java.text.DateFormat format = android.text.format.DateFormat.getMediumDateFormat(context);
        java.text.DateFormat time = android.text.format.DateFormat.getTimeFormat(context);
        return "Last backup: " + format.format(new Date(when)) + " " + time.format(new Date(when)) + (name == null || name.isEmpty() ? "" : "\n" + name);
    }

    static void setTree(Context context, Uri treeUri) throws Exception {
        if (treeUri == null) throw new IllegalArgumentException("Backup folder was not selected");
        int flags = Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION;
        context.getContentResolver().takePersistableUriPermission(treeUri, flags);
        prefs(context).edit().putString(KEY_TREE, treeUri.toString()).putString(KEY_LAST_ERROR, "").apply();
    }

    static void setAutomatic(Context context, boolean enabled) {
        prefs(context).edit().putBoolean(KEY_AUTO, enabled).apply();
        syncSchedule(context);
    }

    static void syncSchedule(Context context) {
        WorkManager wm = WorkManager.getInstance(context.getApplicationContext());
        if (!automatic(context) || !configured(context) || !ProEntitlement.cached(context)) {
            wm.cancelUniqueWork(UNIQUE_WORK);
            return;
        }
        Constraints constraints = new Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .setRequiresBatteryNotLow(true)
                .build();
        PeriodicWorkRequest request = new PeriodicWorkRequest.Builder(DriveBackupWorker.class, 24, TimeUnit.HOURS)
                .setConstraints(constraints)
                .build();
        wm.enqueueUniquePeriodicWork(UNIQUE_WORK, ExistingPeriodicWorkPolicy.UPDATE, request);
    }

    static Result backupNow(Context context) throws Exception {
        if (!ProEntitlement.cached(context)) throw new SecurityException("OwnerGuard Pro is required for Google Drive backup");
        String treeValue = prefs(context).getString(KEY_TREE, "");
        if (treeValue == null || treeValue.isEmpty()) throw new IllegalStateException("Choose a Google Drive / cloud folder first");
        Uri tree = Uri.parse(treeValue);
        File temp = new File(context.getCacheDir(), "ownerguard_drive_" + System.nanoTime() + ".ogb");
        String filename = "OwnerGuard_Backup_" + new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(new Date()) + ".ogb";
        int files;
        try {
            files = createPortableBackup(context, temp);
            Uri parent = DocumentsContract.buildDocumentUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree));
            Uri target = DocumentsContract.createDocument(context.getContentResolver(), parent, "application/octet-stream", filename);
            if (target == null) throw new IllegalStateException("Cloud provider did not create the backup file");
            try (InputStream in = new BufferedInputStream(new FileInputStream(temp));
                 OutputStream out = new BufferedOutputStream(context.getContentResolver().openOutputStream(target, "w"))) {
                if (out == null) throw new IllegalStateException("Cloud provider did not open the backup file");
                copy(in, out);
            }
            prefs(context).edit().putLong(KEY_LAST_TIME, System.currentTimeMillis()).putString(KEY_LAST_NAME, filename).putString(KEY_LAST_ERROR, "").apply();
            pruneOldBackups(context, tree);
            return new Result(files, filename);
        } catch (Exception e) {
            prefs(context).edit().putString(KEY_LAST_ERROR, safe(e.getMessage())).apply();
            throw e;
        } finally {
            temp.delete();
        }
    }

    private static int createPortableBackup(Context context, File output) throws Exception {
        File events = new File(context.getFilesDir(), "vault/events");
        byte[] key = CloudCrypto.getOrCreateKey(context);
        byte[] iv = new byte[12];
        new SecureRandom().nextBytes(iv);
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key, "AES"), new GCMParameterSpec(128, iv));
        int[] count = new int[] {0};
        try (FileOutputStream raw = new FileOutputStream(output)) {
            raw.write(MAGIC);
            raw.write(VERSION);
            raw.write(iv.length);
            raw.write(iv);
            try (CipherOutputStream encrypted = new CipherOutputStream(raw, cipher);
                 ZipOutputStream zip = new ZipOutputStream(new BufferedOutputStream(encrypted))) {
                ZipEntry manifest = new ZipEntry("manifest.json");
                zip.putNextEntry(manifest);
                String meta = "{\"format\":\"OwnerGuard Portable Backup\",\"version\":1,\"createdAt\":" + System.currentTimeMillis() + ",\"appVersion\":\"1.0.44\"}";
                zip.write(meta.getBytes(StandardCharsets.UTF_8));
                zip.closeEntry();
                if (events.isDirectory()) addEventFiles(events, events, zip, count);
            }
        }
        return count[0];
    }

    private static void addEventFiles(File root, File current, ZipOutputStream zip, int[] count) throws Exception {
        File[] children = current.listFiles();
        if (children == null) return;
        java.util.Arrays.sort(children, Comparator.comparing(File::getName));
        for (File child : children) {
            if (child.isDirectory()) {
                addEventFiles(root, child, zip, count);
                continue;
            }
            if (!child.isFile() || !child.getName().endsWith(".ogv")) continue;
            String relative = root.toURI().relativize(child.toURI()).getPath();
            if (relative.isEmpty() || relative.contains("..")) continue;
            byte[] clear = VaultCrypto.decryptBytes(child);
            ZipEntry entry = new ZipEntry("vault/events/" + relative);
            entry.setTime(child.lastModified());
            zip.putNextEntry(entry);
            zip.write(clear);
            zip.closeEntry();
            java.util.Arrays.fill(clear, (byte)0);
            count[0]++;
        }
    }

    static Result restore(Context context, Uri backupUri) throws Exception {
        if (!ProEntitlement.cached(context)) throw new SecurityException("OwnerGuard Pro is required for backup restore");
        if (backupUri == null) throw new IllegalArgumentException("Select an OwnerGuard .ogb backup");
        File stage = new File(context.getCacheDir(), "ownerguard_restore_" + System.nanoTime());
        if (!stage.mkdirs() && !stage.isDirectory()) throw new IllegalStateException("Could not create restore staging area");
        int restored = 0;
        long total = 0L;
        try (InputStream source = new BufferedInputStream(context.getContentResolver().openInputStream(backupUri))) {
            if (source == null) throw new IllegalStateException("Could not open the selected backup");
            byte[] magic = new byte[4];
            readFully(source, magic);
            if (!java.util.Arrays.equals(magic, MAGIC)) throw new IllegalArgumentException("This is not an OwnerGuard Pro backup");
            int version = source.read();
            int ivLength = source.read();
            if (version != VERSION || ivLength < 12 || ivLength > 32) throw new IllegalArgumentException("Unsupported OwnerGuard backup format");
            byte[] iv = new byte[ivLength];
            readFully(source, iv);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(CloudCrypto.getOrCreateKey(context), "AES"), new GCMParameterSpec(128, iv));
            try (CipherInputStream decrypted = new CipherInputStream(source, cipher);
                 ZipInputStream zip = new ZipInputStream(new BufferedInputStream(decrypted))) {
                ZipEntry entry;
                while ((entry = zip.getNextEntry()) != null) {
                    String name = entry.getName();
                    if (entry.isDirectory() || "manifest.json".equals(name)) {
                        drain(zip, MAX_RESTORE_ENTRY);
                        zip.closeEntry();
                        continue;
                    }
                    if (name == null || !name.startsWith("vault/events/") || name.contains("..") || name.startsWith("/") || name.contains("\\")) {
                        throw new IllegalArgumentException("Unsafe backup entry");
                    }
                    byte[] clear = readEntry(zip, MAX_RESTORE_ENTRY);
                    total += clear.length;
                    if (total > MAX_RESTORE_TOTAL) throw new IllegalArgumentException("Backup is too large to restore safely");
                    String relative = name.substring("vault/events/".length());
                    File staged = safeChild(stage, relative);
                    VaultCrypto.encryptBytes(clear, staged);
                    java.util.Arrays.fill(clear, (byte)0);
                    restored++;
                    zip.closeEntry();
                }
            }
            File live = new File(context.getFilesDir(), "vault/events");
            mergeStage(stage, stage, live);
            return new Result(restored, backupUri.getLastPathSegment() == null ? "OwnerGuard backup" : backupUri.getLastPathSegment());
        } catch (Exception e) {
            prefs(context).edit().putString(KEY_LAST_ERROR, "Restore: " + safe(e.getMessage())).apply();
            throw e;
        } finally {
            deleteRecursively(stage);
        }
    }

    private static void mergeStage(File root, File current, File liveRoot) throws Exception {
        File[] children = current.listFiles();
        if (children == null) return;
        for (File child : children) {
            if (child.isDirectory()) { mergeStage(root, child, liveRoot); continue; }
            String relative = root.toURI().relativize(child.toURI()).getPath();
            File target = safeChild(liveRoot, relative);
            if (target.exists()) continue;
            File parent = target.getParentFile();
            if (parent != null && !parent.exists() && !parent.mkdirs() && !parent.isDirectory()) throw new IllegalStateException("Could not create restored event folder");
            try (InputStream in = new FileInputStream(child); OutputStream out = new FileOutputStream(target)) { copy(in, out); }
        }
    }

    private static File safeChild(File root, String relative) throws Exception {
        File child = new File(root, relative);
        String rootPath = root.getCanonicalPath() + File.separator;
        String childPath = child.getCanonicalPath();
        if (!childPath.startsWith(rootPath)) throw new IllegalArgumentException("Unsafe backup path");
        return child;
    }

    private static byte[] readEntry(InputStream in, long limit) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buffer = new byte[64 * 1024];
        long total = 0L;
        int n;
        while ((n = in.read(buffer)) != -1) {
            total += n;
            if (total > limit) throw new IllegalArgumentException("Backup entry is too large");
            out.write(buffer, 0, n);
        }
        return out.toByteArray();
    }

    private static void drain(InputStream in, long limit) throws Exception { readEntry(in, limit); }

    private static void pruneOldBackups(Context context, Uri tree) {
        ContentResolver resolver = context.getContentResolver();
        Cursor cursor = null;
        try {
            String parentId = DocumentsContract.getTreeDocumentId(tree);
            Uri children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, parentId);
            cursor = resolver.query(children, new String[] {
                    DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                    DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                    DocumentsContract.Document.COLUMN_LAST_MODIFIED
            }, null, null, null);
            if (cursor == null) return;
            List<Doc> backups = new ArrayList<>();
            while (cursor.moveToNext()) {
                String id = cursor.getString(0);
                String name = cursor.getString(1);
                long modified = cursor.isNull(2) ? 0L : cursor.getLong(2);
                if (name != null && name.startsWith("OwnerGuard_Backup_") && name.endsWith(".ogb")) {
                    backups.add(new Doc(DocumentsContract.buildDocumentUriUsingTree(tree, id), name, modified));
                }
            }
            Collections.sort(backups, (a,b) -> Long.compare(b.modified, a.modified));
            for (int i = MAX_RETAINED_BACKUPS; i < backups.size(); i++) {
                try { DocumentsContract.deleteDocument(resolver, backups.get(i).uri); } catch (Throwable ignored) {}
            }
        } catch (Throwable ignored) {
        } finally {
            if (cursor != null) cursor.close();
        }
    }

    private static void copy(InputStream in, OutputStream out) throws Exception {
        byte[] buffer = new byte[64 * 1024];
        int n;
        while ((n = in.read(buffer)) != -1) if (n > 0) out.write(buffer, 0, n);
        out.flush();
    }

    private static void readFully(InputStream in, byte[] target) throws Exception {
        int offset = 0;
        while (offset < target.length) {
            int n = in.read(target, offset, target.length - offset);
            if (n < 0) throw new IllegalArgumentException("Truncated OwnerGuard backup");
            offset += n;
        }
    }

    private static void deleteRecursively(File file) {
        if (file == null || !file.exists()) return;
        if (file.isDirectory()) {
            File[] children = file.listFiles();
            if (children != null) for (File child : children) deleteRecursively(child);
        }
        file.delete();
    }

    private static String safe(String value) {
        return value == null || value.trim().isEmpty() ? "Unknown error" : value.trim();
    }
}
