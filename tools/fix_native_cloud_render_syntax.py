#!/usr/bin/env python3
"""Normalize NativeCloudActivity card/addView parentheses before compilation."""
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
TARGET = ROOT / "app" / "src" / "main" / "java" / "com" / "fantest" / "ownerguard" / "NativeCloudActivity.java"

text = TARGET.read_text(encoding="utf-8")
replacements = [
    (
        '+ "\\nEncrypted bytes: " + incident.optLong("bytes"), top(12));',
        '+ "\\nEncrypted bytes: " + incident.optLong("bytes")), top(12));',
    ),
    (
        '+ "\\nPHP: " + system.optString("php_version", "Unknown"), top(10));',
        '+ "\\nPHP: " + system.optString("php_version", "Unknown")), top(10));',
    ),
    (
        '+ "\\nAudit events: " + system.optInt("audit_events"), top(10));',
        '+ "\\nAudit events: " + system.optInt("audit_events")), top(10));',
    ),
    (
        '+ "\\n\\n" + update.optString("release_notes", ""), top(10));',
        '+ "\\n\\n" + update.optString("release_notes", "")), top(10));',
    ),
]

changed = 0
for old, new in replacements:
    if old in text:
        text = text.replace(old, new, 1)
        changed += 1
    elif new not in text:
        raise SystemExit(f"Native Cloud render anchor missing: {old}")

for invalid, valid in replacements:
    if invalid in text:
        raise SystemExit(f"Invalid Native Cloud render expression remains: {invalid}")
    if valid not in text:
        raise SystemExit(f"Corrected Native Cloud render expression missing: {valid}")

TARGET.write_text(text, encoding="utf-8")
print(f"Corrected {changed} Native Cloud render expressions")
