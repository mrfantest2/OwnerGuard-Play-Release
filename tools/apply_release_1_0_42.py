#!/usr/bin/env python3
"""Promote OwnerGuard recovery export and cellular override to Android 1.0.42."""
from pathlib import Path
import re
import subprocess
import sys

ROOT = Path(__file__).resolve().parents[1]
GRADLE = ROOT / "app" / "build.gradle"
JAVA = ROOT / "app" / "src" / "main" / "java" / "com" / "fantest" / "ownerguard"

for helper in ("fix_native_cloud_render_syntax.py", "fix_credential_event_imports.py"):
    path = ROOT / "tools" / helper
    if path.is_file():
        subprocess.run([sys.executable, str(path)], check=True)

build = GRADLE.read_text(encoding="utf-8")
build = re.sub(r"versionCode\s+\d+", "versionCode 10042", build, count=1)
build = re.sub(r"versionName\s+'[^']+'", "versionName '1.0.42'", build, count=1)
if "versionCode 10042" not in build or "versionName '1.0.42'" not in build:
    raise SystemExit("OwnerGuard 1.0.42 version promotion failed")
GRADLE.write_text(build, encoding="utf-8")

changed = 0
for path in JAVA.glob("*.java"):
    text = path.read_text(encoding="utf-8")
    updated = re.sub(r"OwnerGuard-Android/\d+\.\d+\.\d+", "OwnerGuard-Android/1.0.42", text)
    updated = re.sub(
        r'private static final String APP_VERSION = "\d+\.\d+\.\d+";',
        'private static final String APP_VERSION = "1.0.42";', updated)
    updated = re.sub(
        r'return info\.versionName == null \? "\d+\.\d+\.\d+" : info\.versionName;',
        'return info.versionName == null ? "1.0.42" : info.versionName;', updated)
    updated = re.sub(r'return "1\.0\.\d+";', 'return "1.0.42";', updated)
    updated = updated.replace('MIN_SERVER_VERSION = "1.3.27.4"',
                              'MIN_SERVER_VERSION = "1.3.27.5"')
    if updated != text:
        path.write_text(updated, encoding="utf-8")
        changed += 1

required = {
    "RecoveryCodeExport.java": (
        "ACTION_CREATE_DOCUMENT",
        "Save as TXT file",
        "Share to WhatsApp",
        "com.whatsapp",
        "TXT files and messages are not protected",
    ),
    "MainActivity.java": (
        "Show / save / share recovery code",
        "Force upload now (mobile data allowed)",
        "Use mobile data for this upload?",
        "Automatic future synchronization remains Wi-Fi-only",
        "RecoveryCodeExport.handleActivityResult",
    ),
    "CloudAuthActivity.java": (
        "RecoveryCodeExport.show",
        "RecoveryCodeExport.handleActivityResult",
    ),
    "CloudBackupManager.java": (
        "NetworkType.UNMETERED",
        "NetworkType.CONNECTED",
        "INPUT_ALLOW_METERED_ONCE",
        "One-time upload queued — mobile data allowed",
        "ExistingWorkPolicy.REPLACE",
    ),
    "CloudBackupWorker.java": (
        "getInputData().getBoolean",
        "syncAll(getApplicationContext(), allowMeteredOnce)",
    ),
    "CloudBackupEngine.java": (
        "syncAll(Context context, boolean allowMeteredOnce)",
        "validatedInternetConnected",
        "One-time upload running — mobile data allowed",
        "hasTransport(NetworkCapabilities.TRANSPORT_WIFI)",
    ),
    "CloudAccountManager.java": (
        'MIN_SERVER_VERSION = "1.3.27.5"',
    ),
}
for name, tokens in required.items():
    path = JAVA / name
    if not path.is_file():
        raise SystemExit("OwnerGuard 1.0.42 source is missing: " + name)
    text = path.read_text(encoding="utf-8")
    for token in tokens:
        if token not in text:
            raise SystemExit(f"OwnerGuard 1.0.42 invariant missing in {name}: {token}")

manager = (JAVA / "CloudBackupManager.java").read_text(encoding="utf-8")
if manager.count("NetworkType.UNMETERED") < 2:
    raise SystemExit("Automatic and periodic Cloud synchronization must remain Wi-Fi constrained")
if manager.count("NetworkType.CONNECTED") != 1:
    raise SystemExit("Mobile-data override must be one explicit one-time path")

updater = (JAVA / "AppUpdateManager.java").read_text(encoding="utf-8")
if 'private static final String APP_VERSION = "1.0.42";' not in updater:
    raise SystemExit("AppUpdateManager was not promoted to OwnerGuard 1.0.42")
if not any("OwnerGuard-Android/1.0.42" in p.read_text(encoding="utf-8") for p in JAVA.glob("*.java")):
    raise SystemExit("OwnerGuard 1.0.42 network identity was not applied")

print(f"Promoted OwnerGuard Android to 1.0.42; updated {changed} Java files")
