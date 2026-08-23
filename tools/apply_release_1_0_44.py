#!/usr/bin/env python3
"""Promote the reconstructed OwnerGuard 1.0.43 tree to Play-ready 1.0.44 Pro backup."""
from pathlib import Path
import re
import subprocess
import sys

ROOT = Path(__file__).resolve().parents[1]
GRADLE = ROOT / "app" / "build.gradle"
MANIFEST = ROOT / "app" / "src" / "main" / "AndroidManifest.xml"
JAVA = ROOT / "app" / "src" / "main" / "java" / "com" / "fantest" / "ownerguard"
MAIN = JAVA / "MainActivity.java"
UPDATER = JAVA / "AppUpdateManager.java"
REQUIREMENTS = JAVA / "OwnerGuardRequirements.java"

for helper in ("fix_native_cloud_render_syntax.py", "fix_credential_event_imports.py"):
    path = ROOT / "tools" / helper
    if path.is_file():
        subprocess.run([sys.executable, str(path)], check=True)

# Promote Android/Play metadata and add current Play Billing.
build = GRADLE.read_text(encoding="utf-8")
build = re.sub(r"targetSdk\s+\d+", "targetSdk 36", build, count=1)
build = re.sub(r"versionCode\s+\d+", "versionCode 10044", build, count=1)
build = re.sub(r"versionName\s+'[^']+'", "versionName '1.0.44'", build, count=1)
if "com.android.billingclient:billing:9.1.0" not in build:
    anchor = "dependencies {"
    if anchor not in build:
        raise SystemExit("OwnerGuard dependencies block was not found")
    build = build.replace(anchor, anchor + "\n    implementation 'com.android.billingclient:billing:9.1.0'", 1)
for token in ("targetSdk 36", "versionCode 10044", "versionName '1.0.44'", "com.android.billingclient:billing:9.1.0"):
    if token not in build:
        raise SystemExit("OwnerGuard 1.0.44 Gradle invariant missing: " + token)
GRADLE.write_text(build, encoding="utf-8")

# Promote version identity without changing the v1.0.43 minimum Cloud contract.
changed = 0
for path in JAVA.glob("*.java"):
    text = path.read_text(encoding="utf-8")
    updated = re.sub(r"OwnerGuard-Android/\d+\.\d+\.\d+", "OwnerGuard-Android/1.0.44", text)
    updated = re.sub(r'private static final String APP_VERSION = "\d+\.\d+\.\d+";',
                     'private static final String APP_VERSION = "1.0.44";', updated)
    updated = re.sub(r'return info\.versionName == null \? "\d+\.\d+\.\d+" : info\.versionName;',
                     'return info.versionName == null ? "1.0.44" : info.versionName;', updated)
    updated = re.sub(r'return "1\.0\.\d+";', 'return "1.0.44";', updated)
    if updated != text:
        path.write_text(updated, encoding="utf-8")
        changed += 1

# Play release: no arbitrary APK-install permission. Google Play owns application updates.
manifest = MANIFEST.read_text(encoding="utf-8")
manifest = re.sub(r'\s*<uses-permission android:name="android\.permission\.REQUEST_INSTALL_PACKAGES"\s*/>\s*', '\n', manifest, count=1)
activity = '        <activity android:name=".ProBackupActivity" android:exported="false" />'
if activity not in manifest:
    anchor = '        <activity android:name=".CloudConsoleActivity" android:exported="false" />'
    if anchor not in manifest:
        raise SystemExit("CloudConsoleActivity manifest anchor was not found")
    manifest = manifest.replace(anchor, anchor + "\n" + activity, 1)
MANIFEST.write_text(manifest, encoding="utf-8")

# Remove the legacy sideload requirement from first-run readiness. Without this,
# a Play build with REQUEST_INSTALL_PACKAGES correctly removed would permanently
# fail allReady() because PackageManager.canRequestPackageInstalls() cannot become true.
requirements = REQUIREMENTS.read_text(encoding="utf-8")
requirements = requirements.replace(
    "        if (!installUpdatesReady(context)) out.add(INSTALL_UPDATES);\n", "")
requirements = requirements.replace(
    '        if (INSTALL_UPDATES.equals(next)) return "Allow OwnerGuard app updates";\n', "")
requirements = requirements.replace(
    '        append(out, installUpdatesReady(context), "Install OwnerGuard updates",\n'
    '                "Required for verified mandatory APK updates.");\n', "")
