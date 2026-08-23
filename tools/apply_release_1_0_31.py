#!/usr/bin/env python3
"""Promote the fully patched OwnerGuard Android source to 1.0.31."""
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
GRADLE = ROOT / "app" / "build.gradle"
JAVA = ROOT / "app" / "src" / "main" / "java" / "com" / "fantest" / "ownerguard"

build = GRADLE.read_text(encoding="utf-8")
build = build.replace("versionCode 10030", "versionCode 10031")
build = build.replace("versionName '1.0.30'", "versionName '1.0.31'")
if "versionCode 10031" not in build or "versionName '1.0.31'" not in build:
    raise SystemExit("OwnerGuard 1.0.31 version promotion failed")
GRADLE.write_text(build, encoding="utf-8")

changed = 0
for path in JAVA.glob("*.java"):
    text = path.read_text(encoding="utf-8")
    updated = text.replace("OwnerGuard-Android/1.0.30", "OwnerGuard-Android/1.0.31")
    updated = updated.replace('private static final String APP_VERSION = "1.0.30";',
                              'private static final String APP_VERSION = "1.0.31";')
    if updated != text:
        path.write_text(updated, encoding="utf-8")
        changed += 1

main = JAVA / "MainActivity.java"
main_text = main.read_text(encoding="utf-8")
signout_anchor = """                CloudAccountManager.logout(MainActivity.this);
                Toast.makeText(MainActivity.this,\"Cloud account signed out\",Toast.LENGTH_SHORT).show();"""
signout_replacement = """                CloudAccountManager.logout(MainActivity.this);
                android.webkit.CookieManager cookies=android.webkit.CookieManager.getInstance();
                cookies.removeAllCookies(null);
                cookies.flush();
                Toast.makeText(MainActivity.this,\"Cloud account signed out\",Toast.LENGTH_SHORT).show();"""
if signout_anchor not in main_text:
    raise SystemExit("OwnerGuard drawer Cloud sign-out anchor is missing")
main.write_text(main_text.replace(signout_anchor, signout_replacement, 1), encoding="utf-8")

updater = (JAVA / "AppUpdateManager.java").read_text(encoding="utf-8")
if 'private static final String APP_VERSION = "1.0.31";' not in updater:
    raise SystemExit("AppUpdateManager was not promoted to OwnerGuard 1.0.31")
if not any("OwnerGuard-Android/1.0.31" in p.read_text(encoding="utf-8") for p in JAVA.glob("*.java")):
    raise SystemExit("OwnerGuard 1.0.31 network identity was not applied")
if "cookies.removeAllCookies(null);" not in main.read_text(encoding="utf-8"):
    raise SystemExit("OwnerGuard drawer Cloud sign-out did not clear WebView cookies")

print(f"Promoted OwnerGuard Android to 1.0.31; updated {changed} Java files")
