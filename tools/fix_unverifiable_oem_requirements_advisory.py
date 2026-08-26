#!/usr/bin/env python3
"""Keep unverifiable OEM settings advisory instead of hard-blocking OwnerGuard startup."""
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
REQ = ROOT / "app" / "src" / "main" / "java" / "com" / "fantest" / "ownerguard" / "OwnerGuardRequirements.java"

text = REQ.read_text(encoding="utf-8")

marker = "    private static final boolean UNVERIFIABLE_OEM_SETTINGS_ARE_ADVISORY = true;"
if marker not in text:
    anchor = "final class OwnerGuardRequirements {"
    if anchor not in text:
        raise SystemExit("OwnerGuardRequirements class anchor missing")
    text = text.replace(anchor, anchor + "\n" + marker, 1)

# Android's auto-revoke whitelist API and Samsung's Never Sleeping list are not
# reliable enough to be security hard gates. Keep both checks/UI helpers available
# for guidance, but never add them to missing(), which controls application access.
for hard_gate_line in (
    "        if (!unusedAppProtectionReady(context)) out.add(UNUSED_APP);\n",
    "        if (!samsungNeverSleepingReady(context)) out.add(SAMSUNG_NEVER_SLEEPING);\n",
):
    text = text.replace(hard_gate_line, "", 1)

old_unused_summary = '''        append(out, unusedAppProtectionReady(context), "Pause app activity if unused: Off",
                "Prevents Android from automatically revoking critical permissions. If the OEM verification API is unavailable, explicit confirmation is accepted after you open the setting.");'''
new_unused_summary = '''        appendAdvisory(out, "Pause app activity if unused",
                unusedAppProtectionReady(context)
                        ? "Verified Off. Android automatic permission revocation is disabled."
                        : "Recommended: keep this setting Off. This OEM does not expose a reliable verification result, so it does not block OwnerGuard.");'''
if old_unused_summary in text:
    text = text.replace(old_unused_summary, new_unused_summary, 1)
elif new_unused_summary not in text:
    raise SystemExit("Pause-if-unused summary anchor missing")

old_samsung_summary = '''        if (isSamsung()) {
            append(out, samsungNeverSleepingReady(context), "Samsung Never sleeping apps",
                    "Samsung does not expose a reliable verification API; this step requires your confirmation.");
        }'''
new_samsung_summary = '''        if (isSamsung()) {
            appendAdvisory(out, "Samsung Never sleeping apps",
                    samsungNeverSleepingReady(context)
                            ? "Confirmed. OwnerGuard was added to Samsung Never sleeping apps."
                            : "Recommended on Samsung. Samsung exposes no reliable verification API, so this setting does not block OwnerGuard.");
        }'''
if old_samsung_summary in text:
    text = text.replace(old_samsung_summary, new_samsung_summary, 1)
elif new_samsung_summary not in text:
    raise SystemExit("Samsung advisory summary anchor missing")

append_helper = '''    private static void append(StringBuilder out, boolean ready, String title, String detail) {
        if (out.length() > 0) out.append("\\n\\n");
        out.append(ready ? "✓  " : "!  ").append(title).append("\\n").append(detail);
    }'''
append_advisory = '''    private static void append(StringBuilder out, boolean ready, String title, String detail) {
        if (out.length() > 0) out.append("\\n\\n");
        out.append(ready ? "✓  " : "!  ").append(title).append("\\n").append(detail);
    }

    private static void appendAdvisory(StringBuilder out, String title, String detail) {
        if (out.length() > 0) out.append("\\n\\n");
        out.append("•  ").append(title).append("\\n").append(detail);
    }'''
if "private static void appendAdvisory" not in text:
    if append_helper not in text:
        raise SystemExit("append helper anchor missing")
    text = text.replace(append_helper, append_advisory, 1)

for forbidden in (
    "if (!unusedAppProtectionReady(context)) out.add(UNUSED_APP);",
    "if (!samsungNeverSleepingReady(context)) out.add(SAMSUNG_NEVER_SLEEPING);",
):
    if forbidden in text:
        raise SystemExit("Unverifiable OEM setting still blocks startup: " + forbidden)

for required in (
    "UNVERIFIABLE_OEM_SETTINGS_ARE_ADVISORY = true",
    "appendAdvisory(out, \"Pause app activity if unused\"",
    "appendAdvisory(out, \"Samsung Never sleeping apps\"",
    "This OEM does not expose a reliable verification result, so it does not block OwnerGuard.",
):
    if required not in text:
        raise SystemExit("OEM advisory invariant missing: " + required)

REQ.write_text(text, encoding="utf-8")
print("Unverifiable OEM settings are now advisory and cannot block OwnerGuard startup")
