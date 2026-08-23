#!/usr/bin/env python3
"""Add a manual fallback for OEMs that misreport Android's pause-if-unused state."""
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
REQ = ROOT / "app" / "src" / "main" / "java" / "com" / "fantest" / "ownerguard" / "OwnerGuardRequirements.java"

text = REQ.read_text(encoding="utf-8")

# Android/OEM builds do not always report the visible "Pause app activity if unused"
# toggle consistently through PackageManager.isAutoRevokeWhitelisted(). Preserve
# the API check when it works, but allow an explicit user confirmation after the
# settings screen has been opened.
if "KEY_UNUSED_OPENED" not in text:
    anchor = '    private static final String KEY_SAMSUNG_CONFIRMED = "samsung_never_sleeping_confirmed";'
    if anchor not in text:
        raise SystemExit("OwnerGuardRequirements preference anchor missing")
    text = text.replace(
        anchor,
        anchor
        + '\n    private static final String KEY_UNUSED_OPENED = "unused_app_settings_opened";'
        + '\n    private static final String KEY_UNUSED_CONFIRMED = "unused_app_settings_confirmed";',
        1,
    )

old_label = '        if (UNUSED_APP.equals(next)) return "Disable pause-if-unused";'
new_label = '''        if (UNUSED_APP.equals(next)) {
            return prefs(context).getBoolean(KEY_UNUSED_OPENED, false)
                    ? "Confirm pause-if-unused is Off"
                    : "Disable pause-if-unused";
        }'''
if "Confirm pause-if-unused is Off" not in text:
    if old_label not in text:
        raise SystemExit("Pause-if-unused next-action label anchor missing")
    text = text.replace(old_label, new_label, 1)

old_action = '''            if (UNUSED_APP.equals(next)) {
                openAppDetails(activity,
                        "Turn off ‘Pause app activity if unused’ / ‘Remove permissions if app isn't used’, then return.");
                return;
            }'''
new_action = '''            if (UNUSED_APP.equals(next)) {
                SharedPreferences requirementPrefs = prefs(activity);
                if (requirementPrefs.getBoolean(KEY_UNUSED_OPENED, false)) {
                    requirementPrefs.edit().putBoolean(KEY_UNUSED_CONFIRMED, true).apply();
                    Toast.makeText(activity,
                            "Pause-if-unused confirmation saved. Tap Recheck all requirements.",
                            Toast.LENGTH_LONG).show();
                } else {
                    requirementPrefs.edit().putBoolean(KEY_UNUSED_OPENED, true).apply();
                    openAppDetails(activity,
                            "Confirm ‘Pause app activity if unused’ / ‘Remove permissions if app isn't used’ is Off, then return to OwnerGuard.");
                }
                return;
            }'''
if "Pause-if-unused confirmation saved" not in text:
    if old_action not in text:
        raise SystemExit("Pause-if-unused action anchor missing")
    text = text.replace(old_action, new_action, 1)

old_check = '''    private static boolean unusedAppProtectionReady(Context context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return true;
        try {
            return context.getPackageManager().isAutoRevokeWhitelisted();
        } catch (Throwable ignored) {
            return false;
        }
    }'''
new_check = '''    private static boolean unusedAppProtectionReady(Context context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return true;
        try {
            if (context.getPackageManager().isAutoRevokeWhitelisted()) return true;
        } catch (Throwable ignored) {
            // Some OEMs do not expose a reliable auto-revoke verification result.
        }
        return prefs(context).getBoolean(KEY_UNUSED_CONFIRMED, false);
    }'''
if 'return prefs(context).getBoolean(KEY_UNUSED_CONFIRMED, false);' not in text:
    if old_check not in text:
        raise SystemExit("Pause-if-unused verification method anchor missing")
    text = text.replace(old_check, new_check, 1)

old_detail = '                "Prevents Android from automatically revoking critical permissions.");'
new_detail = '                "Prevents Android from automatically revoking critical permissions. If the OEM verification API is unavailable, explicit confirmation is accepted after you open the setting.");'
if new_detail not in text and old_detail in text:
    text = text.replace(old_detail, new_detail, 1)

for token in (
    "KEY_UNUSED_OPENED",
    "KEY_UNUSED_CONFIRMED",
    "Confirm pause-if-unused is Off",
    "isAutoRevokeWhitelisted",
    "Pause-if-unused confirmation saved",
    "return prefs(context).getBoolean(KEY_UNUSED_CONFIRMED, false);",
):
    if token not in text:
        raise SystemExit("Manual pause-if-unused invariant missing: " + token)

REQ.write_text(text, encoding="utf-8")
print("Applied manual pause-if-unused confirmation fallback for unreliable OEM verification")
