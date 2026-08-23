#!/usr/bin/env python3
"""Finalize OwnerGuard 1.0.44 as local-first Play release and harden OEM face enrollment."""
from __future__ import annotations

from pathlib import Path
import re

ROOT = Path(__file__).resolve().parents[1]
APP = ROOT / "app" / "src" / "main"
JAVA = APP / "java" / "com" / "fantest" / "ownerguard"
MAIN = JAVA / "MainActivity.java"
FACE = JAVA / "FaceSimilarity.java"
ACCOUNT = JAVA / "CloudAccountManager.java"
BACKUP = JAVA / "CloudBackupManager.java"
AUTH = JAVA / "CloudAuthActivity.java"
MANIFEST = APP / "AndroidManifest.xml"


def method_bounds(text: str, marker: str) -> tuple[int, int] | None:
    start = text.find(marker)
    if start < 0:
        return None
    brace = text.find("{", start)
    if brace < 0:
        return None
    depth = 0
    for i in range(brace, len(text)):
        ch = text[i]
        if ch == "{":
            depth += 1
        elif ch == "}":
            depth -= 1
            if depth == 0:
                return start, i + 1
    return None


def remove_method(text: str, marker: str) -> str:
    bounds = method_bounds(text, marker)
    if not bounds:
        return text
    start, end = bounds
    while end < len(text) and text[end] in " \t\r\n":
        end += 1
    return text[:start] + text[end:]


def insert_early_return(text: str, marker: str, statement: str) -> str:
    start = text.find(marker)
    if start < 0:
        return text
    brace = text.find("{", start)
    if brace < 0:
        return text
    window = text[brace:brace + 220]
    if statement in window:
        return text
    return text[:brace + 1] + "\n        " + statement + text[brace + 1:]


def strip_hosted_domains() -> int:
    changed = 0
    for path in APP.rglob("*"):
        if not path.is_file() or path.suffix.lower() not in {".java", ".kt", ".xml", ".json", ".txt"}:
            continue
        try:
            text = path.read_text(encoding="utf-8")
        except UnicodeDecodeError:
            continue
        updated = re.sub(r'https?://[^"\'\s]*fantest\.win[^"\'\s]*', '', text, flags=re.I)
        updated = re.sub(r'(?i)(?:[a-z0-9-]+\.)*fantest\.win', '', updated)
        if updated != text:
            path.write_text(updated, encoding="utf-8")
            changed += 1
    return changed


# --- Local-only first run and settings ---------------------------------------
main = MAIN.read_text(encoding="utf-8")

# The historical 1.0.41 stack routed first launch through hosted Cloud account
# creation. Replace that gate with a local six-digit PIN flow.
main = main.replace(
    "launchAutomaticVaultSetup(CloudAuthActivity.MODE_CREATE)",
    "showLocalPinSetup()",
)
main = main.replace(
    "launchAutomaticVaultSetup(CloudAuthActivity.MODE_RESTORE)",
    "showLocalPinSetup()",
)

# Remove calls that can re-impose a hosted-account requirement for an existing
# local vault after app foreground/resume.
main = re.sub(r'(?m)^.*ensureCloudAccountLinked\([^\n]*\);\s*$', '', main)
main = re.sub(r'(?m)^.*CloudAccountManager\.refresh\([^\n]*\);\s*$', '', main)

# Remove the hosted Cloud section(s) from Vault & settings while preserving the
# Play-managed update and OwnerGuard Pro sections that follow.
bounds = method_bounds(main, "private void showVaultTab()") or method_bounds(main, "private void showVaultTab(){")
if bounds:
    ms, me = bounds
    section = main[ms:me]
    starts = [
        section.find('sectionTitle("Cloud account")'),
        section.find('sectionTitle("Automatic Cloud backup")'),
        section.find('sectionTitle("Encrypted cloud backup")'),
    ]
    starts = [p for p in starts if p >= 0]
    if starts:
        start = min(starts)
        start = section.rfind("\n", 0, start) + 1
        ends = [
            section.find('sectionTitle("App updates")', start),
            section.find('sectionTitle("Secure app updates")', start),
            section.find('sectionTitle("OwnerGuard Pro")', start),
        ]
        ends = [p for p in ends if p >= 0]
        if not ends:
            raise SystemExit("Could not locate a safe end anchor after hosted Cloud settings")
        end = min(ends)
        end = section.rfind("\n", 0, end) + 1
        section = section[:start] + section[end:]
        main = main[:ms] + section + main[me:]

# Remove legacy hosted-cloud helper methods from the activity if historical
# transforms added them. They are no longer part of the Play release UI.
for marker in (
    "private void showCloudAccountDialog(",
    "private void showCloudSetup(",
    "private void confirmEscrowMigration(",
    "private void showCloudRecoveryKey(",
    "private void showImportCloudKey(",
    "private void launchAutomaticVaultSetup(",
    "private void ensureCloudAccountLinked(",
    "private void forceCloudUpload(",
    "private void showAccountRecoveryCode(",
):
    main = remove_method(main, marker)

