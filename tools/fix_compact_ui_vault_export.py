#!/usr/bin/env python3
"""Finalize OwnerGuard 1.0.44 compact UI, bounded Vault paging, and ZIP evidence export."""
from __future__ import annotations

from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
JAVA = ROOT / "app" / "src" / "main" / "java" / "com" / "fantest" / "ownerguard"
MAIN = JAVA / "MainActivity.java"
VAULT = JAVA / "VaultActivity.java"
EXPORT = JAVA / "VaultExportManager.java"
SHARE = JAVA / "SecureShareProvider.java"


def method_bounds(text: str, signature: str) -> tuple[int, int]:
    start = text.find(signature)
    if start < 0:
        raise SystemExit(f"Compact UI method anchor missing: {signature}")
    brace = text.find("{", start)
    if brace < 0:
        raise SystemExit(f"Compact UI method brace missing: {signature}")
    depth = 0
    in_string = False
    escaped = False
    quote = ""
    for i in range(brace, len(text)):
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
                    return start, i + 1
    raise SystemExit(f"Compact UI method did not terminate: {signature}")


def replace_method(text: str, signature: str, replacement: str) -> str:
    start, end = method_bounds(text, signature)
    return text[:start] + replacement.rstrip() + text[end:]


# ---------------------------------------------------------------------------
# Dashboard / Protection / Local Setup density pass.
# ---------------------------------------------------------------------------
main = MAIN.read_text(encoding="utf-8")

main = replace_method(main, "    private void showOverviewTab(){", r'''    private void showOverviewTab(){
        drawerRoute="dashboard";
        if(drawerShell!=null)drawerShell.setPage("Dashboard","dashboard");
        LinearLayout page=page();

        status=text("",13);
        status.setBackground(card("#14213D"));
        status.setPadding(dp(14),dp(11),dp(14),dp(11));
        page.addView(status,topMargin(2));
        refreshStatus();

        TextView local=labelCard("Local protection and the encrypted phone vault are ready offline. OwnerGuard Pro adds optional encrypted Google Drive / cloud-folder backup.");
        local.setPadding(dp(12),dp(10),dp(12),dp(10));
        page.addView(local,topMargin(8));

        page.addView(sectionTitle("Security status"),topMargin(16));
        LinearLayout row1=new LinearLayout(this);row1.setOrientation(LinearLayout.HORIZONTAL);
        row1.addView(compactInfoTile("Owner face samples",FaceSimilarity.sampleCount(this)+" / 5"),compactTileParams(true));
        row1.addView(compactInfoTile("Encrypted incidents",String.valueOf(new FileCounter(this).count())),compactTileParams(false));
        page.addView(row1,topMargin(8));
        LinearLayout row2=new LinearLayout(this);row2.setOrientation(LinearLayout.HORIZONTAL);
        row2.addView(compactInfoTile("Evidence location",LocationSnapshot.hasPermission(this)?"Enabled":"Optional"),compactTileParams(true));
        row2.addView(compactInfoTile("Vault status",VaultCrypto.selfTest(this)?"Ready":"Attention"),compactTileParams(false));
        page.addView(row2,topMargin(8));

        Button open=button("Open incident vault",v->startActivity(new Intent(this,VaultActivity.class)));
        page.addView(open,topMargin(12));
        Button test=secondaryButton("Run capture test",v->testCapture());
        page.addView(test,topMargin(8));

        page.addView(sectionTitle("Device controls"),topMargin(18));
        page.addView(compactNavRow("Protection controls","Arm, disarm and evidence rules",v->showProtectionTab()),topMargin(7));
        page.addView(compactNavRow("Local setup","Permissions, face, app lock and updates",v->showVaultTab()),topMargin(6));
        setPage(page);
    }''')

main = replace_method(main, "    private void showProtectionTab(){", r'''    private void showProtectionTab(){
        drawerRoute="protection";
        if(drawerShell!=null)drawerShell.setPage("Protection","protection");
        LinearLayout page=page();
        page.addView(sectionTitle("Protection controls"));

        LinearLayout actions=new LinearLayout(this);actions.setOrientation(LinearLayout.HORIZONTAL);
        Button arm=button("Arm protection",v->arm());
        Button disarm=secondaryButton("Disarm protection",v->disarm());
        actions.addView(arm,compactActionParams(true));
        actions.addView(disarm,compactActionParams(false));
        page.addView(actions,topMargin(8));

        TextView note=labelCard("Credential evidence is enabled by default. Accepted events record three encrypted front-camera photos followed by an encrypted five-second video.");
        note.setPadding(dp(12),dp(10),dp(12),dp(10));
        page.addView(note,topMargin(10));

        page.addView(compactCheckRow("Capture after failed unlock","Photos + video","instant_failed_capture",true),topMargin(8));
        page.addView(compactCheckRow("Capture after successful unlock","Photos + video","capture_successful_unlock",true),topMargin(6));
        page.addView(compactCheckRow("Auto-delete very strong owner matches","Keep the vault clean when confidence is very high","auto_delete_owner",false),topMargin(6));
        page.addView(compactCheckRow("Minimal notification content","Reduce lock-screen detail while protection is armed","minimal_notification",true),topMargin(6));

        TextView androidNote=labelCard("Android requires a visible foreground-service notification while camera protection is armed. OwnerGuard minimizes its content but cannot safely hide it.");
        androidNote.setPadding(dp(12),dp(10),dp(12),dp(10));
        page.addView(androidNote,topMargin(10));
        setPage(page);
    }''')

