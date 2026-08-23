#!/usr/bin/env python3
"""Allow diagnostic screen recording in navigation shells and harden drawer clicks."""
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
JAVA = ROOT / "app" / "src" / "main" / "java" / "com" / "fantest" / "ownerguard"
MAIN = JAVA / "MainActivity.java"
CLOUD = JAVA / "CloudConsoleActivity.java"
DRAWER = JAVA / "OwnerGuardDrawerShell.java"


def replace_once(text: str, old: str, new: str, label: str) -> str:
    if old not in text:
        raise SystemExit(f"Debug drawer patch anchor missing in {label}: {old[:160]!r}")
    return text.replace(old, new, 1)


# Main navigation and Cloud administration are recordable for diagnostics.
# Sensitive vault/recovery activities are intentionally left unchanged and retain FLAG_SECURE.
main = MAIN.read_text(encoding="utf-8")
main = replace_once(
    main,
    "        getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE);\n",
    "        // Diagnostic shell recording is allowed. Sensitive vault/recovery activities remain protected.\n"
    "        getWindow().clearFlags(WindowManager.LayoutParams.FLAG_SECURE);\n",
    "MainActivity screen recording",
)
MAIN.write_text(main, encoding="utf-8")

cloud = CLOUD.read_text(encoding="utf-8")
cloud = replace_once(
    cloud,
    "        getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE);\n",
    "        // Diagnostic Cloud-console recording is allowed; recovered media remains protected elsewhere.\n"
    "        getWindow().clearFlags(WindowManager.LayoutParams.FLAG_SECURE);\n",
    "CloudConsoleActivity screen recording",
)
CLOUD.write_text(cloud, encoding="utf-8")

drawer = DRAWER.read_text(encoding="utf-8")
drawer = replace_once(
    drawer,
    "import android.os.Build;\n",
    "import android.os.Build;\nimport android.util.Log;\n",
    "drawer Log import",
)
drawer = replace_once(
    drawer,
    "import android.widget.TextView;\n",
    "import android.widget.TextView;\nimport android.widget.Toast;\n",
    "drawer Toast import",
)
drawer = replace_once(
    drawer,
    "final class OwnerGuardDrawerShell {\n",
    "final class OwnerGuardDrawerShell {\n    private static final String TAG = \"OwnerGuardDrawer\";\n",
    "drawer tag",
)
drawer = replace_once(
    drawer,
    "    private boolean open;\n",
    "    private boolean open;\n    private boolean navigating;\n",
    "drawer navigation guard",
)

old_footer = '''    void refreshFooter() {
        if (accountLine == null || cloudLine == null || cryptoLine == null || versionLine == null) {
            return;
        }
        boolean signedIn = CloudAccountManager.loggedIn(activity);
        accountLine.setText(signedIn
                ? "Signed in as @" + CloudAccountManager.username(activity)
                : "Cloud account not signed in");
        cloudLine.setText(signedIn ? "●  Cloud vault connected" : "○  Cloud vault optional — set up later");
        cloudLine.setTextColor(signedIn ? GOOD : MUTED);

        boolean crypto = VaultCrypto.selfTest(activity);
        cryptoLine.setText((crypto ? "●" : "○") + "  Cryptography "
                + (crypto ? "ready" : "requires attention"));
        cryptoLine.setTextColor(crypto ? GOOD : Color.rgb(255, 138, 138));
        versionLine.setText("●  Mandatory APK " + appVersion());
    }
'''
new_footer = '''    void refreshFooter() {
        if (accountLine == null || cloudLine == null || cryptoLine == null || versionLine == null) {
            return;
        }
        try {
            boolean signedIn = CloudAccountManager.loggedIn(activity);
            accountLine.setText(signedIn
                    ? "Signed in as @" + CloudAccountManager.username(activity)
                    : "Cloud account not signed in");
            cloudLine.setText(signedIn ? "●  Cloud vault connected" : "○  Cloud vault optional — set up later");
            cloudLine.setTextColor(signedIn ? GOOD : MUTED);

            boolean crypto = false;
            try {
                crypto = VaultCrypto.selfTest(activity);
            } catch (Throwable error) {
                Log.e(TAG, "Vault crypto footer check failed", error);
            }
            cryptoLine.setText((crypto ? "●" : "○") + "  Cryptography "
                    + (crypto ? "ready" : "requires attention"));
            cryptoLine.setTextColor(crypto ? GOOD : Color.rgb(255, 138, 138));
            versionLine.setText("●  Mandatory APK " + appVersion());
        } catch (Throwable error) {
            Log.e(TAG, "Drawer footer refresh failed", error);
            accountLine.setText("Account status unavailable");
            cloudLine.setText("○  Cloud status unavailable");
            cloudLine.setTextColor(MUTED);
            cryptoLine.setText("○  Cryptography status unavailable");
            cryptoLine.setTextColor(Color.rgb(255, 138, 138));
            versionLine.setText("●  OwnerGuard " + appVersion());
        }
    }
'''
drawer = replace_once(drawer, old_footer, new_footer, "drawer footer hardening")

