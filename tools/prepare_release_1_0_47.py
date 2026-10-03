#!/usr/bin/env python3
"""Reconstruct OwnerGuard 1.0.47 as the Play-compliant production release.

This release preserves the verified 1.0.46 face-enrollment/camera fixes while
removing unnecessary background-location privileges from the Play artifact.
Foreground protection remains camera-based and cloud backup remains data-sync.
"""
from pathlib import Path
import re
import subprocess
import sys

ROOT = Path(__file__).resolve().parents[1]
TOOLS = ROOT / "tools"
GRADLE = ROOT / "app" / "build.gradle"
MANIFEST = ROOT / "app" / "src" / "main" / "AndroidManifest.xml"
JAVA = ROOT / "app" / "src" / "main" / "java" / "com" / "fantest" / "ownerguard"

def run(*args):
    subprocess.run(list(args), cwd=ROOT, check=True)

def main() -> int:
    run(sys.executable, str(TOOLS / "prepare_release_1_0_46.py"))

    build = GRADLE.read_text(encoding="utf-8")
    build = re.sub(r"versionCode\s+10046\b", "versionCode 10047", build, count=1)
    build = re.sub(r"versionName\s+'1\.0\.46'", "versionName '1.0.47'", build, count=1)
    if "versionCode 10047" not in build or "versionName '1.0.47'" not in build:
        raise SystemExit("OwnerGuard 1.0.47 version promotion failed")
    GRADLE.write_text(build, encoding="utf-8")

    manifest = MANIFEST.read_text(encoding="utf-8")
    manifest = manifest.replace(
        '    <uses-permission android:name="android.permission.FOREGROUND_SERVICE_LOCATION" />\n', "", 1)
    manifest = manifest.replace(
        '    <uses-permission android:name="android.permission.ACCESS_BACKGROUND_LOCATION" />\n', "", 1)
    manifest = manifest.replace(
        'android:foregroundServiceType="camera|location"',
        'android:foregroundServiceType="camera"',
        1,
    )
    MANIFEST.write_text(manifest, encoding="utf-8")

    # Promote visible/network version identities where they exist, without changing
    # compatibility contracts that intentionally remain pinned.
    for path in JAVA.glob("*.java"):
        text = path.read_text(encoding="utf-8")
        updated = re.sub(r"OwnerGuard-Android/1\.0\.46\b", "OwnerGuard-Android/1.0.47", text)
        updated = re.sub(r'private static final String APP_VERSION = "1\.0\.46";',
                         'private static final String APP_VERSION = "1.0.47";', updated)
        updated = re.sub(r'return info\.versionName == null \? "1\.0\.46" : info\.versionName;',
                         'return info.versionName == null ? "1.0.47" : info.versionName;', updated)
        if updated != text:
            path.write_text(updated, encoding="utf-8")

    run(sys.executable, str(TOOLS / "release_contract_1_0_47.py"))
    print("OwnerGuard 1.0.47 Play-compliance preparation: PASS")
    return 0

if __name__ == "__main__":
    raise SystemExit(main())