main = replace_method(main, "    private void showVaultTab(){", r'''    private void showVaultTab(){
        drawerRoute="setup";
        if(drawerShell!=null)drawerShell.setPage("Local setup","setup");
        LinearLayout page=page();

        page.addView(sectionTitle("Vault & device"));
        page.addView(compactNavRow("Permissions & battery","Review required access",v->requestPermissionsNow()),topMargin(7));
        page.addView(compactNavRow("PIN / password monitoring","Pattern, PIN & password checks",v->enableAdmin()),topMargin(6));
        page.addView(compactNavRow("Owner-face enrollment",FaceSimilarity.sampleCount(this)>=5?"Replace or re-enroll":"Complete five-angle enrollment",v->startGuidedEnrollment()),topMargin(6));
        page.addView(compactNavRow("Selfie recognition test","Verify owner-face matching",v->startSelfieTest()),topMargin(6));
        page.addView(compactNavRow("Clear owner-face data","Remove all enrollment samples",v->{FaceSimilarity.clear(this);Toast.makeText(this,"Owner face enrollment cleared",Toast.LENGTH_SHORT).show();refreshStatus();showVaultTab();}),topMargin(6));
        page.addView(compactNavRow("Vault encryption test","Run cryptographic self-test",v->runVaultSelfTest()),topMargin(6));

        page.addView(sectionTitle("App lock"),topMargin(18));
        page.addView(compactNavRow("Lock after leaving",AuthSession.timeoutLabel(this),v->showLockTimeoutChooser()),topMargin(7));
        page.addView(compactNavRow("Lock now","Secure OwnerGuard immediately",v->{AuthSession.lock();buildPinScreen();}),topMargin(6));

        page.addView(sectionTitle("App updates"),topMargin(18));
        page.addView(compactInfoRow("OwnerGuard 1.0.44","Google Play managed release"),topMargin(7));
        page.addView(compactInfoRow("Installed","1.0.44 (10044)"),topMargin(6));
        page.addView(button("Check Google Play updates",v->AppUpdateManager.check(this,true)),topMargin(8));

        page.addView(sectionTitle("OwnerGuard Pro"),topMargin(18));
        TextView proCopy=labelCard("Core protection and the local encrypted vault remain free. Pro adds portable encrypted Google Drive / cloud-folder backup and disaster-recovery restore.");
        proCopy.setPadding(dp(12),dp(10),dp(12),dp(10));
        page.addView(proCopy,topMargin(7));
        page.addView(compactInfoRow("Pro status",ProEntitlement.cachedStatus(this)),topMargin(6));
        page.addView(button(ProEntitlement.cached(this)?"Open Pro backup":"Unlock Pro backup",v->startActivity(new Intent(this,ProBackupActivity.class))),topMargin(8));
        setPage(page);
    }''')

if "private LinearLayout compactNavRow(" not in main:
    anchor = "    private void showLockTimeoutChooser(){"
    if anchor not in main:
        raise SystemExit("Compact UI helper insertion anchor missing")
    helpers = r'''    private LinearLayout compactInfoTile(String label,String value){
        LinearLayout box=vertical(10);box.setBackground(card("#14213D"));box.setPadding(dp(12),dp(10),dp(12),dp(10));
        TextView l=text(label,12);l.setSingleLine(true);l.setEllipsize(android.text.TextUtils.TruncateAt.END);
        TextView v=text(value,18);v.setTextColor(Color.WHITE);v.setPadding(0,dp(3),0,0);
        box.addView(l);box.addView(v);return box;
    }

    private LinearLayout.LayoutParams compactTileParams(boolean first){
        LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(0,ViewGroup.LayoutParams.WRAP_CONTENT,1f);
        if(first)p.setMargins(0,0,dp(4),0);else p.setMargins(dp(4),0,0,0);return p;
    }

    private LinearLayout.LayoutParams compactActionParams(boolean first){
        LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(0,dp(50),1f);
        if(first)p.setMargins(0,0,dp(4),0);else p.setMargins(dp(4),0,0,0);return p;
    }

    private LinearLayout compactNavRow(String title,String subtitle,View.OnClickListener listener){
        LinearLayout row=new LinearLayout(this);row.setOrientation(LinearLayout.HORIZONTAL);row.setGravity(Gravity.CENTER_VERTICAL);
        row.setBackground(card("#14213D"));row.setPadding(dp(13),dp(8),dp(10),dp(8));row.setMinimumHeight(dp(52));
        LinearLayout copy=new LinearLayout(this);copy.setOrientation(LinearLayout.VERTICAL);
        TextView t=text(title,14);t.setTextColor(Color.WHITE);TextView s=text(subtitle,11);s.setSingleLine(true);s.setEllipsize(android.text.TextUtils.TruncateAt.END);s.setPadding(0,dp(2),0,0);
        copy.addView(t);copy.addView(s);row.addView(copy,new LinearLayout.LayoutParams(0,ViewGroup.LayoutParams.WRAP_CONTENT,1f));
        TextView chevron=text("›",25);chevron.setGravity(Gravity.CENTER);chevron.setTextColor(Color.parseColor("#94A3B8"));row.addView(chevron,new LinearLayout.LayoutParams(dp(28),dp(36)));
        row.setOnClickListener(listener);row.setClickable(true);row.setFocusable(true);return row;
    }

    private LinearLayout compactInfoRow(String title,String value){
        LinearLayout row=new LinearLayout(this);row.setOrientation(LinearLayout.HORIZONTAL);row.setGravity(Gravity.CENTER_VERTICAL);
        row.setBackground(card("#14213D"));row.setPadding(dp(13),dp(9),dp(13),dp(9));row.setMinimumHeight(dp(50));
        TextView t=text(title,13);t.setTextColor(Color.WHITE);row.addView(t,new LinearLayout.LayoutParams(0,ViewGroup.LayoutParams.WRAP_CONTENT,1f));
        TextView v=text(value,12);v.setGravity(Gravity.END);v.setMaxWidth(dp(220));row.addView(v);return row;
    }

    private LinearLayout compactCheckRow(String title,String subtitle,String key,boolean def){
        LinearLayout row=new LinearLayout(this);row.setOrientation(LinearLayout.HORIZONTAL);row.setGravity(Gravity.CENTER_VERTICAL);
        row.setBackground(card("#14213D"));row.setPadding(dp(10),dp(6),dp(11),dp(6));row.setMinimumHeight(dp(56));
        CheckBox box=check("",key,def);box.setPadding(0,0,dp(8),0);row.addView(box,new LinearLayout.LayoutParams(dp(42),dp(44)));
        LinearLayout copy=new LinearLayout(this);copy.setOrientation(LinearLayout.VERTICAL);
        TextView t=text(title,14);t.setTextColor(Color.WHITE);TextView s=text(subtitle,11);s.setPadding(0,dp(2),0,0);
        copy.addView(t);copy.addView(s);row.addView(copy,new LinearLayout.LayoutParams(0,ViewGroup.LayoutParams.WRAP_CONTENT,1f));
        row.setOnClickListener(v->box.setChecked(!box.isChecked()));return row;
    }

'''
    main = main.replace(anchor, helpers + anchor, 1)