# Armed protection must never start the retired hosted backup path.
main = re.sub(
    r'if\s*\(CloudBackupManager\.enabled\(this\)\s*&&\s*CloudBackupManager\.configured\(this\)\)\s*CloudBackupManager\.backupAll\(this\);',
    '',
    main,
)

# Remove any remaining single-line status widget added by the 1.0.43 hosted
# Cloud pipeline. The Pro backup UI remains separate.
main = re.sub(r'(?m)^.*new CloudSyncStatusView\(this\).*\n?', '', main)

# Add a dedicated local first-run PIN creator if the historical stack does not
# already leave one reachable. This path has no username, server, or network.
if "private void showLocalPinSetup()" not in main:
    anchor = "    private void requireAuthentication()"
    if anchor not in main:
        anchor = "    private void buildPinScreen()"
    if anchor not in main:
        raise SystemExit("Could not find MainActivity anchor for local PIN setup")
    local_pin = '''    private void showLocalPinSetup(){
        authenticationUiVisible=true;
        LinearLayout box=vertical(20);
        TextView note=text("Create an exact 6-digit OwnerGuard PIN. This protects the encrypted local vault and does not require an online account.",15);
        EditText p1=secretField("New 6-digit PIN"); p1.setInputType(InputType.TYPE_CLASS_NUMBER|InputType.TYPE_NUMBER_VARIATION_PASSWORD);
        EditText p2=secretField("Confirm 6-digit PIN"); p2.setInputType(InputType.TYPE_CLASS_NUMBER|InputType.TYPE_NUMBER_VARIATION_PASSWORD);
        box.addView(note);box.addView(p1,buttonParams());box.addView(p2,buttonParams());
        AlertDialog d=new AlertDialog.Builder(this).setTitle("Secure OwnerGuard").setView(box).setCancelable(false).setPositiveButton("Save 6-digit PIN",null).create();
        d.setOnShowListener(v->d.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(x->{
            String a=p1.getText().toString(),b=p2.getText().toString();
            if(!a.matches("\\\\d{6}")||!a.equals(b)){Toast.makeText(this,"Enter matching exact 6-digit PINs",Toast.LENGTH_LONG).show();return;}
            try{PinStore.setPin(this,a);AuthSession.unlock();d.dismiss();buildDashboard();}catch(Exception e){Toast.makeText(this,"Could not save 6-digit PIN",Toast.LENGTH_LONG).show();}
        }));d.show();
    }

'''
    main = main.replace(anchor, local_pin + anchor, 1)

# If an older local setup method still carries hosted-account wording, make its
# copy explicitly local without changing its PIN security behavior.
main = main.replace(
    "Create your username and six-digit OwnerGuard PIN. Cloud backup is enabled automatically.",
    "Create an exact 6-digit OwnerGuard PIN. Online backup is optional through OwnerGuard Pro.",
)
main = main.replace(
    "Cloud backup is enabled automatically.",
    "Online backup is optional through OwnerGuard Pro.",
)

# Final MainActivity may not depend on the retired hosted-cloud controllers.
if "CloudAccountManager." in main or "CloudBackupManager." in main or "launchAutomaticVaultSetup(" in main or "ensureCloudAccountLinked" in main:
    leftovers = [line.strip() for line in main.splitlines() if any(t in line for t in (
        "CloudAccountManager.", "CloudBackupManager.", "launchAutomaticVaultSetup(", "ensureCloudAccountLinked"
    ))]
    raise SystemExit("Hosted Cloud reference remains in MainActivity: " + " | ".join(leftovers[:6]))
if "Create an exact 6-digit OwnerGuard PIN" not in main:
    raise SystemExit("Local-only six-digit PIN onboarding marker is missing")
MAIN.write_text(main, encoding="utf-8")


# --- Retire hosted account entry point ---------------------------------------
# Keep the class/constant surface for old internal references, but make it a
# local redirect with no registration fields or network behavior.
AUTH.write_text('''package com.fantest.ownerguard;

import android.content.Intent;
import android.os.Bundle;
import android.widget.Toast;

public class CloudAuthActivity extends SecureActivity {
    public static final int MODE_CREATE = 1;
    public static final int MODE_RESTORE = 2;

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        Toast.makeText(this, "Online OwnerGuard accounts are not required in this Play release.", Toast.LENGTH_LONG).show();
        startActivity(new Intent(this, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP));
        finish();
    }
}
''', encoding="utf-8")

# Mark hosted-account code disabled. It is no longer reachable from the release
# UI; the marker lets the static contract prevent regressions.
account = ACCOUNT.read_text(encoding="utf-8")
if "HOSTED_ACCOUNT_DISABLED = true" not in account:
    class_anchor = "final class CloudAccountManager {"
    if class_anchor not in account:
        raise SystemExit("CloudAccountManager class anchor missing")
    account = account.replace(class_anchor, class_anchor + "\n    private static final boolean HOSTED_ACCOUNT_DISABLED = true;", 1)
