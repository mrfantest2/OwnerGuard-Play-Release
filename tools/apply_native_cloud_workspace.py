#!/usr/bin/env python3
"""Route OwnerGuard Cloud drawer destinations to the native Android workspace."""
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
JAVA = ROOT / "app" / "src" / "main" / "java" / "com" / "fantest" / "ownerguard"
MAIN = JAVA / "MainActivity.java"
SHELL = JAVA / "OwnerGuardDrawerShell.java"
MANIFEST = ROOT / "app" / "src" / "main" / "AndroidManifest.xml"


def replace_once(text: str, old: str, new: str, label: str) -> str:
    if old not in text:
        raise SystemExit(f"Native Cloud patch anchor missing in {label}: {old[:180]!r}")
    return text.replace(old, new, 1)


def replace_method(text: str, signature: str, replacement: str) -> str:
    start = text.find(signature)
    if start < 0:
        raise SystemExit(f"Native Cloud method anchor missing: {signature}")
    brace = text.find("{", start)
    if brace < 0:
        raise SystemExit(f"Native Cloud method brace missing: {signature}")
    depth = 0
    in_string = False
    escaped = False
    quote = ""
    i = brace
    while i < len(text):
        ch = text[i]
        if in_string:
            if escaped:
                escaped = False
            elif ch == "\\":
                escaped = True
            elif ch == quote:
                in_string = False
        else:
            if ch in ('"', "'"):
                in_string = True
                quote = ch
            elif ch == "{":
                depth += 1
            elif ch == "}":
                depth -= 1
                if depth == 0:
                    return text[:start] + replacement.rstrip() + text[i + 1:]
        i += 1
    raise SystemExit(f"Native Cloud method did not terminate: {signature}")


manifest = MANIFEST.read_text(encoding="utf-8")
if '<activity android:name=".NativeCloudActivity"' not in manifest:
    manifest = replace_once(
        manifest,
        '        <activity android:name=".CloudConsoleActivity" android:exported="false" />\n',
        '        <activity android:name=".CloudConsoleActivity" android:exported="false" />\n'
        '        <activity android:name=".NativeCloudActivity" android:exported="false" />\n',
        "Android manifest activity",
    )
MANIFEST.write_text(manifest, encoding="utf-8")

main = MAIN.read_text(encoding="utf-8")
main = replace_once(
    main,
    '        startActivity(CloudConsoleActivity.intent(this,route,title));',
    '        startActivity(NativeCloudActivity.intent(this,route,title));',
    "MainActivity native Cloud launch",
)
if 'NativeCloudActivity.intent(this,route,title)' not in main:
    raise SystemExit("MainActivity is not routing Cloud destinations natively")
MAIN.write_text(main, encoding="utf-8")

shell = SHELL.read_text(encoding="utf-8")
shell = replace_method(
    shell,
    "    private void configureSystemBars() {",
    '''    private void configureSystemBars() {
        // Samsung can expose a Window before its DecorView has a WindowInsetsController.
        // Avoid Window#getInsetsController during construction; dark bars only need colors
        // and the legacy light-icon flags cleared.
        try {
            android.view.Window window = activity.getWindow();
            if (window == null) return;
            window.setStatusBarColor(STATUS_BG);
            window.setNavigationBarColor(BG);
            View decor = window.getDecorView();
            if (decor == null) return;
            int flags = decor.getSystemUiVisibility();
            flags &= ~View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                flags &= ~View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR;
            }
            decor.setSystemUiVisibility(flags);
        } catch (Throwable ignored) {
            // System-bar decoration must never block the OwnerGuard UI.
        }
    }
''',
)
if "getWindow().getInsetsController()" in shell or "window.getInsetsController()" in shell:
    raise SystemExit("Unsafe early WindowInsetsController call remains in drawer shell")
if "System-bar decoration must never block" not in shell:
    raise SystemExit("Samsung-safe system-bar guard was not applied")
SHELL.write_text(shell, encoding="utf-8")

required = [
    '<activity android:name=".NativeCloudActivity" android:exported="false" />',
    'NativeCloudActivity.intent(this,route,title)',
    'System-bar decoration must never block',
]
combined = manifest + main + shell
missing = [token for token in required if token not in combined]
if missing:
    raise SystemExit("Native Cloud workspace patch incomplete: " + ", ".join(missing))

print("Applied native Cloud drawer routing and Samsung-safe system bars")