main = main.replace("private LinearLayout page(){ScrollView s=new ScrollView(this);s.setBackgroundColor(Color.parseColor(\"#0A1220\"));LinearLayout p=vertical(18);",
                    "private LinearLayout page(){ScrollView s=new ScrollView(this);s.setBackgroundColor(Color.parseColor(\"#0A1220\"));LinearLayout p=vertical(14);")
main = main.replace("private TextView sectionTitle(String s){TextView t=text(s,22);", "private TextView sectionTitle(String s){TextView t=text(s,18);")

for token in (
    "Open incident vault",
    "Permissions & battery",
    "PIN / password monitoring",
    "compactCheckRow(\"Capture after successful unlock\"",
    "ProBackupActivity.class",
    "Google Play managed release",
):
    if token not in main:
        raise SystemExit("Compact MainActivity invariant missing: " + token)
MAIN.write_text(main, encoding="utf-8")


# ---------------------------------------------------------------------------
# Dedicated ZIP export helper. All cleartext exists only in authenticated cache.
# ---------------------------------------------------------------------------
EXPORT.write_text(r'''package com.fantest.ownerguard;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;

import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

final class VaultExportManager {
    interface Callback {
        void onProgress(int completed, int total);
        void onComplete(File zipFile);
        void onError(String message);
    }

    private static final ExecutorService EXECUTOR = Executors.newSingleThreadExecutor();
    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    static void exportIncidents(Activity activity, List<File> incidentDirs, String exportLabel, Callback callback) {
        final ArrayList<File> dirs = new ArrayList<>(incidentDirs);
        final File exportRoot = new File(activity.getCacheDir(), "exports");
        EXECUTOR.execute(() -> {
            try {
                if (!exportRoot.exists() && !exportRoot.mkdirs()) throw new IllegalStateException("Could not create export cache");
                File[] old = exportRoot.listFiles((d, n) -> n.startsWith("OwnerGuard_IncidentVault_") && n.endsWith(".zip"));
                if (old != null) for (File file : old) if (System.currentTimeMillis() - file.lastModified() > 24L * 60L * 60L * 1000L) file.delete();

                String stamp = new SimpleDateFormat("yyyy-MM-dd_HHmmss", Locale.US).format(new Date());
                String safeLabel = safePart(exportLabel == null || exportLabel.trim().isEmpty() ? "Export" : exportLabel);
                File zipFile = new File(exportRoot, "OwnerGuard_IncidentVault_" + safeLabel + "_" + stamp + ".zip");
                StringBuilder manifest = new StringBuilder("timestamp,incident_id,reason,incident_type,photo_count,has_video,failed_credential_attempts,owner_similarity,location\n");

                try (ZipOutputStream zip = new ZipOutputStream(new FileOutputStream(zipFile))) {
                    int completed = 0;
                    for (File incident : dirs) {
                        if (incident == null || !incident.isDirectory()) continue;
                        JSONObject meta = readMetadata(incident);
                        long timestamp = meta.optLong("startedAt", incident.lastModified());
                        String day = new SimpleDateFormat("yyyy-MM-dd", Locale.US).format(new Date(timestamp));
                        String incidentId = safePart(incident.getName());
                        String folder = day + "/" + incidentId + "/";

                        File metadata = new File(incident, "metadata.ogv");
                        if (metadata.isFile()) putEntry(zip, folder + "metadata.json", VaultCrypto.decryptBytes(metadata));

                        File[] photos = incident.listFiles((d, n) -> n.startsWith("photo_") && n.endsWith(".ogv"));
                        if (photos == null) photos = new File[0];
                        Arrays.sort(photos, (a, b) -> a.getName().compareToIgnoreCase(b.getName()));
                        for (int i = 0; i < photos.length; i++) {
                            putEntry(zip, folder + String.format(Locale.US, "photo_%02d.jpg", i + 1), VaultCrypto.decryptBytes(photos[i]));
                        }

                        File video = new File(incident, "video_05s.ogv");
                        boolean hasVideo = video.isFile();
                        if (hasVideo) putEntry(zip, folder + "video_05s.mp4", VaultCrypto.decryptBytes(video));

                        String reason = meta.optString("reason", "");
                        int failures = meta.optInt("failedCredentialAttempts", 0);
                        double similarity = meta.optDouble("ownerSimilarityPrototype", -1d);
                        String location = "";
                        if (meta.optBoolean("locationAvailable", false)) {
                            location = String.format(Locale.US, "%.5f, %.5f", meta.optDouble("latitude"), meta.optDouble("longitude"));
                        }
                        manifest.append(csv(new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(new Date(timestamp)))).append(',')
                                .append(csv(incidentId)).append(',')
                                .append(csv(reason)).append(',')
                                .append(csv(incidentType(reason))).append(',')
                                .append(photos.length).append(',')
                                .append(hasVideo).append(',')
                                .append(failures).append(',')
                                .append(similarity < 0 ? "" : String.format(Locale.US, "%.4f", similarity)).append(',')
                                .append(csv(location)).append('\n');

                        completed++;
                        final int progress = completed;
                        MAIN.post(() -> callback.onProgress(progress, dirs.size()));
                    }
                    putEntry(zip, "evidence_manifest.csv", manifest.toString().getBytes(StandardCharsets.UTF_8));
                }
                if (!zipFile.isFile() || zipFile.length() < 64) throw new IllegalStateException("ZIP export is empty");
                MAIN.post(() -> callback.onComplete(zipFile));
            } catch (Throwable error) {
                MAIN.post(() -> callback.onError(error.getClass().getSimpleName() + ": " + String.valueOf(error.getMessage())));
            }
        });
    }

    static void shareZip(Activity activity, File zipFile) throws Exception {
        Uri uri = SecureShareProvider.uriForFile(activity, zipFile);
        Intent send = new Intent(Intent.ACTION_SEND)
                .setType("application/zip")
                .putExtra(Intent.EXTRA_STREAM, uri)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        activity.startActivity(Intent.createChooser(send, "Share OwnerGuard evidence ZIP"));
    }

    private static JSONObject readMetadata(File incident) {
        try {
            File metadata = new File(incident, "metadata.ogv");
            return metadata.isFile() ? new JSONObject(new String(VaultCrypto.decryptBytes(metadata), StandardCharsets.UTF_8)) : new JSONObject();
        } catch (Throwable ignored) {
            return new JSONObject();
        }
    }

    private static void putEntry(ZipOutputStream zip, String name, byte[] bytes) throws Exception {
        ZipEntry entry = new ZipEntry(name.replace('\\', '/'));
        entry.setTime(0L);
        zip.putNextEntry(entry);
        zip.write(bytes);
        zip.closeEntry();
    }

    private static String safePart(String value) {
        String safe = value.replaceAll("[^A-Za-z0-9._-]+", "_").replaceAll("_+", "_");
        while (safe.startsWith(".")) safe = safe.substring(1);
        return safe.isEmpty() ? "incident" : safe;
    }

    private static String csv(String value) {
        String text = value == null ? "" : value;
        return "\"" + text.replace("\"", "\"\"").replace("\r", " ").replace("\n", " ") + "\"";
    }

    private static String incidentType(String rawReason) {
        String value = rawReason == null ? "" : rawReason.toUpperCase(Locale.US);
        if (value.contains("FAILED_PATTERN") || value.contains("FAILED_PIN") || value.contains("FAILED_PASSWORD")) return "Failed credential";
        if (value.contains("VISIBLE_TEST")) return "Visible test";
        if (value.contains("OWNER_CHECK") || value.contains("COMPLETED_UNLOCK")) return "Owner check";
        return "Other";
    }
}
''', encoding="utf-8")


