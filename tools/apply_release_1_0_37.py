#!/usr/bin/env python3
"""Promote the fully patched OwnerGuard Android source to 1.0.37."""
from pathlib import Path
import re
import subprocess
import sys

ROOT = Path(__file__).resolve().parents[1]
GRADLE = ROOT / "app" / "build.gradle"
JAVA = ROOT / "app" / "src" / "main" / "java" / "com" / "fantest" / "ownerguard"

subprocess.run(
    [sys.executable, str(ROOT / "tools" / "fix_native_cloud_render_syntax.py")],
    check=True,
)

build = GRADLE.read_text(encoding="utf-8")
build = re.sub(r"versionCode\s+\d+", "versionCode 10037", build, count=1)
build = re.sub(r"versionName\s+'[^']+'", "versionName '1.0.37'", build, count=1)
if "versionCode 10037" not in build or "versionName '1.0.37'" not in build:
    raise SystemExit("OwnerGuard 1.0.37 version promotion failed")
GRADLE.write_text(build, encoding="utf-8")

changed = 0
for path in JAVA.glob("*.java"):
    text = path.read_text(encoding="utf-8")
    updated = re.sub(r"OwnerGuard-Android/\d+\.\d+\.\d+", "OwnerGuard-Android/1.0.37", text)
    updated = re.sub(
        r'private static final String APP_VERSION = "\d+\.\d+\.\d+";',
        'private static final String APP_VERSION = "1.0.37";',
        updated,
    )
    updated = re.sub(
        r'return info\.versionName == null \? "\d+\.\d+\.\d+" : info\.versionName;',
        'return info.versionName == null ? "1.0.37" : info.versionName;',
        updated,
    )
    updated = re.sub(r'return "1\.0\.\d+";', 'return "1.0.37";', updated)
    if updated != text:
        path.write_text(updated, encoding="utf-8")
        changed += 1

updater = (JAVA / "AppUpdateManager.java").read_text(encoding="utf-8")
if 'private static final String APP_VERSION = "1.0.37";' not in updater:
    raise SystemExit("AppUpdateManager was not promoted to OwnerGuard 1.0.37")
if not any("OwnerGuard-Android/1.0.37" in p.read_text(encoding="utf-8") for p in JAVA.glob("*.java")):
    raise SystemExit("OwnerGuard 1.0.37 network identity was not applied")

main = (JAVA / "MainActivity.java").read_text(encoding="utf-8")
shell = (JAVA / "OwnerGuardDrawerShell.java").read_text(encoding="utf-8")
native = (JAVA / "NativeCloudActivity.java").read_text(encoding="utf-8")
for required in (
    'page.addView(sectionTitle("Cloud workspace"),topMargin(22));',
    'page.addView(sectionTitle("Administration"),topMargin(22));',
    'openNativeCloudFromSetup("incidents.php","All incidents")',
    'Sign out of cloud vault',
):
    if required not in main:
        raise SystemExit("Vertical Cloud control center missing from release: " + required)
for forbidden in (
    'section(nav, "CLOUD VAULT")',
    'section(nav, "ADMINISTRATION")',
    'item(nav, "incidents"',
    'item(nav, "users"',
):
    if forbidden in shell:
        raise SystemExit("Cloud navigation remains in release drawer: " + forbidden)
if "NativeCloudActivity" not in native:
    raise SystemExit("Native Cloud activity is missing from release")
for invalid in (
    'incident.optLong("bytes"), top(12));',
    'system.optString("php_version", "Unknown"), top(10));',
    'system.optInt("audit_events"), top(10));',
    'update.optString("release_notes", ""), top(10));',
):
    if invalid in native:
        raise SystemExit("Native Cloud render syntax was not normalized: " + invalid)

print(f"Promoted OwnerGuard Android to 1.0.37; updated {changed} Java files")