ACCOUNT.write_text(account, encoding="utf-8")

# Disable all automatic legacy hosted-backup entry points while preserving the
# historical method surface so older internal classes still compile.
backup = BACKUP.read_text(encoding="utf-8")
if "LEGACY_HOSTED_CLOUD_DISABLED = true" not in backup:
    class_anchor = "final class CloudBackupManager {"
    if class_anchor not in backup:
        raise SystemExit("CloudBackupManager class anchor missing")
    backup = backup.replace(class_anchor, class_anchor + "\n    private static final boolean LEGACY_HOSTED_CLOUD_DISABLED = true;", 1)

for marker, statement in (
    ("static boolean enabled(Context", "if (LEGACY_HOSTED_CLOUD_DISABLED) return false;"),
    ("static boolean configured(Context", "if (LEGACY_HOSTED_CLOUD_DISABLED) return false;"),
    ("static void initialize(Context", "if (LEGACY_HOSTED_CLOUD_DISABLED) return;"),
    ("static void onAppForeground(Context", "if (LEGACY_HOSTED_CLOUD_DISABLED) return;"),
    ("static void backupEvent(Context", "if (LEGACY_HOSTED_CLOUD_DISABLED) return;"),
    ("static void backupAll(Context", "if (LEGACY_HOSTED_CLOUD_DISABLED) return;"),
    ("static void retryPending(Context", "if (LEGACY_HOSTED_CLOUD_DISABLED) return;"),
    ("static void force", "if (LEGACY_HOSTED_CLOUD_DISABLED) return;"),
):
    backup = insert_early_return(backup, marker, statement)
BACKUP.write_text(backup, encoding="utf-8")


# --- OnePlus/OEM-safe enrollment orientation fallback -----------------------
face = FACE.read_text(encoding="utf-8")
if "ENROLLMENT_ORIENTATIONS = new int[]{0, 90, 270, 180}" not in face:
    anchor = "    static final int MAX_SAMPLES = 5;"
    if anchor not in face:
        raise SystemExit("FaceSimilarity MAX_SAMPLES anchor missing")
    face = face.replace(
        anchor,
        anchor + "\n    private static final int[] ENROLLMENT_ORIENTATIONS = new int[]{0, 90, 270, 180};",
        1,
    )

old = "        DescriptorData data = descriptor(bitmap, true);"
if "descriptorEnrollmentVariants(bitmap)" not in face:
    if old not in face:
        raise SystemExit("FaceSimilarity guided enrollment descriptor anchor missing")
    face = face.replace(old, "        DescriptorData data = descriptorEnrollmentVariants(bitmap);", 1)

if "private static DescriptorData descriptorEnrollmentVariants(Bitmap source)" not in face:
    anchor = "    static boolean enroll(Context c, Bitmap bitmap) {"
    if anchor not in face:
        raise SystemExit("FaceSimilarity enroll method anchor missing")
    helper = '''    private static DescriptorData descriptorEnrollmentVariants(Bitmap source) {
        DescriptorData firstFailure = null;
        for (int degrees : ENROLLMENT_ORIENTATIONS) {
            Bitmap oriented = degrees == 0 ? source : rotateCopy(source, degrees);
            if (oriented == null) continue;
            DescriptorData candidate = descriptor(oriented, true);
            if (degrees != 0 && oriented != source && !oriented.isRecycled()) oriented.recycle();
            if (candidate.bytes != null) return candidate;
            if (firstFailure == null) firstFailure = candidate;
        }
        return firstFailure != null
                ? firstFailure
                : new DescriptorData(null, "No clear face detected. Keep your full face inside the guide and look toward the camera.");
    }

'''
    face = face.replace(anchor, helper + anchor, 1)

for token in (
    "ENROLLMENT_ORIENTATIONS = new int[]{0, 90, 270, 180}",
    "descriptorEnrollmentVariants(bitmap)",
    "for (int degrees : ENROLLMENT_ORIENTATIONS)",
):
    if token not in face:
        raise SystemExit("OEM-safe enrollment invariant missing: " + token)
FACE.write_text(face, encoding="utf-8")


# Remove any hard-coded fantest.win literals left by historical Cloud/update
# transforms. Play release operation must not depend on that domain.
changed_domains = strip_hosted_domains()

# Static final assertions before the release contract runs.
all_android = "\n".join(
    p.read_text(encoding="utf-8", errors="ignore")
    for p in APP.rglob("*")
    if p.is_file() and p.suffix.lower() in {".java", ".kt", ".xml", ".json", ".txt"}
)
for forbidden in (
    "fantest.win",
    "Create your username and six-digit OwnerGuard PIN",
    "Cloud backup is enabled automatically",
):
    if forbidden in all_android:
        raise SystemExit("Play local-only cleanup failed; forbidden token remains: " + forbidden)

print(
    "Applied OwnerGuard Play local-only onboarding and OEM-safe face enrollment; "
    f"removed hosted-domain literals from {changed_domains} file(s)"
)