# ---------------------------------------------------------------------------
# Incident Vault: compact rows, date-first filters, pagination and selection.
# ---------------------------------------------------------------------------
vault = VAULT.read_text(encoding="utf-8")

for old, new in (
    ("import android.content.Intent;", "import android.content.Intent;\nimport android.app.AlertDialog;\nimport android.app.DatePickerDialog;"),
    ("import android.widget.Button;", "import android.widget.Button;\nimport android.widget.CheckBox;\nimport android.widget.Toast;"),
    ("import java.util.List;", "import java.util.List;\nimport java.util.HashSet;\nimport java.util.Set;"),
):
    if new not in vault:
        if old not in vault: raise SystemExit("Vault import anchor missing: " + old)
        vault = vault.replace(old, new, 1)

old_fields = '''    private LinearLayout list;
    private boolean newestFirst = true;
    private int dateFilter = 0;   // 0 all, 1 today, 2 yesterday, 3 last 7 days, 4 last 30 days
    private int typeFilter = 0;   // 0 all, 1 failed credential, 2 visible test, 3 owner check, 4 other
    private int ownerFilter = 0;  // 0 all, 1 likely owner, 2 not owner, 3 unscored
    private int renderGeneration = 0;'''
new_fields = '''    private static final int DEFAULT_PAGE_SIZE = 24;
    private static final int DATE_ALL = 0;
    private static final int DATE_TODAY = 1;
    private static final int DATE_WEEK = 2;
    private static final int DATE_CUSTOM = 3;

    private LinearLayout list;
    private boolean newestFirst = true;
    private int dateFilter = DATE_ALL;
    private int typeFilter = 0;
    private int ownerFilter = 0;
    private int currentPage = 0;
    private long customStart = -1L;
    private long customEnd = -1L;
    private boolean selectionMode = false;
    private final Set<String> selectedIncidentIds = new HashSet<>();
    private int renderGeneration = 0;'''
