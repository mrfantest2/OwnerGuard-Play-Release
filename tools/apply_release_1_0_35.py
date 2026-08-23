#!/usr/bin/env python3
"""Promote the fully patched OwnerGuard Android source to 1.0.35."""
from pathlib import Path
import re

ROOT = Path(__file__).resolve().parents[1]
GRADLE = ROOT / "app" / "build.gradle"
JAVA = ROOT / "app" / "src" / "main" / "java" / "com" / "fantest" / "ownerguard"

build = GRADLE.read_text(encoding="utf-8")
build = re.sub(r"versionCode\s+\d+", "versionCode 10035", build, count=1)
build = re.sub(r"versionName\s+'[^']+'", "versionName '1.0.35'", build, count=1)
if "versionCode 10035" not in build or "versionName '1.0.35'" not in build:
    raise SystemExit("OwnerGuard 1.0.35 version promotion failed")
GRADLE.write_text(build, encoding="utf-8")

changed = 0
for path in JAVA.glob("*.java"):
    text = path.read_text(encoding="utf-8")
    updated = re.sub(r"OwnerGuard-Android/\d+\.\d+\.\d+", "OwnerGuard-Android/1.0.35", text)
    updated = re.sub(
        r'private static final String APP_VERSION = "\d+\.\d+\.\d+";',
        'private static final String APP_VERSION = "1.0.35";',
        updated,
    )
    updated = re.sub(
        r'return info\.versionName == null \? "\d+\.\d+\.\d+" : info\.versionName;',
        'return info.versionName == null ? "1.0.35" : info.versionName;',
        updated,
    )
    updated = re.sub(r'return "1\.0\.\d+";', 'return "1.0.35";', updated)
    if updated != text:
        path.write_text(updated, encoding="utf-8")
        changed += 1

updater = (JAVA / "AppUpdateManager.java").read_text(encoding="utf-8")
if 'private static final String APP_VERSION = "1.0.35";' not in updater:
    raise SystemExit("AppUpdateManager was not promoted to OwnerGuard 1.0.35")
if not any("OwnerGuard-Android/1.0.35" in p.read_text(encoding="utf-8") for p in JAVA.glob("*.java")):
    raise SystemExit("OwnerGuard 1.0.35 network identity was not applied")

print(f"Promoted OwnerGuard Android to 1.0.35; updated {changed} Java files")
