package com.fantest.ownerguard;

import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.LruCache;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import org.json.JSONObject;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.text.DateFormat;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Calendar;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class VaultActivity extends SecureActivity {
    private static final String BG = "#0A1220";
    private static final String SURFACE = "#14213D";
    private static final String SURFACE_ALT = "#1C2A48";
    private static final String PRIMARY = "#30C5FF";
    private static final String TEXT = "#F8FAFC";
    private static final String MUTED = "#B8C5D6";
    private static final String DIVIDER = "#263652";

    private LinearLayout list;
    private boolean newestFirst = true;
    private int dateFilter = 0;   // 0 all, 1 today, 2 yesterday, 3 last 7 days, 4 last 30 days
    private int typeFilter = 0;   // 0 all, 1 failed credential, 2 visible test, 3 owner check, 4 other
    private int ownerFilter = 0;  // 0 all, 1 likely owner, 2 not owner, 3 unscored
    private int renderGeneration = 0;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final ExecutorService thumbnailExecutor = Executors.newFixedThreadPool(2);
    private LruCache<String, Bitmap> thumbnailCache;

    private static final class EventRow {
        final File dir;
        final long timestamp;
        final String reason;
        final String rawReason;
        final String incidentType;
        final int failures;
        final int photos;
        final boolean hasVideo;
        final double similarity;
        final String location;
        final File thumbnail;

        EventRow(File dir, long timestamp, String reason, String rawReason, String incidentType,
                 int failures, int photos, boolean hasVideo, double similarity,
                 String location, File thumbnail) {
            this.dir = dir;
            this.timestamp = timestamp;
            this.reason = reason;
            this.rawReason = rawReason;
            this.incidentType = incidentType;
            this.failures = failures;
            this.photos = photos;
            this.hasVideo = hasVideo;
            this.similarity = similarity;
            this.location = location;
            this.thumbnail = thumbnail;
        }
    }

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE);
        int maxMemoryKb = (int) (Runtime.getRuntime().maxMemory() / 1024L);
        int cacheKb = Math.min(8192, Math.max(2048, maxMemoryKb / 16));
        thumbnailCache = new LruCache<String, Bitmap>(cacheKb) {
            @Override protected int sizeOf(String key, Bitmap bitmap) {
                return Math.max(1, bitmap.getByteCount() / 1024);
            }
        };
        if (!AuthSession.isUnlocked()) {
            startActivity(new Intent(this, MainActivity.class)
                    .putExtra("open_vault_after_unlock", true)
                    .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP));
            finish();
            return;
        }
        render();
    }

    @Override protected void onResume() {
        super.onResume();
        if (!AuthSession.isUnlocked()) {
            startActivity(new Intent(this, MainActivity.class)
                    .putExtra("open_vault_after_unlock", true)
                    .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP));
            finish();
            return;
        }
        if (list != null) render();
    }

    @Override protected void onDestroy() {
        renderGeneration++;
        thumbnailExecutor.shutdownNow();
        mainHandler.removeCallbacksAndMessages(null);
        if (thumbnailCache != null) thumbnailCache.evictAll();
        super.onDestroy();
    }

    private void render() {
        final int generation = ++renderGeneration;
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setBackgroundColor(Color.parseColor(BG));

        list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        int p = dp(16);
        list.setPadding(p, p, p, p);
        scroll.addView(list);

        list.addView(title("Incident vault"));
        List<EventRow> allEvents = loadAllEvents();
        List<EventRow> events = applyFilters(allEvents);
        addFilterPanel();

        LinearLayout summary = new LinearLayout(this);
        summary.setOrientation(LinearLayout.HORIZONTAL);
        summary.setGravity(Gravity.CENTER_VERTICAL);
        String countLabel = events.size() + (events.size() == 1 ? " incident" : " incidents");
        if (events.size() != allEvents.size()) countLabel += " of " + allEvents.size();
        TextView count = subtitle(countLabel);
        summary.addView(count, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        Button sort = compactButton(newestFirst ? "Newest first" : "Oldest first", SURFACE_ALT);
        sort.setOnClickListener(v -> { newestFirst = !newestFirst; render(); });
        summary.addView(sort, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(42)));
        list.addView(summary, topMarginParams(6));

        if (events.isEmpty()) {
            TextView empty = subtitle(allEvents.isEmpty() ? "No incidents stored yet." : "No incidents match the selected filters.");
            empty.setGravity(Gravity.CENTER);
            empty.setPadding(0, dp(38), 0, dp(38));
            list.addView(empty);
        } else {
            String currentDay = null;
            int dayCount;
            for (EventRow event : events) {
                String dayKey = dayKey(event.timestamp);
                if (!dayKey.equals(currentDay)) {
                    currentDay = dayKey;
                    dayCount = countForDay(events, dayKey);
                    addDateHeader(event.timestamp, dayCount);
                }
                addEvent(event, generation);
            }
        }

        Button back = button("Back", SURFACE_ALT);
        back.setOnClickListener(v -> finish());
        list.addView(back, topMarginParams(18));
        setContentView(scroll);
    }

    private List<EventRow> loadAllEvents() {
        List<EventRow> rows = new ArrayList<>();
        File dir = new File(getFilesDir(), "vault/events");
        File[] eventDirs = dir.listFiles(File::isDirectory);
        if (eventDirs == null) return rows;
        for (File eventDir : eventDirs) rows.add(readEvent(eventDir));
        Collections.sort(rows, (a, b) -> newestFirst
                ? Long.compare(b.timestamp, a.timestamp)
                : Long.compare(a.timestamp, b.timestamp));
        return rows;
    }

    private EventRow readEvent(File event) {
        long timestamp = event.lastModified();
        String reason = "Unknown event";
        String rawReason = "";
        String incidentType = "Other";
        int failures = 0;
        int photos = 0;
        boolean hasVideo = new File(event, "video_05s.ogv").isFile();
        double similarity = -1;
        String location = "";
        File thumbnail = null;

        File[] photoFiles = event.listFiles((d, n) -> n.startsWith("photo_") && n.endsWith(".ogv"));
        if (photoFiles != null && photoFiles.length > 0) {
            Arrays.sort(photoFiles, (a, b) -> a.getName().compareToIgnoreCase(b.getName()));
            thumbnail = photoFiles[0];
            photos = photoFiles.length;
        }

        try {
            File metaFile = new File(event, "metadata.ogv");
            JSONObject meta = metaFile.isFile()
                    ? new JSONObject(new String(VaultCrypto.decryptBytes(metaFile), StandardCharsets.UTF_8))
                    : new JSONObject();
            timestamp = meta.optLong("startedAt", timestamp);
            rawReason = meta.optString("reason", "");
            reason = humanReason(rawReason.isEmpty() ? reason : rawReason);
            incidentType = incidentType(rawReason);
            failures = meta.optInt("failedCredentialAttempts", 0);
            photos = Math.max(photos, meta.optInt("photosCaptured", 0));
            similarity = meta.optDouble("ownerSimilarityPrototype", -1);
            if (meta.optBoolean("locationAvailable", false)) {
                location = String.format(Locale.US, "%.5f, %.5f",
                        meta.optDouble("latitude"), meta.optDouble("longitude"));
            }
        } catch (Exception ignored) {}
        return new EventRow(event, timestamp, reason, rawReason, incidentType,
                failures, photos, hasVideo, similarity, location, thumbnail);
    }

    private void addDateHeader(long timestamp, int count) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, dp(14), 0, dp(4));

        TextView date = new TextView(this);
        date.setText(dayLabel(timestamp));
        date.setTextSize(15);
        date.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        date.setTextColor(Color.parseColor(TEXT));
        row.addView(date, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        TextView badge = new TextView(this);
        badge.setText(String.valueOf(count));
        badge.setTextSize(12);
        badge.setTextColor(Color.parseColor(MUTED));
        badge.setGravity(Gravity.CENTER);
        badge.setBackground(shape(SURFACE_ALT, 999));
        badge.setPadding(dp(10), dp(4), dp(10), dp(4));
        row.addView(badge);
        list.addView(row);
    }

    private void addEvent(EventRow event, int generation) {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.HORIZONTAL);
        card.setGravity(Gravity.CENTER_VERTICAL);
        card.setBackground(shape(SURFACE, 16));
        card.setPadding(dp(8), dp(8), dp(8), dp(8));
        card.setClickable(true);
        card.setFocusable(true);
        card.setOnClickListener(v -> openEvent(event));
        list.addView(card, topMarginParams(7));

        FrameLayout preview = new FrameLayout(this);
        preview.setBackground(shape(SURFACE_ALT, 13));
        preview.setClipToOutline(true);
        LinearLayout.LayoutParams previewParams = new LinearLayout.LayoutParams(dp(82), dp(82));
        previewParams.setMargins(0, 0, dp(11), 0);
        card.addView(preview, previewParams);

        ImageView image = new ImageView(this);
        image.setScaleType(ImageView.ScaleType.CENTER_CROP);
        image.setContentDescription("Incident photo preview");
        image.setBackgroundColor(Color.parseColor(SURFACE_ALT));
        preview.addView(image, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        TextView placeholder = new TextView(this);
        placeholder.setText(event.thumbnail == null ? "No photo" : "Loading…");
        placeholder.setTextSize(11);
        placeholder.setTextColor(Color.parseColor(MUTED));
        placeholder.setGravity(Gravity.CENTER);
        preview.addView(placeholder, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        if (event.hasVideo) {
            TextView video = new TextView(this);
            video.setText("▶ 5s");
            video.setTextSize(10);
            video.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
            video.setTextColor(Color.WHITE);
            video.setGravity(Gravity.CENTER);
            video.setPadding(dp(6), dp(3), dp(6), dp(3));
            video.setBackground(shape("#CC0A1220", 999));
            FrameLayout.LayoutParams badgeParams = new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                    Gravity.END | Gravity.BOTTOM);
            badgeParams.setMargins(0, 0, dp(5), dp(5));
            preview.addView(video, badgeParams);
        }

        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setGravity(Gravity.CENTER_VERTICAL);
        card.addView(content, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        LinearLayout top = new LinearLayout(this);
        top.setOrientation(LinearLayout.HORIZONTAL);
        top.setGravity(Gravity.CENTER_VERTICAL);
        content.addView(top);

        TextView time = new TextView(this);
        time.setText(DateFormat.getTimeInstance(DateFormat.SHORT).format(new Date(event.timestamp)));
        time.setTextSize(15);
        time.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        time.setTextColor(Color.parseColor(PRIMARY));
        top.addView(time, new LinearLayout.LayoutParams(dp(82), ViewGroup.LayoutParams.WRAP_CONTENT));

        TextView typeBadge = badge(event.incidentType, incidentColor(event.incidentType));
        top.addView(typeBadge);

        TextView reason = new TextView(this);
        reason.setText(event.reason);
        reason.setSingleLine(true);
        reason.setEllipsize(android.text.TextUtils.TruncateAt.END);
        reason.setTextSize(13);
        reason.setTextColor(Color.parseColor(TEXT));
        LinearLayout.LayoutParams reasonParams = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        reasonParams.setMargins(dp(7), 0, 0, 0);
        top.addView(reason, reasonParams);

        TextView chevron = new TextView(this);
        chevron.setText("›");
        chevron.setTextSize(27);
        chevron.setTextColor(Color.parseColor(MUTED));
        chevron.setGravity(Gravity.CENTER);
        top.addView(chevron, new LinearLayout.LayoutParams(dp(25), dp(30)));

        StringBuilder facts = new StringBuilder();
        facts.append(event.photos).append(event.photos == 1 ? " photo" : " photos");
        facts.append("  •  ").append(event.hasVideo ? "5s video" : "no video");
        if (event.failures > 0) facts.append("  •  ").append(event.failures).append(" failed");
        if (event.similarity >= 0) {
            int pct = (int) Math.round(Math.max(0, Math.min(1, event.similarity)) * 100.0);
            facts.append("  •  owner match ").append(pct).append('%');
        }
        TextView meta = subtitle(facts.toString());
        meta.setSingleLine(true);
        meta.setEllipsize(android.text.TextUtils.TruncateAt.END);
        meta.setPadding(0, dp(3), 0, 0);
        content.addView(meta);

        LinearLayout flags = new LinearLayout(this);
        flags.setOrientation(LinearLayout.HORIZONTAL);
        flags.setGravity(Gravity.CENTER_VERTICAL);
        flags.setPadding(0, dp(5), 0, 0);
        if (event.failures > 0) flags.addView(badge(event.failures + " FAILED", "#7F1D1D"));
        flags.addView(badge(ownerLabel(event), ownerColor(event)), leftMarginParams(6));
        content.addView(flags);

        if (!event.location.isEmpty()) {
            TextView location = subtitle("Location: " + event.location);
            location.setTextSize(11);
            location.setSingleLine(true);
            location.setEllipsize(android.text.TextUtils.TruncateAt.END);
            location.setPadding(0, dp(2), 0, 0);
            content.addView(location);
        }

        if (event.thumbnail != null) loadThumbnail(event.thumbnail, image, placeholder, generation);
    }


    private void addFilterPanel() {
        TextView hint = subtitle("Filter by date, incident type, and owner-confidence classification.");
        hint.setPadding(0, dp(8), 0, dp(7));
        list.addView(hint);

        HorizontalScrollView scroller = new HorizontalScrollView(this);
        scroller.setHorizontalScrollBarEnabled(false);
        LinearLayout filters = new LinearLayout(this);
        filters.setOrientation(LinearLayout.HORIZONTAL);
        filters.setPadding(0, 0, dp(8), 0);
        scroller.addView(filters);

        Button date = compactButton(dateFilterLabel(), dateFilter == 0 ? SURFACE_ALT : "#164E63");
        date.setOnClickListener(v -> { dateFilter = (dateFilter + 1) % 5; render(); });
        filters.addView(date);

        Button type = compactButton(typeFilterLabel(), typeFilter == 0 ? SURFACE_ALT : "#4C1D95");
        type.setOnClickListener(v -> { typeFilter = (typeFilter + 1) % 5; render(); });
        filters.addView(type, leftMarginParams(7));

        Button owner = compactButton(ownerFilterLabel(), ownerFilter == 0 ? SURFACE_ALT : "#14532D");
        owner.setOnClickListener(v -> { ownerFilter = (ownerFilter + 1) % 4; render(); });
        filters.addView(owner, leftMarginParams(7));

        Button clear = compactButton("Clear", "#3F1D2E");
        clear.setOnClickListener(v -> {
            dateFilter = 0;
            typeFilter = 0;
            ownerFilter = 0;
            render();
        });
        filters.addView(clear, leftMarginParams(7));
        list.addView(scroller);
    }

    private List<EventRow> applyFilters(List<EventRow> source) {
        List<EventRow> out = new ArrayList<>();
        for (EventRow event : source) {
            if (!matchesDate(event.timestamp)) continue;
            if (!matchesType(event)) continue;
            if (!matchesOwner(event)) continue;
            out.add(event);
        }
        return out;
    }

    private boolean matchesDate(long timestamp) {
        if (dateFilter == 0) return true;
        Calendar now = Calendar.getInstance();
        Calendar target = Calendar.getInstance();
        target.setTimeInMillis(timestamp);
        if (dateFilter == 1) return sameDay(target, now);
        if (dateFilter == 2) {
            now.add(Calendar.DAY_OF_YEAR, -1);
            return sameDay(target, now);
        }
        Calendar start = Calendar.getInstance();
        start.set(Calendar.HOUR_OF_DAY, 0);
        start.set(Calendar.MINUTE, 0);
        start.set(Calendar.SECOND, 0);
        start.set(Calendar.MILLISECOND, 0);
        start.add(Calendar.DAY_OF_YEAR, dateFilter == 3 ? -6 : -29);
        return timestamp >= start.getTimeInMillis();
    }

    private boolean matchesType(EventRow event) {
        if (typeFilter == 0) return true;
        if (typeFilter == 1) return "Failed credential".equals(event.incidentType);
        if (typeFilter == 2) return "Visible test".equals(event.incidentType);
        if (typeFilter == 3) return "Owner check".equals(event.incidentType);
        return "Other".equals(event.incidentType);
    }

    private boolean matchesOwner(EventRow event) {
        if (ownerFilter == 0) return true;
        if (ownerFilter == 3) return event.similarity < 0;
        if (event.similarity < 0) return false;
        boolean likelyOwner = event.similarity >= 0.72;
        return ownerFilter == 1 ? likelyOwner : !likelyOwner;
    }

    private String dateFilterLabel() {
        String[] values = {"Date: All", "Date: Today", "Date: Yesterday", "Date: 7 days", "Date: 30 days"};
        return values[dateFilter];
    }

    private String typeFilterLabel() {
        String[] values = {"Type: All", "Type: Failed", "Type: Visible test", "Type: Owner check", "Type: Other"};
        return values[typeFilter];
    }

    private String ownerFilterLabel() {
        String[] values = {"Owner: All", "Owner: Likely", "Owner: Not owner", "Owner: Unscored"};
        return values[ownerFilter];
    }

    private String incidentType(String rawReason) {
        String value = rawReason == null ? "" : rawReason.toUpperCase(Locale.US);
        if (value.contains("FAILED_PATTERN") || value.contains("FAILED_PIN") || value.contains("FAILED_PASSWORD")) {
            return "Failed credential";
        }
        if (value.contains("VISIBLE_TEST")) return "Visible test";
        if (value.contains("OWNER_CHECK") || value.contains("COMPLETED_UNLOCK")) return "Owner check";
        return "Other";
    }

    private String incidentColor(String type) {
        if ("Failed credential".equals(type)) return "#991B1B";
        if ("Visible test".equals(type)) return "#075985";
        if ("Owner check".equals(type)) return "#166534";
        return "#475569";
    }

    private String ownerLabel(EventRow event) {
        if (event.similarity < 0) return "OWNER UNSCORED";
        int pct = (int) Math.round(Math.max(0, Math.min(1, event.similarity)) * 100.0);
        return event.similarity >= 0.72 ? "LIKELY OWNER " + pct + "%" : "NOT OWNER " + pct + "%";
    }

    private String ownerColor(EventRow event) {
        if (event.similarity < 0) return "#475569";
        return event.similarity >= 0.72 ? "#166534" : "#9A3412";
    }

    private TextView badge(String text, String color) {
        TextView badge = new TextView(this);
        badge.setText(text);
        badge.setTextSize(10);
        badge.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        badge.setTextColor(Color.WHITE);
        badge.setGravity(Gravity.CENTER);
        badge.setSingleLine(true);
        badge.setBackground(shape(color, 999));
        badge.setPadding(dp(8), dp(3), dp(8), dp(3));
        return badge;
    }

    private void loadThumbnail(File encrypted, ImageView image, TextView placeholder, int generation) {
        String key = encrypted.getAbsolutePath() + ':' + encrypted.lastModified() + ':' + encrypted.length();
        image.setTag(key);
        Bitmap cached;
        synchronized (thumbnailCache) { cached = thumbnailCache.get(key); }
        if (cached != null && !cached.isRecycled()) {
            image.setImageBitmap(cached);
            placeholder.setVisibility(View.GONE);
            return;
        }

        final int targetPixels = dp(190);
        thumbnailExecutor.execute(() -> {
            Bitmap decoded = decodeThumbnail(encrypted, targetPixels);
            mainHandler.post(() -> {
                if (generation != renderGeneration || isFinishing() || isDestroyed()) return;
                if (!key.equals(image.getTag())) return;
                if (decoded == null) {
                    placeholder.setText("No preview");
                    return;
                }
                synchronized (thumbnailCache) { thumbnailCache.put(key, decoded); }
                image.setImageBitmap(decoded);
                placeholder.setVisibility(View.GONE);
            });
        });
    }

    private Bitmap decodeThumbnail(File encrypted, int targetPixels) {
        File clear = null;
        try {
            clear = File.createTempFile("og_thumb_", ".jpg", getCacheDir());
            VaultCrypto.decryptFile(encrypted, clear);

            BitmapFactory.Options bounds = new BitmapFactory.Options();
            bounds.inJustDecodeBounds = true;
            BitmapFactory.decodeFile(clear.getAbsolutePath(), bounds);
            if (bounds.outWidth < 1 || bounds.outHeight < 1) return null;

            int sample = 1;
            int smaller = Math.min(bounds.outWidth, bounds.outHeight);
            while (smaller / (sample * 2) >= targetPixels) sample *= 2;

            BitmapFactory.Options options = new BitmapFactory.Options();
            options.inSampleSize = Math.max(1, sample);
            options.inPreferredConfig = Bitmap.Config.RGB_565;
            Bitmap bitmap = BitmapFactory.decodeFile(clear.getAbsolutePath(), options);
            if (bitmap == null) return null;
            return EvidenceShare.rotateIfNeeded(bitmap, clear);
        } catch (Throwable ignored) {
            return null;
        } finally {
            if (clear != null) {
                clear.delete();
                new File(clear.getParentFile(), clear.getName() + ".decrypting").delete();
            }
        }
    }

    private void openEvent(EventRow event) {
        startActivity(new Intent(this, EventDetailActivity.class).putExtra("event", event.dir.getName()));
    }

    private int countForDay(List<EventRow> events, String dayKey) {
        int count = 0;
        for (EventRow e : events) if (dayKey(e.timestamp).equals(dayKey)) count++;
        return count;
    }

    private String dayKey(long timestamp) {
        return new SimpleDateFormat("yyyy-MM-dd", Locale.US).format(new Date(timestamp));
    }

    private String dayLabel(long timestamp) {
        Calendar target = Calendar.getInstance();
        target.setTimeInMillis(timestamp);
        Calendar today = Calendar.getInstance();
        if (sameDay(target, today)) return "Today";
        today.add(Calendar.DAY_OF_YEAR, -1);
        if (sameDay(target, today)) return "Yesterday";
        return new SimpleDateFormat("EEE, d MMM yyyy", Locale.getDefault()).format(new Date(timestamp));
    }

    private boolean sameDay(Calendar a, Calendar b) {
        return a.get(Calendar.ERA) == b.get(Calendar.ERA)
                && a.get(Calendar.YEAR) == b.get(Calendar.YEAR)
                && a.get(Calendar.DAY_OF_YEAR) == b.get(Calendar.DAY_OF_YEAR);
    }

    private String humanReason(String value) {
        String normalized = value == null ? "Unknown event" : value.replace('_', ' ').trim();
        if (normalized.isEmpty()) return "Unknown event";
        return Character.toUpperCase(normalized.charAt(0)) + normalized.substring(1);
    }

    private TextView title(String s) {
        TextView v = new TextView(this);
        v.setText(s);
        v.setTextSize(26);
        v.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        v.setTextColor(Color.parseColor(TEXT));
        return v;
    }

    private TextView subtitle(String s) {
        TextView v = new TextView(this);
        v.setText(s);
        v.setTextSize(13);
        v.setTextColor(Color.parseColor(MUTED));
        return v;
    }

    private Button compactButton(String label, String bg) {
        Button b = button(label, bg);
        b.setTextSize(12);
        b.setMinHeight(0);
        b.setMinimumHeight(0);
        b.setMinWidth(0);
        b.setMinimumWidth(0);
        b.setPadding(dp(12), 0, dp(12), 0);
        return b;
    }

    private Button button(String label, String bg) {
        Button b = new Button(this);
        b.setText(label);
        b.setAllCaps(false);
        b.setTextColor(Color.parseColor(TEXT));
        b.setBackground(shape(bg, 16));
        b.setPadding(dp(14), dp(10), dp(14), dp(10));
        return b;
    }

    private GradientDrawable shape(String color, int radiusDp) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(Color.parseColor(color));
        d.setCornerRadius(dp(radiusDp));
        d.setStroke(dp(1), Color.parseColor(DIVIDER));
        return d;
    }

    private LinearLayout.LayoutParams leftMarginParams(int margin) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.setMargins(dp(margin), 0, 0, 0);
        return lp;
    }

    private LinearLayout.LayoutParams topMarginParams(int top) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.setMargins(0, dp(top), 0, 0);
        return lp;
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }
}
