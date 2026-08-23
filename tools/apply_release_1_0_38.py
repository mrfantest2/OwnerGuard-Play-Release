#!/usr/bin/env python3
"""Promote the fully patched OwnerGuard Android source to 1.0.38."""
from pathlib import Path
import re
import subprocess
import sys

ROOT = Path(__file__).resolve().parents[1]
GRADLE = ROOT / "app" / "build.gradle"
JAVA = ROOT / "app" / "src" / "main" / "java" / "com" / "fantest" / "ownerguard"
MANIFEST = ROOT / "app" / "src" / "main" / "AndroidManifest.xml"

for fixer in (
    "fix_native_cloud_render_syntax.py",
    "fix_credential_event_imports.py",
):
    subprocess.run(
        [sys.executable, str(ROOT / "tools" / fixer)],
        check=True,
    )

build = GRADLE.read_text(encoding="utf-8")
build = re.sub(r"versionCode\s+\d+", "versionCode 10038", build, count=1)
build = re.sub(r"versionName\s+'[^']+'", "versionName '1.0.38'", build, count=1)
if "versionCode 10038" not in build or "versionName '1.0.38'" not in build:
    raise SystemExit("OwnerGuard 1.0.38 version promotion failed")
GRADLE.write_text(build, encoding="utf-8")

changed = 0
for path in JAVA.glob("*.java"):
    text = path.read_text(encoding="utf-8")
    updated = re.sub(r"OwnerGuard-Android/\d+\.\d+\.\d+", "OwnerGuard-Android/1.0.38", text)
    updated = re.sub(
        r'private static final String APP_VERSION = "\d+\.\d+\.\d+";',
        'private static final String APP_VERSION = "1.0.38";',
        updated,
    )
    updated = re.sub(
        r'return info\.versionName == null \? "\d+\.\d+\.\d+" : info\.versionName;',
        'return info.versionName == null ? "1.0.38" : info.versionName;',
        updated,
    )
    updated = re.sub(r'return "1\.0\.\d+";', 'return "1.0.38";', updated)
    if updated != text:
        path.write_text(updated, encoding="utf-8")
        changed += 1

updater = (JAVA / "AppUpdateManager.java").read_text(encoding="utf-8")
if 'private static final String APP_VERSION = "1.0.38";' not in updater:
    raise SystemExit("AppUpdateManager was not promoted to OwnerGuard 1.0.38")
if not any("OwnerGuard-Android/1.0.38" in p.read_text(encoding="utf-8") for p in JAVA.glob("*.java")):
    raise SystemExit("OwnerGuard 1.0.38 network identity was not applied")

main = (JAVA / "MainActivity.java").read_text(encoding="utf-8")
admin = (JAVA / "TheftAdminReceiver.java").read_text(encoding="utf-8")
service = (JAVA / "ProtectionService.java").read_text(encoding="utf-8")
unlock = (JAVA / "UnlockEventReceiver.java").read_text(encoding="utf-8")
manifest = MANIFEST.read_text(encoding="utf-8")
for required in (
    "ensureDefaultCapturePolicy();",
    "credential_capture_defaults_initialized",
    "Capture photos and video after a failed unlock attempt",
    "Capture photos and video after a successful unlock",
    "android.content.SharedPreferences settings=",
    "android.os.Build.VERSION.SDK_INT>=26",
):
    if required not in main:
        raise SystemExit("Credential capture controls missing from release: " + required)
for required in (
    "onPasswordFailed",
    "onPasswordSucceeded",
    "ACTION_SUCCESSFUL_CREDENTIAL",
    "EXTRA_SUCCESS_AT",
):
    if required not in admin + service:
        raise SystemExit("Credential event trigger missing from release: " + required)
for required in (
    "DUPLICATE_SUCCESS_CALLBACK_MS",
    "last_success_capture_request_at",
    "requestSuccessfulUnlockCapture",
    "three front-camera photos and a 5-second video",
):
    if required not in service:
        raise SystemExit("Successful unlock capture pipeline incomplete: " + required)
if "UnlockEventReceiver" not in unlock or '.UnlockEventReceiver' not in manifest:
    raise SystemExit("USER_PRESENT fallback receiver is missing")
if "android.intent.action.USER_PRESENT" not in manifest:
    raise SystemExit("USER_PRESENT manifest action is missing")

native = (JAVA / "NativeCloudActivity.java").read_text(encoding="utf-8")
for invalid in (
    'incident.optLong("bytes"), top(12));',
    'system.optString("php_version", "Unknown"), top(10));',
    'system.optInt("audit_events"), top(10));',
    'update.optString("release_notes", ""), top(10));',
):
    if invalid in native:
        raise SystemExit("Native Cloud render syntax was not normalized: " + invalid)

print(f"Promoted OwnerGuard Android to 1.0.38; updated {changed} Java files")
