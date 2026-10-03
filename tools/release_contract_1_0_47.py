#!/usr/bin/env python3
"""Static release contract for OwnerGuard 1.0.47 Play compliance."""
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
BUILD = (ROOT / "app" / "build.gradle").read_text(encoding="utf-8")
MANIFEST = (ROOT / "app" / "src" / "main" / "AndroidManifest.xml").read_text(encoding="utf-8")

for token in ("versionCode 10047", "versionName '1.0.47'", "targetSdk 36"):
    if token not in BUILD:
        raise SystemExit("OwnerGuard 1.0.47 build invariant missing: " + token)

for token in (
    'android.permission.CAMERA',
    'android.permission.FOREGROUND_SERVICE',
    'android.permission.FOREGROUND_SERVICE_CAMERA',
    'android.permission.FOREGROUND_SERVICE_DATA_SYNC',
    'android.permission.ACCESS_COARSE_LOCATION',
    'android.permission.ACCESS_FINE_LOCATION',
    'android:name=".ProtectionService"',
    'android:foregroundServiceType="camera"',
    'android:name=".CloudBackupService"',
    'android:foregroundServiceType="dataSync"',
):
    if token not in MANIFEST:
        raise SystemExit("OwnerGuard 1.0.47 manifest invariant missing: " + token)

for forbidden in (
    'android.permission.ACCESS_BACKGROUND_LOCATION',
    'android.permission.FOREGROUND_SERVICE_LOCATION',
    'android:foregroundServiceType="camera|location"',
    'android.permission.REQUEST_INSTALL_PACKAGES',
):
    if forbidden in MANIFEST:
        raise SystemExit("OwnerGuard 1.0.47 Play-forbidden manifest token remains: " + forbidden)

print("OwnerGuard 1.0.47 release contract: PASS")
