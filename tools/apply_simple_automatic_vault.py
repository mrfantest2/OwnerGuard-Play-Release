#!/usr/bin/env python3
"""Execute the reviewed OwnerGuard 1.0.41 simplified-vault transformation."""
from pathlib import Path
import base64, gzip, hashlib
ROOT = Path(__file__).resolve().parents[1]
parts = sorted((ROOT / "tools" / "payloads").glob("simple_automatic_vault_1_0_41_*.part"))
if len(parts) != 8:
    raise SystemExit(f"OwnerGuard 1.0.41 Android payload part count mismatch: {len(parts)}")
encoded = "".join(p.read_text(encoding="ascii").strip() for p in parts)
source = gzip.decompress(base64.b64decode(encoded, validate=True))
expected = "6ed631afef0ab546b803e575214c5b74bc222ff14b94dc2c12912190f2e2ab36"
if hashlib.sha256(source).hexdigest() != expected:
    raise SystemExit("OwnerGuard 1.0.41 Android payload checksum mismatch")
exec(compile(source, str(parts[0]), "exec"), {"__name__": "__main__", "__file__": str(ROOT / "tools" / "apply_simple_automatic_vault.py")})