if old_fields not in vault and "DEFAULT_PAGE_SIZE = 24" not in vault:
    raise SystemExit("Vault field anchor missing")
if old_fields in vault:
    vault = vault.replace(old_fields, new_fields, 1)

vault = replace_method(vault, "    private void render() {", r'''    private void render() {
        final int generation = ++renderGeneration;
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setBackgroundColor(Color.parseColor(BG));

        list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        int p = dp(14);
        list.setPadding(p, dp(12), p, dp(14));
        scroll.addView(list);

        LinearLayout titleRow = new LinearLayout(this);
        titleRow.setOrientation(LinearLayout.HORIZONTAL);
        titleRow.setGravity(Gravity.CENTER_VERTICAL);
        titleRow.addView(title("Incident vault"), new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        if (!selectionMode) {
            Button select = compactButton("Select", SURFACE_ALT);
            select.setOnClickListener(v -> { selectionMode = true; render(); });
            titleRow.addView(select, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(40)));
        }
        list.addView(titleRow);

        List<EventRow> allEvents = loadAllEvents();
        List<EventRow> filtered = applyFilters(allEvents);
        selectedIncidentIds.retainAll(eventIds(filtered));
        int total = filtered.size();
        int pageCount = Math.max(1, (total + DEFAULT_PAGE_SIZE - 1) / DEFAULT_PAGE_SIZE);
        currentPage = Math.max(0, Math.min(currentPage, pageCount - 1));
        int from = Math.min(total, currentPage * DEFAULT_PAGE_SIZE);
        int to = Math.min(total, from + DEFAULT_PAGE_SIZE);
        List<EventRow> pageRows = filtered.subList(from, to);

        addFilterPanel();
        if (selectionMode) addSelectionToolbar(pageRows, filtered);

        LinearLayout summary = new LinearLayout(this);
        summary.setOrientation(LinearLayout.HORIZONTAL);
        summary.setGravity(Gravity.CENTER_VERTICAL);
        String countLabel = total == 0 ? "0 incidents" : (from + 1) + "–" + to + " of " + total + " incidents";
        TextView count = subtitle(countLabel);
        summary.addView(count, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        if (!selectionMode && total > 0) {
            Button export = compactButton("Export this range", "#075985");
            export.setOnClickListener(v -> startExport(filtered, exportRangeLabel()));
            summary.addView(export, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(40)));
        }
        list.addView(summary, topMarginParams(8));

        if (pageRows.isEmpty()) {
            TextView empty = subtitle(allEvents.isEmpty() ? "No incidents stored yet." : "No incidents match the selected filters.");
            empty.setGravity(Gravity.CENTER);
            empty.setPadding(0, dp(34), 0, dp(34));
            list.addView(empty);
        } else {
            String currentDay = null;
            for (EventRow event : pageRows) {
                String dayKey = dayKey(event.timestamp);
                if (!dayKey.equals(currentDay)) {
                    currentDay = dayKey;
                    addDateHeader(event.timestamp, countForDay(pageRows, dayKey));
                }
                addEvent(event, generation);
            }
        }

        if (total > DEFAULT_PAGE_SIZE) addPagination(pageCount, from, to, total);

        Button back = button("Back", SURFACE_ALT);
        back.setOnClickListener(v -> finish());
        list.addView(back, topMarginParams(14));
        setContentView(scroll);
    }''')