old_click = '''        view.setOnClickListener(v -> {
            close();
            root.postDelayed(() -> handler.onNavigate(destination), 120L);
        });
    }

    private void open() {
'''
new_click = '''        view.setOnClickListener(v -> navigateSafely(destination));
    }

    private void navigateSafely(String destination) {
        if (navigating) return;
        navigating = true;
        close();
        root.post(() -> {
            try {
                if (activity.isFinishing()
                        || (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN_MR1
                        && activity.isDestroyed())) {
                    return;
                }
                handler.onNavigate(destination);
            } catch (Throwable error) {
                Log.e(TAG, "Drawer navigation failed: " + destination, error);
                Toast.makeText(activity,
                        "Could not open " + destination + ". The app stayed running; try again after refresh.",
                        Toast.LENGTH_LONG).show();
            } finally {
                root.postDelayed(() -> navigating = false, 250L);
            }
        });
    }

    private void open() {
'''
drawer = replace_once(drawer, old_click, new_click, "drawer click hardening")

drawer = replace_once(
    drawer,
    '''        refreshFooter();
        scrim.setVisibility(View.VISIBLE);
        scrim.animate().alpha(1f).setDuration(180L).start();
        drawer.animate().translationX(0f).setDuration(220L).start();
''',
    '''        refreshFooter();
        scrim.animate().cancel();
        drawer.animate().cancel();
        scrim.setVisibility(View.VISIBLE);
        scrim.animate().alpha(1f).setDuration(180L).start();
        drawer.animate().translationX(0f).setDuration(220L).start();
''',
    "drawer open animation",
)

drawer = replace_once(
    drawer,
    '''        open = false;
        scrim.animate()
                .alpha(0f)
                .setDuration(160L)
                .withEndAction(() -> scrim.setVisibility(View.GONE))
                .start();
        drawer.animate().translationX(-drawerWidth).setDuration(200L).start();
''',
    '''        open = false;
        scrim.animate().cancel();
        drawer.animate().cancel();
        scrim.animate()
                .alpha(0f)
                .setDuration(120L)
                .withEndAction(() -> {
                    if (!open) scrim.setVisibility(View.GONE);
                })
                .start();
        drawer.animate().translationX(-drawerWidth).setDuration(150L).start();
''',
    "drawer close animation",
)

required = [
    "clearFlags(WindowManager.LayoutParams.FLAG_SECURE)",
    "navigateSafely(destination)",
    "Drawer navigation failed:",
    "private boolean navigating;",
    "scrim.animate().cancel()",
    "Vault crypto footer check failed",
]
combined = MAIN.read_text(encoding="utf-8") + CLOUD.read_text(encoding="utf-8") + drawer
for token in required:
    if token not in combined:
        raise SystemExit(f"Debug recording/drawer output incomplete: {token}")

DRAWER.write_text(drawer, encoding="utf-8")
print("Enabled diagnostic shell recording and applied crash-safe drawer navigation")
