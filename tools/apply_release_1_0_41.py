#!/usr/bin/env python3
"""Promote simplified automatic OwnerGuard vault onboarding to Android 1.0.41."""
from pathlib import Path
import re
import subprocess
import sys

ROOT = Path(__file__).resolve().parents[1]
GRADLE = ROOT / "app" / "build.gradle"
JAVA = ROOT / "app" / "src" / "main" / "java" / "com" / "fantest" / "ownerguard"
MANIFEST = ROOT / "app" / "src" / "main" / "AndroidManifest.xml"

for helper in ("fix_native_cloud_render_syntax.py", "fix_credential_event_imports.py"):
    path = ROOT / "tools" / helper
    if path.is_file():
        subprocess.run([sys.executable, str(path)], check=True)

build = GRADLE.read_text(encoding="utf-8")
build = re.sub(r"versionCode\s+\d+", "versionCode 10041", build, count=1)
build = re.sub(r"versionName\s+'[^']+'", "versionName '1.0.41'", build, count=1)
if "versionCode 10041" not in build or "versionName '1.0.41'" not in build:
    raise SystemExit("OwnerGuard 1.0.41 version promotion failed")
GRADLE.write_text(build, encoding="utf-8")

changed = 0
for path in JAVA.glob("*.java"):
    text = path.read_text(encoding="utf-8")
    updated = re.sub(r"OwnerGuard-Android/\d+\.\d+\.\d+", "OwnerGuard-Android/1.0.41", text)
    updated = re.sub(
        r'private static final String APP_VERSION = "\d+\.\d+\.\d+";',
        'private static final String APP_VERSION = "1.0.41";', updated)
    updated = re.sub(
        r'return info\.versionName == null \? "\d+\.\d+\.\d+" : info\.versionName;',
        'return info.versionName == null ? "1.0.41" : info.versionName;', updated)
    updated = re.sub(r'return "1\.0\.\d+";', 'return "1.0.41";', updated)
    if updated != text:
        path.write_text(updated, encoding="utf-8")
        changed += 1

required_files = (
    "CloudAuthActivity.java", "CloudLoginRecoveryStore.java", "CloudBackupWorker.java",
    "CloudBackupEngine.java", "CloudBackupManager.java", "CloudAccountManager.java",
)
for name in required_files:
    if not (JAVA / name).is_file():
        raise SystemExit("OwnerGuard 1.0.41 source is missing: " + name)

account = (JAVA / "CloudAccountManager.java").read_text(encoding="utf-8")
recovery = (JAVA / "CloudLoginRecoveryStore.java").read_text(encoding="utf-8")
manager = (JAVA / "CloudBackupManager.java").read_text(encoding="utf-8")
engine = (JAVA / "CloudBackupEngine.java").read_text(encoding="utf-8")
auth = (JAVA / "CloudAuthActivity.java").read_text(encoding="utf-8")
main = (JAVA / "MainActivity.java").read_text(encoding="utf-8")
app = (JAVA / "OwnerGuardApp.java").read_text(encoding="utf-8")
manifest = MANIFEST.read_text(encoding="utf-8")

checks = {
    "CloudAccountManager.java": (account, (
        'MIN_SERVER_VERSION = "1.3.27.4"',
        "Username plus recovery-code Cloud identity",
        'form("username", cleanUser)',
        'form("username", cleanUser, "recovery_code", code)',
        "CloudLoginRecoveryStore.save",
    )),
    "CloudLoginRecoveryStore.java": (recovery, (
        "Device-encrypted copy",
        "OG-[A-HJ-NP-Z2-9]",
        "VaultCrypto.encryptBytes",
    )),
    "CloudBackupManager.java": (manager, (
        "NetworkType.UNMETERED",
        "ExistingPeriodicWorkPolicy.UPDATE",
        "Automatic Cloud sync queued — Wi-Fi only",
        "prepareAutomaticMigration",
    )),
    "CloudBackupEngine.java": (engine, (
        "TRANSPORT_WIFI",
        "X-OwnerGuard-Migration",
        "Cloud session is invalid or disabled",
    )),
    "CloudAuthActivity.java": (auth, (
        "Create your username and six-digit OwnerGuard PIN",
        "Restore an existing account",
        "Recovery code — OG-XXXX-XXXX-XXXX-XXXX-XXXX",
        "The six-digit PIN stays on this phone",
    )),
    "MainActivity.java": (main, (
        "launchAutomaticVaultSetup(CloudAuthActivity.MODE_CREATE)",
        "Automatic Cloud backup",
        "Show account recovery code",
        "ensureCloudAccountLinked",
    )),
    "OwnerGuardApp.java": (app, (
        "CloudBackupManager.initialize(this)",
        "CloudBackupManager.onAppForeground(this)",
    )),
    "AndroidManifest.xml": (manifest, (
        'android:name=".CloudAuthActivity"',
        'android:name=".CloudBackupService"',
    )),
}
for label, (text, tokens) in checks.items():
    for token in tokens:
        if token not in text:
            raise SystemExit(f"OwnerGuard 1.0.41 invariant missing in {label}: {token}")

for forbidden in (
    'sectionTitle("Cloud workspace")',
    'sectionTitle("Administration")',
    'openNativeCloudFromSetup(',
    '"All incidents"',
    '"Vault owners"',
    '"Audit log"',
    '"System status"',
    '"Update management"',
    '"Sign out of Cloud Vault"',
    '"Create cloud account or log in"',
    '"Use local vault only"',
    'Password — at least 10 characters',
):
    if forbidden in main + auth:
        raise SystemExit("Removed Android Cloud workspace/auth behavior returned: " + forbidden)

updater = (JAVA / "AppUpdateManager.java").read_text(encoding="utf-8")
if 'private static final String APP_VERSION = "1.0.41";' not in updater:
    raise SystemExit("AppUpdateManager was not promoted to OwnerGuard 1.0.41")
if not any("OwnerGuard-Android/1.0.41" in p.read_text(encoding="utf-8") for p in JAVA.glob("*.java")):
    raise SystemExit("OwnerGuard 1.0.41 network identity was not applied")

print(f"Promoted OwnerGuard Android to 1.0.41; updated {changed} Java files")
