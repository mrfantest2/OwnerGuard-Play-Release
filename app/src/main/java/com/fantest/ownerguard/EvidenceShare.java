package com.fantest.ownerguard;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.Typeface;
import android.media.ExifInterface;

import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

final class EvidenceShare {
    private EvidenceShare() {}

    static File exportsDir(Context c) throws Exception {
        File d = new File(c.getCacheDir(), "exports");
        if (!d.exists() && !d.mkdirs()) throw new IllegalStateException("Cannot create secure export folder");
        File[] old = d.listFiles();
        if (old != null) for (File f : old) if (System.currentTimeMillis() - f.lastModified() > 24L * 60L * 60L * 1000L) f.delete();
        return d;
    }

    static String timestamp(JSONObject meta) {
        long t = meta.optLong("startedAt", System.currentTimeMillis());
        return new SimpleDateFormat("yyyy-MM-dd HH:mm:ss Z", Locale.getDefault()).format(new Date(t));
    }

    static String safeTimestamp(JSONObject meta) {
        long t = meta.optLong("startedAt", System.currentTimeMillis());
        return new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(new Date(t));
    }

    static String locationLine(JSONObject meta) {
        if (!meta.optBoolean("locationAvailable", false)) return "Location: unavailable";
        double lat = meta.optDouble("latitude");
        double lon = meta.optDouble("longitude");
        double acc = meta.optDouble("locationAccuracyMeters", -1);
        return String.format(Locale.US, "Location: %.6f, %.6f%s", lat, lon, acc > 0 ? " (±" + Math.round(acc) + " m)" : "");
    }

    static String mapsLink(JSONObject meta) {
        if (!meta.optBoolean("locationAvailable", false)) return "";
        return String.format(Locale.US, "https://maps.google.com/?q=%.6f,%.6f", meta.optDouble("latitude"), meta.optDouble("longitude"));
    }

    static String shareText(JSONObject meta) {
        StringBuilder s = new StringBuilder();
        s.append("OwnerGuard evidence\nTimestamp: ").append(timestamp(meta)).append('\n').append(locationLine(meta));
        String link = mapsLink(meta); if (!link.isEmpty()) s.append("\nMap: ").append(link);
        s.append("\nReason: ").append(meta.optString("reason", "Unknown").replace('_', ' '));
        return s.toString();
    }

    static Bitmap rotateIfNeeded(Bitmap source, File file) {
        try {
            ExifInterface exif = new ExifInterface(file.getAbsolutePath());
            int orientation = exif.getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL);
            int degrees = orientation == ExifInterface.ORIENTATION_ROTATE_90 ? 90 : orientation == ExifInterface.ORIENTATION_ROTATE_180 ? 180 : orientation == ExifInterface.ORIENTATION_ROTATE_270 ? 270 : 0;
            if (degrees == 0) return source;
            Matrix m = new Matrix(); m.postRotate(degrees);
            Bitmap rotated = Bitmap.createBitmap(source, 0, 0, source.getWidth(), source.getHeight(), m, true);
            if (rotated != source) source.recycle();
            return rotated;
        } catch (Exception ignored) { return source; }
    }

    static File stampedPhoto(Context c, Bitmap source, JSONObject meta, int index) throws Exception {
        int panel = Math.max(170, source.getHeight() / 7);
        Bitmap output = Bitmap.createBitmap(source.getWidth(), source.getHeight() + panel, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(output);
        canvas.drawColor(Color.BLACK);
        canvas.drawBitmap(source, 0, 0, null);
        Paint bg = new Paint(Paint.ANTI_ALIAS_FLAG); bg.setColor(Color.rgb(10,18,32));
        canvas.drawRect(0, source.getHeight(), output.getWidth(), output.getHeight(), bg);
        Paint title = new Paint(Paint.ANTI_ALIAS_FLAG); title.setColor(Color.rgb(48,197,255)); title.setTypeface(Typeface.DEFAULT_BOLD); title.setTextSize(Math.max(28f, source.getWidth()/30f));
        Paint body = new Paint(Paint.ANTI_ALIAS_FLAG); body.setColor(Color.WHITE); body.setTextSize(Math.max(22f, source.getWidth()/38f));
        float x = Math.max(20, source.getWidth()/35f); float y = source.getHeight() + title.getTextSize() + 18;
        canvas.drawText("OwnerGuard evidence • Photo " + (index + 1), x, y, title);
        y += body.getTextSize() + 18; canvas.drawText("Timestamp: " + timestamp(meta), x, y, body);
        y += body.getTextSize() + 14; canvas.drawText(locationLine(meta), x, y, body);
        File out = new File(exportsDir(c), "OwnerGuard_" + safeTimestamp(meta) + "_photo_" + (index + 1) + ".jpg");
        try (FileOutputStream fos = new FileOutputStream(out)) { output.compress(Bitmap.CompressFormat.JPEG, 94, fos); }
        output.recycle();
        return out;
    }

    static File evidenceCard(Context c, JSONObject meta) throws Exception {
        Bitmap b = Bitmap.createBitmap(1080, 680, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(b); canvas.drawColor(Color.rgb(10,18,32));
        Paint title = new Paint(Paint.ANTI_ALIAS_FLAG); title.setColor(Color.rgb(48,197,255)); title.setTypeface(Typeface.DEFAULT_BOLD); title.setTextSize(64);
        Paint body = new Paint(Paint.ANTI_ALIAS_FLAG); body.setColor(Color.WHITE); body.setTextSize(38);
        Paint muted = new Paint(Paint.ANTI_ALIAS_FLAG); muted.setColor(Color.rgb(184,197,214)); muted.setTextSize(30);
        canvas.drawText("OwnerGuard Evidence", 70, 110, title);
        canvas.drawText("Timestamp", 70, 205, muted); canvas.drawText(timestamp(meta), 70, 255, body);
        canvas.drawText("Location", 70, 345, muted); canvas.drawText(locationLine(meta).replace("Location: ", ""), 70, 395, body);
        String link = mapsLink(meta);
        if (!link.isEmpty()) { canvas.drawText("Map", 70, 485, muted); canvas.drawText(link, 70, 530, body); }
        canvas.drawText("Reason: " + meta.optString("reason", "Unknown").replace('_',' '), 70, 620, muted);
        File out = new File(exportsDir(c), "OwnerGuard_" + safeTimestamp(meta) + "_evidence_card.jpg");
        try (FileOutputStream fos = new FileOutputStream(out)) { b.compress(Bitmap.CompressFormat.JPEG, 94, fos); }
        b.recycle(); return out;
    }
}
