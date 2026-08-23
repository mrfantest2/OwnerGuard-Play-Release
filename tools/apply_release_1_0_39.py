#!/usr/bin/env python3
"""Promote the fully patched OwnerGuard Android source to 1.0.39."""
from pathlib import Path
import re
import subprocess
import sys

ROOT = Path(__file__).resolve().parents[1]
GRADLE = ROOT / "app" / "build.gradle"
JAVA = ROOT / "app" / "src" / "main" / "java" / "com" / "fantest" / "ownerguard"
MANIFEST = ROOT / "app" / "src" / "main" / "AndroidManifest.xml"

for fixer in (
    "fix_credential_event_imports.py",
    "fix_native_cloud_render_syntax.py",
):
    subprocess.run([sys.executable, str(ROOT / "tools" / fixer)], check=True)

build = GRADLE.read_text(encoding="utf-8")
build = re.sub(r"versionCode\s+\d+", "versionCode 10039", build, count=1)
build = re.sub(r"versionName\s+'[^']+'", "versionName '1.0.39'", build, count=1)
if "versionCode 10039" not in build or "versionName '1.0.39'" not in build:
    raise SystemExit("OwnerGuard 1.0.39 version promotion failed")
GRADLE.write_text(build, encoding="utf-8")

changed = 0
for path in JAVA.glob("*.java"):
    text = path.read_text(encoding="utf-8")
    updated = re.sub(r"OwnerGuard-Android/\d+\.\d+\.\d+", "OwnerGuard-Android/1.0.39", text)
    updated = re.sub(
        r'private static final String APP_VERSION = "\d+\.\d+\.\d+";',
        'private static final String APP_VERSION = "1.0.39";',
        updated,
    )
    updated = re.sub(
        r'return info\.versionName == null \? "\d+\.\d+\.\d+" : info\.versionName;',
        'return info.versionName == null ? "1.0.39" : info.versionName;',
        updated,
    )
    updated = re.sub(r'return "1\.0\.\d+";', 'return "1.0.39";', updated)
    if updated != text:
        path.write_text(updated, encoding="utf-8")
        changed += 1

updater = (JAVA / "AppUpdateManager.java").read_text(encoding="utf-8")
if 'private static final String APP_VERSION = "1.0.39";' not in updater:
    raise SystemExit("AppUpdateManager was not promoted to OwnerGuard 1.0.39")
if not any("OwnerGuard-Android/1.0.39" in p.read_text(encoding="utf-8") for p in JAVA.glob("*.java")):
    raise SystemExit("OwnerGuard 1.0.39 network identity was not applied")

requirements = (JAVA / "OwnerGuardRequirements.java").read_text(encoding="utf-8")
face = (JAVA / "FaceSimilarity.java").read_text(encoding="utf-8")
main = (JAVA / "MainActivity.java").read_text(encoding="utf-8")
service = (JAVA / "ProtectionService.java").read_text(encoding="utf-8")
manifest = MANIFEST.read_text(encoding="utf-8")

for required in (
    "REQUEST_OWNER_FACE = 2042",
    'OWNER_FACE = "owner_face"',
    "FaceSimilarity.isEnrolled(context)",
    "Enroll owner face — five angles",
    "Five valid front-camera angles are required",
    "EnrollmentActivity.class",
):
    if required not in requirements:
        raise SystemExit("Mandatory owner-face requirement missing: " + required)
for required in (
    "descriptor.length != N * N",
    "prefs.getInt(OWNER_COUNT, 0) != MAX_SAMPLES",
):
    if required not in face:
        raise SystemExit("Owner-face profile validation missing: " + required)
for required in (
    "Owner face enrollment cleared — setup is now required",
    "showRequirementsGate();",
    "Capture photos and video after a failed unlock attempt",
    "Capture photos and video after a successful unlock",
    "android.content.SharedPreferences settings=",
    "android.os.Build.VERSION.SDK_INT>=26",
):
    if required not in main:
        raise SystemExit("Owner-face or credential capture UI missing: " + required)
for required in (
    "ACTION_SUCCESSFUL_CREDENTIAL",
    "DUPLICATE_SUCCESS_CALLBACK_MS",
    "three front-camera photos and a 5-second video",
):
    if required not in service:
        raise SystemExit("Credential event evidence pipeline missing: " + required)
if '.UnlockEventReceiver' not in manifest or "android.intent.action.USER_PRESENT" not in manifest:
    raise SystemExit("Successful-unlock fallback receiver is missing")

native = (JAVA / "NativeCloudActivity.java").read_text(encoding="utf-8")
for invalid in (
    'incident.optLong("bytes"), top(12));',
    'system.optString("php_version", "Unknown"), top(10));',
    'system.optInt("audit_events"), top(10));',
    'update.optString("release_notes", ""), top(10));',
):
    if invalid in native:
        raise SystemExit("Native Cloud render syntax was not normalized: " + invalid)

print(f"Promoted OwnerGuard Android to 1.0.39; updated {changed} Java files")
