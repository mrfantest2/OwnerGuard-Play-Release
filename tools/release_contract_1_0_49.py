#!/usr/bin/env python3
from pathlib import Path
ROOT=Path(__file__).resolve().parents[1]
BUILD=(ROOT/"app"/"build.gradle").read_text(encoding="utf-8")
MANIFEST=(ROOT/"app"/"src"/"main"/"AndroidManifest.xml").read_text(encoding="utf-8")
REQ=(ROOT/"app"/"src"/"main"/"java"/"com"/"fantest"/"ownerguard"/"OwnerGuardRequirements.java").read_text(encoding="utf-8")
for token in ("versionCode 10049","versionName '1.0.49'","targetSdk 36"):
    if token not in BUILD: raise SystemExit("build invariant missing: "+token)
for token in (
    "android.permission.CAMERA",
    "android.permission.FOREGROUND_SERVICE_CAMERA",
    "android.permission.FOREGROUND_SERVICE_DATA_SYNC",
    "android.permission.ACCESS_COARSE_LOCATION",
    "android.permission.ACCESS_FINE_LOCATION",
):
    if token not in MANIFEST: raise SystemExit("manifest invariant missing: "+token)
for token in (
    "android.permission.ACCESS_BACKGROUND_LOCATION",
    "android.permission.FOREGROUND_SERVICE_LOCATION",
    "android:foregroundServiceType=\"camera|location\"",
):
    if token in MANIFEST: raise SystemExit("forbidden manifest token remains: "+token)
for token in (
    "BACKGROUND_LOCATION",
    "backgroundLocationReady(",
    "requestBackgroundLocation(",
    "Allow location all the time",
):
    if token in REQ: raise SystemExit("background-location gate remains: "+token)
for token in (
    "Precise location (While using the app)",
    "Background location is not required.",
):
    if token not in REQ: raise SystemExit("new location contract missing: "+token)
print("OwnerGuard 1.0.49 release contract: PASS")