vault = replace_method(vault, "    private void addEvent(EventRow event, int generation) {", r'''    private void addEvent(EventRow event, int generation) {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.HORIZONTAL);
        card.setGravity(Gravity.CENTER_VERTICAL);
        card.setBackground(shape(SURFACE, 14));
        card.setPadding(dp(7), dp(7), dp(8), dp(7));
        card.setClickable(true);
        card.setFocusable(true);
        card.setOnClickListener(v -> {
            if (selectionMode) toggleSelection(event);
            else openEvent(event);
        });
        list.addView(card, topMarginParams(6));

        if (selectionMode) {
            CheckBox selected = new CheckBox(this);
            selected.setChecked(selectedIncidentIds.contains(eventId(event)));
            selected.setButtonTintList(android.content.res.ColorStateList.valueOf(Color.parseColor(PRIMARY)));
            selected.setOnClickListener(v -> toggleSelection(event));
            card.addView(selected, new LinearLayout.LayoutParams(dp(38), dp(48)));
        }

        FrameLayout preview = new FrameLayout(this);
        preview.setBackground(shape(SURFACE_ALT, 11));
        preview.setClipToOutline(true);
        LinearLayout.LayoutParams previewParams = new LinearLayout.LayoutParams(dp(68), dp(68));
        previewParams.setMargins(0, 0, dp(10), 0);
        card.addView(preview, previewParams);

        ImageView image = new ImageView(this);
        image.setScaleType(ImageView.ScaleType.CENTER_CROP);
        image.setContentDescription("Incident photo preview");
        image.setBackgroundColor(Color.parseColor(SURFACE_ALT));
        preview.addView(image, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        TextView placeholder = new TextView(this);
        placeholder.setText(event.thumbnail == null ? "No photo" : "Loading…");
        placeholder.setTextSize(10);
        placeholder.setTextColor(Color.parseColor(MUTED));
        placeholder.setGravity(Gravity.CENTER);
        preview.addView(placeholder, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        if (event.hasVideo) {
            TextView video = new TextView(this);video.setText("▶ 5s");video.setTextSize(9);video.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
            video.setTextColor(Color.WHITE);video.setGravity(Gravity.CENTER);video.setPadding(dp(5), dp(2), dp(5), dp(2));video.setBackground(shape("#CC0A1220", 999));
            FrameLayout.LayoutParams badgeParams = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.END | Gravity.BOTTOM);
            badgeParams.setMargins(0, 0, dp(4), dp(4));preview.addView(video, badgeParams);
        }

        LinearLayout content = new LinearLayout(this);content.setOrientation(LinearLayout.VERTICAL);content.setGravity(Gravity.CENTER_VERTICAL);
        card.addView(content, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        LinearLayout top = new LinearLayout(this);top.setOrientation(LinearLayout.HORIZONTAL);top.setGravity(Gravity.CENTER_VERTICAL);content.addView(top);
        TextView typeBadge = badge(event.incidentType, incidentColor(event.incidentType));top.addView(typeBadge);
        TextView reason = new TextView(this);reason.setText(event.reason);reason.setSingleLine(true);reason.setEllipsize(android.text.TextUtils.TruncateAt.END);reason.setTextSize(13);reason.setTextColor(Color.parseColor(TEXT));
        LinearLayout.LayoutParams reasonParams = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);reasonParams.setMargins(dp(6), 0, 0, 0);top.addView(reason, reasonParams);
        TextView chevron = new TextView(this);chevron.setText(selectionMode ? "" : "›");chevron.setTextSize(24);chevron.setTextColor(Color.parseColor(MUTED));chevron.setGravity(Gravity.CENTER);top.addView(chevron, new LinearLayout.LayoutParams(dp(22), dp(28)));

        String timeText = DateFormat.getTimeInstance(DateFormat.SHORT).format(new Date(event.timestamp));
        StringBuilder facts = new StringBuilder(timeText).append("  •  ").append(event.photos).append(event.photos == 1 ? " photo" : " photos");
        facts.append("  •  ").append(event.hasVideo ? "0:05" : "no video");
        if (event.failures > 0) facts.append("  •  ").append(event.failures).append(" failed");
        TextView meta = subtitle(facts.toString());meta.setSingleLine(true);meta.setEllipsize(android.text.TextUtils.TruncateAt.END);meta.setPadding(0, dp(3), 0, 0);content.addView(meta);

        if (event.similarity >= 0) {
            TextView owner = subtitle(ownerLabel(event));owner.setTextSize(10);owner.setTextColor(Color.parseColor(event.similarity >= 0.72 ? "#86EFAC" : "#FDBA74"));owner.setPadding(0, dp(2), 0, 0);content.addView(owner);
        }
        if (event.thumbnail != null) loadThumbnail(event.thumbnail, image, placeholder, generation);
    }''')

vault = replace_method(vault, "    private void addFilterPanel() {", r'''    private void addFilterPanel() {
        HorizontalScrollView dates = new HorizontalScrollView(this);dates.setHorizontalScrollBarEnabled(false);
        LinearLayout dateRow = new LinearLayout(this);dateRow.setOrientation(LinearLayout.HORIZONTAL);dateRow.setPadding(0, dp(9), dp(6), dp(3));dates.addView(dateRow);
        addDateChip(dateRow, "All", DATE_ALL);
        addDateChip(dateRow, "Today", DATE_TODAY);
        addDateChip(dateRow, "This week", DATE_WEEK);
        Button custom = compactButton("Custom", dateFilter == DATE_CUSTOM ? "#0891B2" : SURFACE_ALT);
        custom.setOnClickListener(v -> showCustomDatePicker());dateRow.addView(custom, leftMarginParams(6));
        list.addView(dates);

        HorizontalScrollView secondary = new HorizontalScrollView(this);secondary.setHorizontalScrollBarEnabled(false);
        LinearLayout filters = new LinearLayout(this);filters.setOrientation(LinearLayout.HORIZONTAL);filters.setPadding(0, dp(3), dp(6), dp(3));secondary.addView(filters);
        Button type = compactButton(typeFilterLabel(), typeFilter == 0 ? SURFACE_ALT : "#4C1D95");
        type.setOnClickListener(v -> { typeFilter = (typeFilter + 1) % 5; resetPagingAndSelection(); render(); });filters.addView(type);
        Button owner = compactButton(ownerFilterLabel(), ownerFilter == 0 ? SURFACE_ALT : "#14532D");
        owner.setOnClickListener(v -> { ownerFilter = (ownerFilter + 1) % 4; resetPagingAndSelection(); render(); });filters.addView(owner, leftMarginParams(6));
        Button sort = compactButton(newestFirst ? "Newest" : "Oldest", SURFACE_ALT);
        sort.setOnClickListener(v -> { newestFirst = !newestFirst; currentPage = 0; render(); });filters.addView(sort, leftMarginParams(6));
        list.addView(secondary);
    }''')

