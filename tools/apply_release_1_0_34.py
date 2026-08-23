#!/usr/bin/env python3
"""Promote the fully patched OwnerGuard Android source to 1.0.34."""
from pathlib import Path
import re

ROOT = Path(__file__).resolve().parents[1]
GRADLE = ROOT / "app" / "build.gradle"
JAVA = ROOT / "app" / "src" / "main" / "java" / "com" / "fantest" / "ownerguard"

build = GRADLE.read_text(encoding="utf-8")
build = re.sub(r"versionCode\s+\d+", "versionCode 10034", build, count=1)
build = re.sub(r"versionName\s+'[^']+'", "versionName '1.0.34'", build, count=1)
if "versionCode 10034" not in build or "versionName '1.0.34'" not in build:
    raise SystemExit("OwnerGuard 1.0.34 version promotion failed")
GRADLE.write_text(build, encoding="utf-8")

changed = 0
for path in JAVA.glob("*.java"):
    text = path.read_text(encoding="utf-8")
    updated = re.sub(r"OwnerGuard-Android/\d+\.\d+\.\d+", "OwnerGuard-Android/1.0.34", text)
    updated = re.sub(
        r'private static final String APP_VERSION = "\d+\.\d+\.\d+";',
        'private static final String APP_VERSION = "1.0.34";',
        updated,
    )
    updated = updated.replace('return info.versionName == null ? "1.0.33" : info.versionName;',
                              'return info.versionName == null ? "1.0.34" : info.versionName;')
    updated = updated.replace('return "1.0.33";', 'return "1.0.34";')
    if updated != text:
        path.write_text(updated, encoding="utf-8")
        changed += 1

updater = (JAVA / "AppUpdateManager.java").read_text(encoding="utf-8")
if 'private static final String APP_VERSION = "1.0.34";' not in updater:
    raise SystemExit("AppUpdateManager was not promoted to OwnerGuard 1.0.34")
if not any("OwnerGuard-Android/1.0.34" in p.read_text(encoding="utf-8") for p in JAVA.glob("*.java")):
    raise SystemExit("OwnerGuard 1.0.34 network identity was not applied")

print(f"Promoted OwnerGuard Android to 1.0.34; updated {changed} Java files")
