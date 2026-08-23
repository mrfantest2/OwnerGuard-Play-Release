#!/usr/bin/env python3
"""Normalize fully qualified Android types used by the generated capture policy."""
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
MAIN = ROOT / "app" / "src" / "main" / "java" / "com" / "fantest" / "ownerguard" / "MainActivity.java"

text = MAIN.read_text(encoding="utf-8")
text = text.replace(
    '        SharedPreferences settings=getSharedPreferences("owner_guard_settings",MODE_PRIVATE);',
    '        android.content.SharedPreferences settings=getSharedPreferences("owner_guard_settings",MODE_PRIVATE);',
    1,
)
text = text.replace(
    '        SharedPreferences.Editor edit=settings.edit();',
    '        android.content.SharedPreferences.Editor edit=settings.edit();',
    1,
)
text = text.replace(
    '                if(Build.VERSION.SDK_INT>=26)startForegroundService(armIntent);',
    '                if(android.os.Build.VERSION.SDK_INT>=26)startForegroundService(armIntent);',
    1,
)

for required in (
    'android.content.SharedPreferences settings=',
    'android.content.SharedPreferences.Editor edit=',
    'android.os.Build.VERSION.SDK_INT>=26',
):
    if required not in text:
        raise SystemExit("Credential evidence type normalization failed: " + required)

MAIN.write_text(text, encoding="utf-8")
print("Normalized OwnerGuard credential evidence Android type references")
