#!/usr/bin/env python3
"""Preserve the retired CloudAuthActivity request-code symbol for compile compatibility only."""
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
AUTH = ROOT / "app/src/main/java/com/fantest/ownerguard/CloudAuthActivity.java"

text = AUTH.read_text(encoding="utf-8")
marker = "public class CloudAuthActivity extends SecureActivity {"
constant = "    public static final int REQUEST_CODE = 41041;"

if constant not in text:
    if marker not in text:
        raise SystemExit("CloudAuthActivity class anchor missing")
    text = text.replace(marker, marker + "\n" + constant, 1)
    AUTH.write_text(text, encoding="utf-8")

if "REQUEST_CODE" not in AUTH.read_text(encoding="utf-8"):
    raise SystemExit("CloudAuthActivity request-code compatibility symbol missing")

print("Preserved retired CloudAuthActivity request-code compile surface")