vault = replace_method(vault, "    private boolean matchesDate(long timestamp) {", r'''    private boolean matchesDate(long timestamp) {
        if (dateFilter == DATE_ALL) return true;
        Calendar now = Calendar.getInstance();
        Calendar target = Calendar.getInstance();target.setTimeInMillis(timestamp);
        if (dateFilter == DATE_TODAY) return sameDay(target, now);
        if (dateFilter == DATE_WEEK) {
            Calendar start = Calendar.getInstance();
            start.set(Calendar.HOUR_OF_DAY, 0);start.set(Calendar.MINUTE, 0);start.set(Calendar.SECOND, 0);start.set(Calendar.MILLISECOND, 0);
            int delta = (7 + start.get(Calendar.DAY_OF_WEEK) - start.getFirstDayOfWeek()) % 7;
            start.add(Calendar.DAY_OF_YEAR, -delta);
            return timestamp >= start.getTimeInMillis();
        }
        if (dateFilter == DATE_CUSTOM) return customStart >= 0 && customEnd >= customStart && timestamp >= customStart && timestamp <= customEnd;
        return true;
    }''')

vault = replace_method(vault, "    private String dateFilterLabel() {", r'''    private String dateFilterLabel() {
        if (dateFilter == DATE_TODAY) return "Today";
        if (dateFilter == DATE_WEEK) return "This week";
        if (dateFilter == DATE_CUSTOM) return "Custom";
        return "All";
    }''')

if "private void addSelectionToolbar(" not in vault:
    anchor = "    private List<EventRow> loadAllEvents() {"
    if anchor not in vault: raise SystemExit("Vault helper insertion anchor missing")
    vault_helpers = r'''    private Set<String> eventIds(List<EventRow> rows) {
        Set<String> ids = new HashSet<>();for (EventRow row : rows) ids.add(eventId(row));return ids;
    }

    private String eventId(EventRow event) { return event.dir.getAbsolutePath(); }

    private void toggleSelection(EventRow event) {
        String id = eventId(event);if (selectedIncidentIds.contains(id)) selectedIncidentIds.remove(id);else selectedIncidentIds.add(id);render();
    }

    private void resetPagingAndSelection() {
        currentPage = 0;selectedIncidentIds.clear();selectionMode = false;
    }

    private void addDateChip(LinearLayout row, String label, int mode) {
        Button chip = compactButton(label, dateFilter == mode ? "#0891B2" : SURFACE_ALT);
        chip.setOnClickListener(v -> { dateFilter = mode;customStart = -1L;customEnd = -1L;resetPagingAndSelection();render(); });
        if (row.getChildCount() == 0) row.addView(chip); else row.addView(chip, leftMarginParams(6));
    }

    private void showCustomDatePicker() {
        Calendar start = Calendar.getInstance();
        new DatePickerDialog(this, (dialog, year, month, day) -> {
            Calendar from = Calendar.getInstance();from.set(year, month, day, 0, 0, 0);from.set(Calendar.MILLISECOND, 0);
            new DatePickerDialog(this, (endDialog, endYear, endMonth, endDay) -> {
                Calendar to = Calendar.getInstance();to.set(endYear, endMonth, endDay, 23, 59, 59);to.set(Calendar.MILLISECOND, 999);
                if (to.before(from)) { Toast.makeText(this, "To date must be on or after From date", Toast.LENGTH_LONG).show();return; }
                customStart = from.getTimeInMillis();customEnd = to.getTimeInMillis();dateFilter = DATE_CUSTOM;resetPagingAndSelection();render();
            }, year, month, day).show();
        }, start.get(Calendar.YEAR), start.get(Calendar.MONTH), start.get(Calendar.DAY_OF_MONTH)).show();
    }

    private void addSelectionToolbar(List<EventRow> pageRows, List<EventRow> filtered) {
        LinearLayout top = new LinearLayout(this);top.setOrientation(LinearLayout.HORIZONTAL);top.setGravity(Gravity.CENTER_VERTICAL);top.setBackground(shape("#10213A", 14));top.setPadding(dp(10), dp(7), dp(8), dp(7));
        TextView count = subtitle(selectedIncidentIds.size() + " selected");count.setTextColor(Color.parseColor(TEXT));top.addView(count, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        Button export = compactButton("Export ZIP", "#0891B2");export.setEnabled(!selectedIncidentIds.isEmpty());export.setOnClickListener(v -> startExport(selectedRows(filtered), "Selected"));top.addView(export, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(40)));list.addView(top, topMarginParams(8));

        HorizontalScrollView scroller = new HorizontalScrollView(this);scroller.setHorizontalScrollBarEnabled(false);LinearLayout actions = new LinearLayout(this);actions.setOrientation(LinearLayout.HORIZONTAL);actions.setPadding(0, dp(5), dp(5), dp(2));scroller.addView(actions);
        Button page = compactButton("Select page", SURFACE_ALT);page.setOnClickListener(v -> { for (EventRow e : pageRows) selectedIncidentIds.add(eventId(e));render(); });actions.addView(page);
        Button all = compactButton("Select all filtered", SURFACE_ALT);all.setOnClickListener(v -> { for (EventRow e : filtered) selectedIncidentIds.add(eventId(e));render(); });actions.addView(all, leftMarginParams(6));
        Button clear = compactButton("Clear", SURFACE_ALT);clear.setOnClickListener(v -> { selectedIncidentIds.clear();render(); });actions.addView(clear, leftMarginParams(6));
        Button cancel = compactButton("Cancel", "#3F1D2E");cancel.setOnClickListener(v -> { selectedIncidentIds.clear();selectionMode=false;render(); });actions.addView(cancel, leftMarginParams(6));list.addView(scroller);
    }

    private List<EventRow> selectedRows(List<EventRow> filtered) {
        List<EventRow> rows = new ArrayList<>();for (EventRow event : filtered) if (selectedIncidentIds.contains(eventId(event))) rows.add(event);return rows;
    }

    private void addPagination(int pageCount, int from, int to, int total) {
        LinearLayout pager = new LinearLayout(this);pager.setOrientation(LinearLayout.HORIZONTAL);pager.setGravity(Gravity.CENTER_VERTICAL);pager.setPadding(0, dp(10), 0, 0);
        Button previous = compactButton("‹", SURFACE_ALT);previous.setEnabled(currentPage > 0);previous.setOnClickListener(v -> { if (currentPage > 0) { currentPage--;render(); } });pager.addView(previous, new LinearLayout.LayoutParams(dp(48), dp(42)));
        TextView page = subtitle("Page " + (currentPage + 1) + " of " + pageCount + "   •   " + (from + 1) + "–" + to + " of " + total);page.setGravity(Gravity.CENTER);page.setTextColor(Color.parseColor(TEXT));pager.addView(page, new LinearLayout.LayoutParams(0, dp(42), 1f));
        Button next = compactButton("›", SURFACE_ALT);next.setEnabled(currentPage + 1 < pageCount);next.setOnClickListener(v -> { if (currentPage + 1 < pageCount) { currentPage++;render(); } });pager.addView(next, new LinearLayout.LayoutParams(dp(48), dp(42)));list.addView(pager);
    }

    private String exportRangeLabel() {
        if (dateFilter == DATE_TODAY) return "Today";
        if (dateFilter == DATE_WEEK) return "This_week";
        if (dateFilter == DATE_CUSTOM && customStart >= 0 && customEnd >= customStart) {
            SimpleDateFormat f = new SimpleDateFormat("yyyy-MM-dd", Locale.US);return f.format(new Date(customStart)) + "_to_" + f.format(new Date(customEnd));
        }
        return "All";
    }

    private void startExport(List<EventRow> rows, String label) {
        if (rows == null || rows.isEmpty()) { Toast.makeText(this, "No incidents selected for export", Toast.LENGTH_LONG).show();return; }
        ArrayList<File> dirs = new ArrayList<>();for (EventRow row : rows) dirs.add(row.dir);
        TextView progress = subtitle("Preparing evidence ZIP… 0 / " + dirs.size());progress.setPadding(dp(16),dp(14),dp(16),dp(14));
        AlertDialog dialog = new AlertDialog.Builder(this).setTitle("Export incident evidence").setView(progress).setCancelable(false).create();dialog.show();
        VaultExportManager.exportIncidents(this, dirs, label, new VaultExportManager.Callback() {
            @Override public void onProgress(int completed, int total) { progress.setText("Preparing evidence ZIP… " + completed + " / " + total); }
            @Override public void onComplete(File zipFile) {
                if (!isFinishing()) dialog.dismiss();
                Toast.makeText(VaultActivity.this, "Evidence ZIP ready", Toast.LENGTH_SHORT).show();
                try { VaultExportManager.shareZip(VaultActivity.this, zipFile); } catch (Exception error) { Toast.makeText(VaultActivity.this, "Could not share ZIP: " + error.getMessage(), Toast.LENGTH_LONG).show(); }
            }
            @Override public void onError(String message) { if (!isFinishing()) dialog.dismiss();Toast.makeText(VaultActivity.this, "ZIP export failed: " + message, Toast.LENGTH_LONG).show(); }
        });
    }

'''
    vault = vault.replace(anchor, vault_helpers + anchor, 1)

