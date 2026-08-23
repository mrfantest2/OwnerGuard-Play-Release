package com.fantest.ownerguard;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.os.IBinder;
import android.os.Build;
import android.provider.Settings;

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
import java.util.Locale;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class CloudBackupService extends Service {
    static final String ACTION_EVENT = "com.fantest.ownerguard.CLOUD_EVENT";
    static final String ACTION_ALL = "com.fantest.ownerguard.CLOUD_ALL";
    private static final int NOTIFICATION_ID = 1701;
    private final ExecutorService executor = Executors.newSingleThreadExecutor();

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        startForeground(NOTIFICATION_ID, notification("Preparing encrypted backup…"));
        String action = intent == null ? ACTION_ALL : intent.getAction();
        String event = intent == null ? null : intent.getStringExtra("event");
        executor.execute(() -> {
            try {
                if (!CloudBackupManager.configured(this)) throw new IllegalStateException("Cloud backup is not configured");
                if (!CloudBackupManager.adminEscrowConsent(this)) throw new IllegalStateException("Open OwnerGuard and approve Administrator recovery before cloud backup can continue");
                if (!networkAllowed()) throw new IllegalStateException(CloudBackupManager.wifiOnly(this) ? "Waiting for Wi-Fi" : "No network connection");
                if (ACTION_EVENT.equals(action) && event != null && !event.contains("/") && !event.contains("..")) {
                    backupEvent(new File(new File(getFilesDir(), "vault/events"), event));
                } else {
                    File root = new File(getFilesDir(), "vault/events"); File[] events = root.listFiles(File::isDirectory);
                    if (events != null) { Arrays.sort(events); for (File d : events) if (!new File(d, "cloud_uploaded.ok").isFile()) backupEvent(d); }
                }
                update("Encrypted backup complete");
            } catch (Exception e) {
                update("Backup pending: " + safe(e.getMessage()));
            } finally {
                stopForeground(STOP_FOREGROUND_REMOVE); stopSelf(startId);
            }
        });
        return START_NOT_STICKY;
    }

    private void backupEvent(File eventDir) throws Exception {
        if (!eventDir.isDirectory()) return;
        File marker = new File(eventDir, "cloud_uploaded.ok"); if (marker.isFile()) return;
        update("Encrypting " + eventDir.getName() + "…");
        File work = new File(getCacheDir(), "cloud_upload/" + eventDir.getName());
        deleteRecursively(work); if (!work.mkdirs() && !work.isDirectory()) throw new IllegalStateException("Cannot create upload cache");
        CloudEscrow.PublicKeyInfo escrow=CloudEscrow.require(this);
        List<CloudObject> objects = new ArrayList<>();
        try {
            File metaEncrypted = new File(eventDir, "metadata.ogv");
            if (!metaEncrypted.isFile()) throw new IllegalStateException("Incident metadata is missing");
            byte[] metaClear = VaultCrypto.decryptBytes(metaEncrypted);
            JSONObject meta = new JSONObject(new String(metaClear, StandardCharsets.UTF_8));
            long createdAt = meta.optLong("startedAt", eventDir.lastModified());
            File cloudMeta = new File(work, "metadata.json.ogc");
            CloudCrypto.Envelope metaEnvelope=CloudCrypto.encryptBytes(this,metaClear,cloudMeta,escrow);
            objects.add(new CloudObject(cloudMeta,"metadata","application/json",createdAt,metaEnvelope));

            File[] photos = eventDir.listFiles((d,n) -> n.startsWith("photo_") && n.endsWith(".ogv"));
            if (photos != null) {
                Arrays.sort(photos);
                for (int i=0;i<photos.length;i++) {
                    File clear = new File(work, "photo_" + (i+1) + ".jpg");
                    VaultCrypto.decryptFile(photos[i], clear);
                    File cloud = new File(work, clear.getName() + ".ogc");
                    CloudCrypto.Envelope photoEnvelope=CloudCrypto.encryptFile(this,clear,cloud,escrow);clear.delete();
                    objects.add(new CloudObject(cloud,"photo_"+(i+1),"image/jpeg",createdAt,photoEnvelope));
                }
            }
            File video = new File(eventDir, "video_05s.ogv");
            if (video.isFile()) {
                File clear = new File(work, "video_05s.mp4");
                VaultCrypto.decryptFile(video, clear);
                File cloud = new File(work, "video_05s.mp4.ogc");
                CloudCrypto.Envelope videoEnvelope=CloudCrypto.encryptFile(this,clear,cloud,escrow);clear.delete();
                objects.add(new CloudObject(cloud,"video_05s","video/mp4",createdAt,videoEnvelope));
            }

            int done = 0;
            for (CloudObject object : objects) {
                update("Uploading " + eventDir.getName() + " (" + (++done) + "/" + objects.size() + ")…");
                upload(eventDir.getName(), object);
            }
            try (FileOutputStream out = new FileOutputStream(marker)) { out.write(("uploaded=" + System.currentTimeMillis()).getBytes(StandardCharsets.UTF_8)); }
            File error = new File(eventDir, "cloud_error.txt"); error.delete();
        } catch (Exception e) {
            try (FileOutputStream out = new FileOutputStream(new File(eventDir, "cloud_error.txt"))) { out.write(safe(e.getMessage()).getBytes(StandardCharsets.UTF_8)); }
            throw e;
        } finally {
            deleteRecursively(work);
        }
    }

    private void upload(String eventId, CloudObject object) throws Exception {
        URL url = new URL(CloudBackupManager.baseUrl(this) + "/api/upload.php");
        HttpURLConnection c = (HttpURLConnection) url.openConnection();
        c.setConnectTimeout(20_000); c.setReadTimeout(90_000); c.setDoOutput(true); c.setRequestMethod("PUT");
        c.setFixedLengthStreamingMode(object.file.length());
        c.setRequestProperty("Authorization", "Bearer " + CloudBackupManager.token(this));
        c.setRequestProperty("Content-Type", "application/octet-stream");
        c.setRequestProperty("X-OwnerGuard-Event", eventId);
        c.setRequestProperty("X-OwnerGuard-Name", object.name);
        c.setRequestProperty("X-OwnerGuard-Mime", object.mime);
        c.setRequestProperty("X-OwnerGuard-Created", String.valueOf(object.createdAt));
        c.setRequestProperty("X-OwnerGuard-Sha256", CloudCrypto.sha256(object.file));
        c.setRequestProperty("X-OwnerGuard-Device", deviceId());
        c.setRequestProperty("X-OwnerGuard-Device-Name",deviceName());
        c.setRequestProperty("X-OwnerGuard-Crypto-Version",String.valueOf(object.envelope.cryptoVersion));
        c.setRequestProperty("X-OwnerGuard-User-Envelope",object.envelope.userEnvelope);
        c.setRequestProperty("X-OwnerGuard-Admin-Envelope",object.envelope.adminEnvelope);
        c.setRequestProperty("X-OwnerGuard-Admin-Key-Id",object.envelope.adminKeyId);
        try (InputStream in = new BufferedInputStream(new FileInputStream(object.file)); OutputStream out = new BufferedOutputStream(c.getOutputStream())) {
            byte[] b = new byte[64 * 1024]; int n; while ((n = in.read(b)) != -1) out.write(b, 0, n);
        }
        int code = c.getResponseCode();
        InputStream response = code >= 400 ? c.getErrorStream() : c.getInputStream();
        String body = read(response);
        if (code < 200 || code >= 300) throw new IllegalStateException("Server " + code + ": " + body);
        c.disconnect();
    }

    private String deviceId() {
        try {
            String raw = Settings.Secure.getString(getContentResolver(), Settings.Secure.ANDROID_ID);
            if (raw == null || raw.trim().isEmpty()) raw = Build.FINGERPRINT;
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(raw.getBytes(StandardCharsets.UTF_8));
            StringBuilder out = new StringBuilder("android_");
            for (int i = 0; i < 8; i++) out.append(String.format(Locale.US, "%02x", digest[i]));
            return out.toString();
        } catch (Exception e) {
            return "android_unknown";
        }
    }

    private String deviceName() {
        String maker = Build.MANUFACTURER == null ? "" : Build.MANUFACTURER.trim();
        String model = Build.MODEL == null ? "" : Build.MODEL.trim();
        String label = (maker + " " + model).trim().replaceAll("[\\p{Cntrl}]", "");
        return label.length() > 100 ? label.substring(0, 100) : label;
    }

    private boolean networkAllowed() {
        ConnectivityManager cm = (ConnectivityManager) getSystemService(CONNECTIVITY_SERVICE);
        Network n = cm.getActiveNetwork(); if (n == null) return false;
        NetworkCapabilities caps = cm.getNetworkCapabilities(n); if (caps == null || !caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)) return false;
        return !CloudBackupManager.wifiOnly(this) || caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI);
    }

    private Notification notification(String text) {
        String channel = "owner_guard_cloud"; NotificationManager nm = (NotificationManager)getSystemService(NOTIFICATION_SERVICE);
        NotificationChannel ch = new NotificationChannel(channel, "OwnerGuard encrypted backup", NotificationManager.IMPORTANCE_LOW); ch.setSound(null,null); nm.createNotificationChannel(ch);
        PendingIntent pi = PendingIntent.getActivity(this,0,new Intent(this,MainActivity.class),PendingIntent.FLAG_IMMUTABLE|PendingIntent.FLAG_UPDATE_CURRENT);
        return new Notification.Builder(this,channel).setSmallIcon(R.drawable.ic_shield).setContentTitle("OwnerGuard cloud backup").setContentText(text).setOnlyAlertOnce(true).setOngoing(true).setContentIntent(pi).build();
    }
    private void update(String text) { ((NotificationManager)getSystemService(NOTIFICATION_SERVICE)).notify(NOTIFICATION_ID, notification(text)); }
    private static String read(InputStream in) throws Exception { if (in == null) return ""; try (InputStream x=in; ByteArrayOutputStream out=new ByteArrayOutputStream()) { byte[] b=new byte[4096]; int n; while((n=x.read(b))!=-1)out.write(b,0,n); return out.toString("UTF-8"); } }
    private static String safe(String s){ return s==null||s.trim().isEmpty()?"unknown error":s.trim(); }
    private static void deleteRecursively(File f){ if(f==null||!f.exists())return; File[]c=f.listFiles();if(c!=null)for(File x:c)deleteRecursively(x);f.delete(); }
    @Override public void onDestroy(){ executor.shutdownNow(); super.onDestroy(); }
    @Override public IBinder onBind(Intent intent){ return null; }
    private static final class CloudObject {final File file;final String name,mime;final long createdAt;final CloudCrypto.Envelope envelope;CloudObject(File f,String n,String m,long c,CloudCrypto.Envelope e){file=f;name=n;mime=m;createdAt=c;envelope=e;}}
}
