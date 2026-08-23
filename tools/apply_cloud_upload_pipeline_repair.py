#!/usr/bin/env python3
"""Repair OwnerGuard Cloud bearer forwarding and promote Cloud to 1.3.27.6."""
from pathlib import Path
import re

ROOT = Path(__file__).resolve().parents[1]
OUT = ROOT / ".deploy" / "ownerguard_cloud"
HTACCESS = OUT / ".htaccess"
CORE = OUT / "includes" / "core.php"
UI = OUT / "includes" / "ui.php"
VERSION = OUT / "VERSION"
CHANGELOG = OUT / "CHANGELOG.txt"


def replace_php_function(text: str, signature: str, replacement: str) -> str:
    start = text.find(signature)
    if start < 0:
        raise SystemExit(f"Cloud upload repair anchor missing: {signature}")
    brace = text.find("{", start)
    depth = 0
    in_single = in_double = False
    escaped = False
    i = brace
    while i < len(text):
        ch = text[i]
        if escaped:
            escaped = False
        elif ch == "\\" and (in_single or in_double):
            escaped = True
        elif ch == "'" and not in_double:
            in_single = not in_single
        elif ch == '"' and not in_single:
            in_double = not in_double
        elif not in_single and not in_double:
            if ch == "{": depth += 1
            elif ch == "}":
                depth -= 1
                if depth == 0:
                    return text[:start] + replacement.rstrip() + text[i + 1:]
        i += 1
    raise SystemExit(f"Cloud upload repair function did not terminate: {signature}")

ht = HTACCESS.read_text(encoding="utf-8")
forwarding = """# Preserve Android bearer tokens across Apache/FastCGI/rewrite boundaries.\n<IfModule mod_rewrite.c>\nRewriteCond %{HTTP:Authorization} ^(.+)$\nRewriteRule .* - [E=HTTP_AUTHORIZATION:%1]\n</IfModule>\n<IfModule mod_setenvif.c>\nSetEnvIfNoCase Authorization \"^(.+)$\" HTTP_AUTHORIZATION=$1\n</IfModule>\n"""
if "E=HTTP_AUTHORIZATION" not in ht:
    anchor = "RewriteEngine On\n"
    if anchor not in ht:
        raise SystemExit("Cloud .htaccess RewriteEngine anchor missing")
    ht = ht.replace(anchor, anchor + forwarding, 1)
HTACCESS.write_text(ht, encoding="utf-8")

core = CORE.read_text(encoding="utf-8")
core = core.replace("const OG_VERSION = '1.3.27.5';", "const OG_VERSION = '1.3.27.6';", 1)
bearer = r'''function og_bearer(): string {
    $candidates = [
        (string)($_SERVER['HTTP_AUTHORIZATION'] ?? ''),
        (string)($_SERVER['REDIRECT_HTTP_AUTHORIZATION'] ?? ''),
        (string)($_SERVER['Authorization'] ?? ''),
    ];
    if (function_exists('getallheaders')) {
        $headers = getallheaders();
        if (is_array($headers)) {
            foreach ($headers as $name => $value) {
                if (strcasecmp((string)$name, 'Authorization') === 0) {
                    $candidates[] = is_array($value) ? implode(',', $value) : (string)$value;
                }
            }
        }
    } elseif (function_exists('apache_request_headers')) {
        $headers = apache_request_headers();
        if (is_array($headers)) {
            foreach ($headers as $name => $value) {
                if (strcasecmp((string)$name, 'Authorization') === 0) {
                    $candidates[] = is_array($value) ? implode(',', $value) : (string)$value;
                }
            }
        }
    }
    foreach ($candidates as $header) {
        if (preg_match('/^Bearer\s+(.+)$/i', trim($header), $m)) return trim($m[1]);
    }
    return '';
}
'''
core = replace_php_function(core, "function og_bearer(): string {", bearer)
CORE.write_text(core, encoding="utf-8")

version = VERSION.read_text(encoding="utf-8")
version = re.sub(r"OwnerGuard Cloud 1\.3\.27\.\d+", "OwnerGuard Cloud 1.3.27.6", version)
VERSION.write_text(version, encoding="utf-8")

ui = UI.read_text(encoding="utf-8")
ui = ui.replace("drawer132", "drawer133")
UI.write_text(ui, encoding="utf-8")

entry = """\nOwnerGuard Cloud 1.3.27.6\n- Forward Android Authorization bearer headers through Apache, FastCGI and rewrite environments.\n- Read bearer tokens from HTTP_AUTHORIZATION, REDIRECT_HTTP_AUTHORIZATION and request-header fallbacks.\n- Add authenticated upload integration and live header-forwarding verification to the release gate.\n"""
if CHANGELOG.is_file():
    changelog = CHANGELOG.read_text(encoding="utf-8")
    if "OwnerGuard Cloud 1.3.27.6" not in changelog:
        CHANGELOG.write_text(changelog.rstrip() + "\n" + entry, encoding="utf-8")

combined = HTACCESS.read_text(encoding="utf-8") + CORE.read_text(encoding="utf-8")
for token in (
    "E=HTTP_AUTHORIZATION:%1",
    "SetEnvIfNoCase Authorization",
    "REDIRECT_HTTP_AUTHORIZATION",
    "getallheaders",
    "const OG_VERSION = '1.3.27.6';",
):
    if token not in combined:
        raise SystemExit("Cloud upload repair output missing: " + token)
print("Applied OwnerGuard Cloud 1.3.27.6 bearer forwarding repair")