for token in (
    "DEFAULT_PAGE_SIZE = 24",
    "Select all filtered",
    "Export this range",
    "VaultExportManager.exportIncidents",
    "DATE_TODAY",
    "DATE_WEEK",
    "DATE_CUSTOM",
):
    if token not in vault: raise SystemExit("Vault compact/export invariant missing: " + token)
VAULT.write_text(vault, encoding="utf-8")

share = SHARE.read_text(encoding="utf-8")
if 'endsWith(".zip")' not in share:
    old = 'if(n.endsWith(".txt"))return "text/plain";'
    if old not in share: raise SystemExit("SecureShareProvider MIME anchor missing")
    share = share.replace(old, 'if(n.endsWith(".zip"))return "application/zip";' + old, 1)
SHARE.write_text(share, encoding="utf-8")

for path, tokens in {
    MAIN: ("Open incident vault", "Permissions & battery", "PIN / password monitoring"),
    VAULT: ("DEFAULT_PAGE_SIZE = 24", "Select all filtered", "Export this range", "VaultExportManager.exportIncidents"),
    EXPORT: ("evidence_manifest.csv", "ZipOutputStream", "VaultCrypto.decryptBytes", "application/zip"),
}.items():
    text = path.read_text(encoding="utf-8")
    for token in tokens:
        if token not in text: raise SystemExit(f"Final compact UI/export invariant missing in {path.name}: {token}")

print("Applied compact OwnerGuard UI, paginated multi-select Incident Vault, and secure ZIP export")
