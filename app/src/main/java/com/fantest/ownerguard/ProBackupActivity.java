package com.fantest.ownerguard;

import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

/** OwnerGuard Pro lifetime purchase and portable Google Drive/cloud backup workspace. */
public final class ProBackupActivity extends SecureActivity implements ProEntitlement.Listener {
    private static final int REQ_TREE = 4401;
    private static final int REQ_BACKUP = 4402;
    private ProEntitlement pro;

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        pro = ProEntitlement.get(this);
        pro.addListener(this);
        build();
        pro.connectAndRefresh();
    }

    @Override protected void onDestroy() {
        if (pro != null) pro.removeListener(this);
        super.onDestroy();
    }

    @Override public void onChanged(boolean active, String status) {
        runOnUiThread(() -> build());
    }

    private void build() {
        boolean active = ProEntitlement.cached(this);
        ScrollView scroll = new ScrollView(this);
        scroll.setBackgroundColor(Color.parseColor("#08111F"));
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(18), dp(22), dp(18), dp(30));
        scroll.addView(root, new ScrollView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        TextView title = text("OwnerGuard Pro", 28, Color.WHITE);
        root.addView(title);
        root.addView(card(ProEntitlement.cachedStatus(this)), margin(10));

        if (!active) {
            root.addView(card("Keep OwnerGuard's core protection free. Pro unlocks portable encrypted backups to Google Drive or another Android cloud document provider, automatic daily backup, and disaster-recovery restore."), margin(12));
            Button unlock = primary("Unlock Pro lifetime", v -> pro.launchPurchase(this));
            root.addView(unlock, margin(14));
            root.addView(secondary("Restore purchase", v -> pro.restorePurchases()), margin(10));
            root.addView(card("The purchase is a one-time non-consumable Google Play product. No advertising is added to OwnerGuard."), margin(14));
        } else {
            DriveBackupManager.syncSchedule(this);
            root.addView(section("Google Drive / cloud backup"), margin(18));
            root.addView(card(DriveBackupManager.destinationSummary(this)), margin(8));
            root.addView(card(DriveBackupManager.lastBackupSummary(this)), margin(8));
            root.addView(primary(DriveBackupManager.configured(this) ? "Change backup folder" : "Choose Google Drive backup folder", v -> chooseTree()), margin(12));
            root.addView(secondary("Back up now", v -> backupNow()), margin(10));

            CheckBox automatic = new CheckBox(this);
            automatic.setText("Automatic encrypted backup every day");
            automatic.setTextColor(Color.parseColor("#E2E8F0"));
            automatic.setChecked(DriveBackupManager.automatic(this));
            automatic.setOnCheckedChangeListener((b, checked) -> DriveBackupManager.setAutomatic(this, checked));
            root.addView(automatic, margin(12));
            root.addView(card("OwnerGuard keeps the newest seven automatic backup files when the selected provider supports deletion. Backups are encrypted before they leave the app."), margin(8));

            root.addView(section("Disaster recovery"), margin(22));
            root.addView(card("For a replacement phone: restore your Google Play purchase, import the OwnerGuard recovery key, then select the .ogb backup. OwnerGuard re-encrypts every restored incident with the new phone's Android Keystore key."), margin(8));
            root.addView(secondary("Show / copy recovery key", v -> showRecoveryKey()), margin(10));
            root.addView(secondary("Import recovery key", v -> importRecoveryKey()), margin(10));
            root.addView(primary("Restore an OwnerGuard .ogb backup", v -> chooseBackup()), margin(10));
        }

        root.addView(secondary("Back", v -> finish()), margin(22));
        setContentView(scroll);
    }

    private void chooseTree() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION | Intent.FLAG_GRANT_PREFIX_URI_PERMISSION);
        startActivityForResult(intent, REQ_TREE);
    }

    private void chooseBackup() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("application/octet-stream");
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        startActivityForResult(intent, REQ_BACKUP);
    }

    private void backupNow() {
        if (!DriveBackupManager.configured(this)) { chooseTree(); return; }
        Toast.makeText(this, "Creating encrypted Pro backup…", Toast.LENGTH_SHORT).show();
        new Thread(() -> {
            try {
                DriveBackupManager.Result result = DriveBackupManager.backupNow(this);
                runOnUiThread(() -> { Toast.makeText(this, "Backed up " + result.files + " encrypted vault item(s)", Toast.LENGTH_LONG).show(); build(); });
            } catch (Exception e) {
                runOnUiThread(() -> { Toast.makeText(this, "Backup failed: " + safe(e.getMessage()), Toast.LENGTH_LONG).show(); build(); });
            }
        }, "OwnerGuardDriveBackupNow").start();
    }

    private void restore(Uri uri) {
        Toast.makeText(this, "Validating and restoring encrypted backup…", Toast.LENGTH_SHORT).show();
        new Thread(() -> {
            try {
                DriveBackupManager.Result result = DriveBackupManager.restore(this, uri);
                runOnUiThread(() -> { Toast.makeText(this, "Restored " + result.files + " vault item(s)", Toast.LENGTH_LONG).show(); build(); });
            } catch (Exception e) {
                runOnUiThread(() -> Toast.makeText(this, "Restore failed: " + safe(e.getMessage()) + ". If this is a replacement phone, import the original recovery key first.", Toast.LENGTH_LONG).show());
            }
        }, "OwnerGuardDriveRestore").start();
    }

    @Override protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (resultCode != RESULT_OK || data == null || data.getData() == null) return;
        Uri uri = data.getData();
        if (requestCode == REQ_TREE) {
            try {
                int takeFlags = data.getFlags() & (Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
                getContentResolver().takePersistableUriPermission(uri, takeFlags);
                DriveBackupManager.prefs(this).edit().putString("tree_uri", uri.toString()).putString("last_error", "").apply();
                Toast.makeText(this, "Backup folder connected", Toast.LENGTH_LONG).show();
                build();
            } catch (Exception e) {
                Toast.makeText(this, "Could not keep access to this folder: " + safe(e.getMessage()), Toast.LENGTH_LONG).show();
            }
        } else if (requestCode == REQ_BACKUP) {
            restore(uri);
        }
    }

    private void showRecoveryKey() {
        try {
            String key = CloudCrypto.recoveryKey(this);
            TextView value = text(key, 16, Color.WHITE);
            value.setTextIsSelectable(true);
            value.setPadding(dp(12), dp(12), dp(12), dp(12));
            value.setBackground(round("#15243D"));
            new AlertDialog.Builder(this)
                    .setTitle("OwnerGuard recovery key")
                    .setMessage("Store this separately from the backup. Anyone with both the backup and this recovery key can restore the portable vault contents.")
                    .setView(value)
                    .setPositiveButton("Copy", (d,w) -> {
                        ClipboardManager cm = (ClipboardManager)getSystemService(Context.CLIPBOARD_SERVICE);
                        cm.setPrimaryClip(ClipData.newPlainText("OwnerGuard recovery key", key));
                        Toast.makeText(this, "Recovery key copied", Toast.LENGTH_LONG).show();
                    })
                    .setNegativeButton("Close", null).show();
        } catch (Exception e) {
            Toast.makeText(this, "Could not load recovery key", Toast.LENGTH_LONG).show();
        }
    }

    private void importRecoveryKey() {
        EditText key = new EditText(this);
        key.setHint("43-character OwnerGuard recovery key");
        key.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        new AlertDialog.Builder(this)
                .setTitle("Import recovery key")
                .setMessage("Use the recovery key saved from the phone that created the backup.")
                .setView(key)
                .setPositiveButton("Import", (d,w) -> {
                    try {
                        CloudCrypto.importRecoveryKey(this, key.getText().toString());
                        Toast.makeText(this, "Recovery key imported", Toast.LENGTH_LONG).show();
                    } catch (Exception e) {
                        Toast.makeText(this, "Invalid recovery key", Toast.LENGTH_LONG).show();
                    }
                })
                .setNegativeButton("Cancel", null).show();
    }

    private TextView section(String value) { return text(value, 21, Color.WHITE); }

    private TextView card(String value) {
        TextView v = text(value, 14, Color.parseColor("#CBD5E1"));
        v.setPadding(dp(14), dp(14), dp(14), dp(14));
        v.setBackground(round("#12213A"));
        return v;
    }

    private Button primary(String label, View.OnClickListener listener) {
        Button b = button(label, listener);
        b.setBackground(round("#087EBD"));
        b.setTextColor(Color.WHITE);
        return b;
    }

    private Button secondary(String label, View.OnClickListener listener) {
        Button b = button(label, listener);
        b.setBackground(round("#172A47"));
        b.setTextColor(Color.WHITE);
        return b;
    }

    private Button button(String label, View.OnClickListener listener) {
        Button b = new Button(this);
        b.setText(label);
        b.setTextSize(15);
        b.setAllCaps(false);
        b.setMinHeight(dp(50));
        b.setGravity(Gravity.CENTER);
        b.setOnClickListener(listener);
        return b;
    }

    private TextView text(String value, int sp, int color) {
        TextView t = new TextView(this);
        t.setText(value);
        t.setTextSize(sp);
        t.setTextColor(color);
        return t;
    }

    private LinearLayout.LayoutParams margin(int top) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        p.topMargin = dp(top);
        return p;
    }

    private GradientDrawable round(String color) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(Color.parseColor(color));
        d.setCornerRadius(dp(14));
        return d;
    }

    private int dp(int value) { return Math.round(value * getResources().getDisplayMetrics().density); }
    private static String safe(String value) { return value == null || value.trim().isEmpty() ? "Unknown error" : value.trim(); }
}
