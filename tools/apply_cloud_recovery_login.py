#!/usr/bin/env python3
"""Execute the reviewed OwnerGuard Cloud 1.3.27.4 recovery-login transformation."""
from pathlib import Path
import base64, gzip, hashlib
ROOT = Path(__file__).resolve().parents[1]
parts = sorted((ROOT / "tools" / "payloads").glob("cloud_recovery_login_1_3_27_4_*.part"))
if len(parts) != 4:
    raise SystemExit(f"OwnerGuard Cloud 1.3.27.4 payload part count mismatch: {len(parts)}")
encoded = "".join(p.read_text(encoding="ascii").strip() for p in parts)
source = gzip.decompress(base64.b64decode(encoded, validate=True))
expected = "97e91704ca6c6cc079492e3cafea46b515be2ad1f7b69313a19b19aa40a4caf9"
if hashlib.sha256(source).hexdigest() != expected:
    raise SystemExit("OwnerGuard Cloud 1.3.27.4 payload checksum mismatch")
exec(compile(source, str(parts[0]), "exec"), {"__name__": "__main__", "__file__": str(ROOT / "tools" / "apply_cloud_recovery_login.py")})