requirements = re.sub(
    r'\n\s*if \(INSTALL_UPDATES\.equals\(next\)\) \{\s*'
    r'Intent intent = new Intent\(Settings\.ACTION_MANAGE_UNKNOWN_APP_SOURCES,\s*'
    r'Uri\.parse\("package:" \+ activity\.getPackageName\(\)\)\);\s*'
    r'activity\.startActivity\(intent\);\s*return;\s*\}',
    "",
    requirements,
    count=1,
    flags=re.S,
)
requirements = re.sub(
    r'\n\s*private static boolean installUpdatesReady\(Context context\) \{\s*'
    r'return Build\.VERSION\.SDK_INT < Build\.VERSION_CODES\.O\s*'
    r'\|\| context\.getPackageManager\(\)\.canRequestPackageInstalls\(\);\s*\}',
    "",
    requirements,
    count=1,
    flags=re.S,
)
# The constant is harmless but removing it makes static release checks unambiguous.
requirements = requirements.replace('    private static final String INSTALL_UPDATES = "install_updates";\n', "")
REQUIREMENTS.write_text(requirements, encoding="utf-8")

# Add Pro entry point to the local vault/settings page after the complete legacy patch stack.
main = MAIN.read_text(encoding="utf-8")
if "ProBackupActivity.class" not in main:
    method = main.find("private void showVaultTab()")
    if method < 0:
        method = main.find("private void showVaultTab(){")
    if method < 0:
        raise SystemExit("OwnerGuard 1.0.44 could not find showVaultTab")
    end = main.find("\n    private void ", method + 20)
    if end < 0:
        raise SystemExit("OwnerGuard 1.0.44 could not find end of showVaultTab")
    section = main[method:end]
    marker = "setPage(page);"
    marker_pos = section.rfind(marker)
    if marker_pos < 0:
        raise SystemExit("OwnerGuard 1.0.44 could not find showVaultTab setPage anchor")
    insertion = (
        '        page.addView(sectionTitle("OwnerGuard Pro"),topMargin(22));\n'
        '        page.addView(labelCard("Core device protection and the local encrypted vault remain free. OwnerGuard Pro adds portable encrypted Google Drive / cloud-folder backup and disaster-recovery restore."),topMargin(10));\n'
        '        page.addView(infoCard("Pro status",ProEntitlement.cachedStatus(this)),topMargin(10));\n'
        '        page.addView(button(ProEntitlement.cached(this)?"Open Pro backup":"Unlock Pro backup",v->startActivity(new Intent(this,ProBackupActivity.class))),topMargin(10));\n'
    )
    section = section[:marker_pos] + insertion + section[marker_pos:]
    main = main[:method] + section + main[end:]

# Rewrite update UX for this Play branch using structural anchors rather than
# depending on the exact explanatory sentence emitted by earlier lineage patches.
play_update_message = (
    "OwnerGuard 1.0.44 is a Google Play managed release. Application updates are delivered by "
    "Google Play; OwnerGuard does not sideload replacement APKs in this build."
)
update_block = re.compile(
    r'(?m)^        page\.addView\(sectionTitle\("(?:Secure app updates|App updates)"\),topMargin\(22\)\);\n'
    r'^        page\.addView\(labelCard\("[^\n]*"\),topMargin\(10\)\);'
)
update_replacement = (
    '        page.addView(sectionTitle("App updates"),topMargin(22));\n'
    f'        page.addView(labelCard("{play_update_message}"),topMargin(10));'
)
main, update_count = update_block.subn(update_replacement, main, count=1)
if update_count != 1 and not (
    'sectionTitle("App updates")' in main and play_update_message in main
):
    raise SystemExit("OwnerGuard 1.0.44 could not structurally rewrite the app-update section")

button_pattern = re.compile(
    r'button\("(?:Check for signed OwnerGuard update|Check Google Play update status)",'
    r'v->AppUpdateManager\.check\(this,true\)\)'
)
main, button_count = button_pattern.subn(
    'button("Check Google Play update status",v->AppUpdateManager.check(this,true))',
    main,
    count=1,
)
if button_count != 1 and 'button("Check Google Play update status",v->AppUpdateManager.check(this,true))' not in main:
    raise SystemExit("OwnerGuard 1.0.44 could not rewrite the update-status button")
MAIN.write_text(main, encoding="utf-8")

# Force the updater subsystem into Play-managed mode while preserving call compatibility.
updater = UPDATER.read_text(encoding="utf-8")
if "PLAY_MANAGED_RELEASE" not in updater:
    class_anchor = "final class AppUpdateManager {"
    if class_anchor not in updater:
        raise SystemExit("AppUpdateManager class anchor missing")
    updater = updater.replace(class_anchor, class_anchor + '\n    private static final boolean PLAY_MANAGED_RELEASE = true;', 1)

