#!/usr/bin/env python3
"""Normalize Java newline escapes produced by the OwnerGuard forced-update overlay."""
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
FILE = ROOT / "app" / "src" / "main" / "java" / "com" / "fantest" / "ownerguard" / "AppUpdateManager.java"
text = FILE.read_text(encoding="utf-8")

bad = '''setMessage(message.toString() + "

Normal controls remain locked until installation. Armed protection and encrypted photo/video capture continue in the background.")'''
good = 'setMessage(message.toString() + "\\n\\nNormal controls remain locked until installation. Armed protection and encrypted photo/video capture continue in the background.")'

if bad in text:
    text = text.replace(bad, good, 1)
elif good not in text:
    raise SystemExit("Forced-update Java newline anchor was not found")

if 'message.toString() + "\\n\\nNormal controls remain locked' not in text:
    raise SystemExit("Forced-update Java newline normalization failed")
FILE.write_text(text, encoding="utf-8")
print("Normalized OwnerGuard forced-update Java newline escapes")
