#!/usr/bin/env python3
"""Promote OwnerGuard upload-pipeline repair to Android 1.0.43."""
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
build = re.sub(r"versionCode\s+\d+", "versionCode 10043", build, count=1)
build = re.sub(r"versionName\s+'[^']+'", "versionName '1.0.43'", build, count=1)
if "versionCode 10043" not in build or "versionName '1.0.43'" not in build:
    raise SystemExit("OwnerGuard 1.0.43 version promotion failed")
GRADLE.write_text(build, encoding="utf-8")

changed = 0
for path in JAVA.glob("*.java"):
    text = path.read_text(encoding="utf-8")
    updated = re.sub(r"OwnerGuard-Android/\d+\.\d+\.\d+", "OwnerGuard-Android/1.0.43", text)
    updated = re.sub(
        r'private static final String APP_VERSION = "\d+\.\d+\.\d+";',
        'private static final String APP_VERSION = "1.0.43";', updated)
    updated = re.sub(
        r'private static final String MIN_SERVER_VERSION = "\d+\.\d+\.\d+\.\d+";',
        'private static final String MIN_SERVER_VERSION = "1.3.27.6";', updated)
    updated = re.sub(
        r'return info\.versionName == null \? "\d+\.\d+\.\d+" : info\.versionName;',
        'return info.versionName == null ? "1.0.43" : info.versionName;', updated)
    updated = re.sub(r'return "1\.0\.\d+";', 'return "1.0.43";', updated)
    if updated != text:
        path.write_text(updated, encoding="utf-8")
        changed += 1

# apply_forced_update_gate.py historically emitted a raw multiline Java string.
# Normalize it here after the complete patch stack so clean reconstruction compiles.
updater_path = JAVA / "AppUpdateManager.java"
updater = updater_path.read_text(encoding="utf-8")
malformed_dialog = '.setMessage(message.toString() + "\n\nNormal controls remain locked until installation. Armed protection and encrypted photo/video capture continue in the background.")'
correct_dialog = '.setMessage(message.toString() + "\\n\\nNormal controls remain locked until installation. Armed protection and encrypted photo/video capture continue in the background.")'
if malformed_dialog in updater:
    updater = updater.replace(malformed_dialog, correct_dialog, 1)
    updater_path.write_text(updater, encoding="utf-8")
elif correct_dialog not in updater:
    raise SystemExit("OwnerGuard 1.0.43 mandatory-update dialog escape anchor was not found")

required = {
    "CloudBackupManager.java": ("APPEND_OR_REPLACE", "Resuming Cloud upload", "Uploading now"),
    "CloudBackupEngine.java": ("session_rebound", "Binding recovery encryption", "markUploadingObject"),
    "CloudBackupWorker.java": ("syncAll(getApplicationContext(), allowMeteredOnce)", "markRetry"),
    "CloudSyncStatusView.java": ("postDelayed(this, 1000L)", "statusSummary"),
    "MainActivity.java": ("new CloudSyncStatusView(this)",),
    "CloudAccountManager.java": ('MIN_SERVER_VERSION = "1.3.27.6"',),
}
for name, tokens in required.items():
    path = JAVA / name
    if not path.is_file():
        raise SystemExit("OwnerGuard 1.0.43 source is missing: " + name)
    text = path.read_text(encoding="utf-8")
    for token in tokens:
        if token not in text:
            raise SystemExit(f"OwnerGuard 1.0.43 invariant missing in {name}: {token}")

if 'private static final String APP_VERSION = "1.0.43";' not in updater:
    raise SystemExit("AppUpdateManager was not promoted to OwnerGuard 1.0.43")
if not any("OwnerGuard-Android/1.0.43" in p.read_text(encoding="utf-8") for p in JAVA.glob("*.java")):
    raise SystemExit("OwnerGuard 1.0.43 network identity was not applied")

print(f"Promoted OwnerGuard Android to 1.0.43; updated {changed} Java files")
