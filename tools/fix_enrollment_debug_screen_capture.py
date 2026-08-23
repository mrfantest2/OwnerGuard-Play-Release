#!/usr/bin/env python3
"""Allow screen capture for debuggable enrollment diagnostics while keeping release secure."""
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
ENROLLMENT = ROOT / "app" / "src" / "main" / "java" / "com" / "fantest" / "ownerguard" / "EnrollmentActivity.java"

text = ENROLLMENT.read_text(encoding="utf-8")
unconditional = "getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE);"
old_conditional = "if (!BuildConfig.DEBUG) getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE);"
conditional = "if ((getApplicationInfo().flags & android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) == 0) getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE);"

if old_conditional in text:
    text = text.replace(old_conditional, conditional, 1)
elif conditional not in text:
    if unconditional not in text:
        raise SystemExit("EnrollmentActivity FLAG_SECURE anchor missing")
    text = text.replace(unconditional, conditional, 1)

if conditional not in text:
    raise SystemExit("Debug-recordable/release-secure enrollment invariant missing")

ENROLLMENT.write_text(text, encoding="utf-8")
print("Enrollment diagnostics: debuggable screen capture enabled; release FLAG_SECURE preserved")