# initialize(): retain installed-version reconciliation but never schedule direct APK checks.
init_anchor = "static void initialize(Context context) {"
if init_anchor not in updater:
    raise SystemExit("AppUpdateManager.initialize anchor missing")
needle = init_anchor + "\n        if (context == null) return;"
if needle in updater and "PLAY_MANAGED_RELEASE) return;" not in updater[updater.find(init_anchor):updater.find(init_anchor)+500]:
    updater = updater.replace(needle, needle + "\n        if (PLAY_MANAGED_RELEASE) { reconcileInstalledVersion(context.getApplicationContext()); return; }", 1)

schedule_anchor = "static void scheduleBackgroundChecks(Context context) {"
if schedule_anchor not in updater:
    raise SystemExit("AppUpdateManager.scheduleBackgroundChecks anchor missing")
updater = updater.replace(schedule_anchor, schedule_anchor + "\n        if (PLAY_MANAGED_RELEASE) { try { WorkManager.getInstance(context).cancelUniqueWork(UNIQUE_WORK); } catch (Throwable ignored) {} return; }", 1)

check_anchor = "static void check(Activity activity, boolean userInitiated) {"
if check_anchor not in updater:
    raise SystemExit("AppUpdateManager.check anchor missing")
updater = updater.replace(check_anchor, check_anchor + '\n        if (PLAY_MANAGED_RELEASE) { if (userInitiated && activity != null && !activity.isFinishing()) new AlertDialog.Builder(activity).setTitle("Updates managed by Google Play").setMessage("OwnerGuard 1.0.44 receives application updates through Google Play. Open the OwnerGuard Play Store listing to see whether an update is available.").setPositiveButton("Close", null).show(); return; }', 1)

background_anchor = "static boolean backgroundCheck(Context context) {"
if background_anchor not in updater:
    raise SystemExit("AppUpdateManager.backgroundCheck anchor missing")
updater = updater.replace(background_anchor, background_anchor + "\n        if (PLAY_MANAGED_RELEASE) return true;", 1)

resume_anchor = "static void onActivityResumed(Activity activity) {"
if resume_anchor not in updater:
    raise SystemExit("AppUpdateManager.onActivityResumed anchor missing")
updater = updater.replace(resume_anchor, resume_anchor + "\n        if (PLAY_MANAGED_RELEASE) return;", 1)
UPDATER.write_text(updater, encoding="utf-8")

# Final source invariants.
checks = {
    GRADLE: ("targetSdk 36", "versionCode 10044", "versionName '1.0.44'", "billing:9.1.0"),
    MANIFEST: (".ProBackupActivity",),
    MAIN: ("ProBackupActivity.class", "OwnerGuard Pro", "Google Play managed release"),
    UPDATER: ("PLAY_MANAGED_RELEASE = true", "Updates managed by Google Play"),
    JAVA / "ProEntitlement.java": ("ownerguard_pro_lifetime", "Purchase.PurchaseState.PURCHASED", "acknowledgePurchase"),
    JAVA / "DriveBackupManager.java": ("OwnerGuard Pro is required", "CloudCrypto.getOrCreateKey", "VaultCrypto.decryptBytes", "VaultCrypto.encryptBytes"),
    JAVA / "ProBackupActivity.java": ("Choose Google Drive backup folder", "Restore an OwnerGuard .ogb backup"),
}
for path, tokens in checks.items():
    if not path.is_file():
        raise SystemExit("OwnerGuard 1.0.44 required source missing: " + str(path.relative_to(ROOT)))
    text = path.read_text(encoding="utf-8")
    for token in tokens:
        if token not in text:
            raise SystemExit(f"OwnerGuard 1.0.44 invariant missing in {path.name}: {token}")

if "REQUEST_INSTALL_PACKAGES" in MANIFEST.read_text(encoding="utf-8"):
    raise SystemExit("Play release must not request REQUEST_INSTALL_PACKAGES")
requirements_final = REQUIREMENTS.read_text(encoding="utf-8")
for forbidden in ("ACTION_MANAGE_UNKNOWN_APP_SOURCES", "canRequestPackageInstalls", "Install OwnerGuard updates", "install_updates"):
    if forbidden in requirements_final:
        raise SystemExit("Play release still contains legacy sideload requirement: " + forbidden)
if 'MIN_SERVER_VERSION = "1.3.27.6"' not in (JAVA / "CloudAccountManager.java").read_text(encoding="utf-8"):
    raise SystemExit("OwnerGuard 1.0.44 must preserve the verified v1.0.43 Cloud minimum contract")

print(f"Promoted OwnerGuard Android to Play Pro 1.0.44; updated {changed} Java version identities")
