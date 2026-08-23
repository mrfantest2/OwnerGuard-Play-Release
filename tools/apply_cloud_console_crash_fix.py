#!/usr/bin/env python3
"""Harden the native Cloud drawer workspace against WebView/activity crashes."""
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
CLOUD = ROOT / "app" / "src" / "main" / "java" / "com" / "fantest" / "ownerguard" / "CloudConsoleActivity.java"


def replace_once(text: str, old: str, new: str, label: str) -> str:
    if old not in text:
        raise SystemExit(f"Cloud crash-fix anchor missing in {label}: {old[:180]!r}")
    return text.replace(old, new, 1)


def replace_method(text: str, signature: str, replacement: str) -> str:
    start = text.find(signature)
    if start < 0:
        raise SystemExit(f"Cloud crash-fix method anchor missing: {signature}")
    brace = text.find("{", start)
    if brace < 0:
        raise SystemExit(f"Cloud crash-fix method brace missing: {signature}")
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
    raise SystemExit(f"Cloud crash-fix method did not terminate: {signature}")


text = CLOUD.read_text(encoding="utf-8")

text = replace_once(
    text,
    "import android.graphics.Color;\n",
    "import android.graphics.Color;\n"
    "import android.graphics.drawable.GradientDrawable;\n",
    "gradient import",
)
text = replace_once(
    text,
    "import android.os.Bundle;\n",
    "import android.os.Bundle;\n"
    "import android.os.Build;\n",
    "Build import",
)
text = replace_once(
    text,
    "import android.view.ViewGroup;\n",
    "import android.view.View;\n"
    "import android.view.ViewGroup;\n",
    "View import",
)
text = replace_once(
    text,
    "import android.webkit.SslErrorHandler;\n",
    "import android.webkit.SslErrorHandler;\n"
    "import android.webkit.RenderProcessGoneDetail;\n"
    "import android.webkit.WebResourceError;\n",
    "WebView failure imports",
)
text = replace_once(
    text,
    "import android.widget.FrameLayout;\n",
    "import android.widget.Button;\n"
    "import android.widget.FrameLayout;\n"
    "import android.widget.LinearLayout;\n"
    "import android.widget.ScrollView;\n"
    "import android.widget.TextView;\n",
    "fallback widget imports",
)

text = replace_once(
    text,
    "    private String basePath;\n",
    "    private String basePath;\n"
    "    private String currentRoute=\"index.php\";\n"
    "    private String currentTitle=\"OwnerGuard Cloud\";\n"
    "    private boolean handlingCloudFailure;\n",
    "Cloud state fields",
)

text = replace_once(
    text,
    '''        buildUi();
        String route = sanitizeRoute(getIntent().getStringExtra(EXTRA_ROUTE));
        String title = getIntent().getStringExtra(EXTRA_TITLE);
        loadRoute(route, title == null ? titleForRoute(route) : title);
''',
    '''        currentRoute = sanitizeRoute(getIntent().getStringExtra(EXTRA_ROUTE));
        String requestedTitle = getIntent().getStringExtra(EXTRA_TITLE);
        currentTitle = requestedTitle == null ? titleForRoute(currentRoute) : requestedTitle;
        launchCloudUiSafely();
''',
    "Cloud launch sequence",
)

text = replace_method(
    text,
    "    @Override protected void onDestroy() {",
    '''    @Override protected void onDestroy() {
        destroyWebViewSafely();
        drawerShell = null;
        super.onDestroy();
    }
''',
)

new_build_ui = r'''    private void buildUi() {
        drawerShell = new OwnerGuardDrawerShell(this, new OwnerGuardDrawerShell.Handler() {
            @Override public void onNavigate(String destination) {
                try {
                    if ("dashboard".equals(destination)
                            || "protection".equals(destination)
                            || "setup".equals(destination)) {
                        openLocalDestination(destination);
                        return;
                    }
                    String route = routeForDestination(destination);
                    loadRoute(route, titleForDestination(destination));
                } catch (Throwable error) {
                    showCloudFailure("Drawer destination could not open", error);
                }
            }

            @Override public void onRefresh() {
                try {
                    if (webView != null) webView.reload();
                    else launchCloudUiSafely();
                } catch (Throwable error) {
                    showCloudFailure("Cloud refresh failed", error);
                }
            }

            @Override public void onCloudSignOut() {
                CloudAccountManager.logout(CloudConsoleActivity.this);
                try {
                    CookieManager cookies = CookieManager.getInstance();
                    cookies.removeAllCookies(removed -> {
                        try { cookies.flush(); } catch (Throwable ignored) {}
                        Toast.makeText(CloudConsoleActivity.this,
                                "Cloud account signed out. Local protection remains available.",
                                Toast.LENGTH_SHORT).show();
                        openLocalDestination("setup");
                    });
                } catch (Throwable error) {
                    Toast.makeText(CloudConsoleActivity.this,
                            "Cloud account signed out", Toast.LENGTH_SHORT).show();
                    openLocalDestination("setup");
                }
            }
        });

        WebView created = new WebView(this);
        webView = created;
        created.setBackgroundColor(Color.rgb(7, 16, 29));
        // A software layer avoids vendor GPU/WebView crashes seen while opening the
        // Cloud Vault and Administration destinations. These pages do not need video/GPU rendering.
        created.setLayerType(View.LAYER_TYPE_SOFTWARE, null);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            created.setRendererPriorityPolicy(WebView.RENDERER_PRIORITY_BOUND, true);
        }
        created.getSettings().setJavaScriptEnabled(true);
        created.getSettings().setDomStorageEnabled(true);
        created.getSettings().setAllowFileAccess(false);
        created.getSettings().setAllowContentAccess(false);
        created.getSettings().setMixedContentMode(
                android.webkit.WebSettings.MIXED_CONTENT_NEVER_ALLOW);
        created.getSettings().setSaveFormData(false);
        created.getSettings().setSupportMultipleWindows(false);
        created.getSettings().setJavaScriptCanOpenWindowsAutomatically(false);
        created.getSettings().setMediaPlaybackRequiresUserGesture(true);
        String userAgent = created.getSettings().getUserAgentString();
        created.getSettings().setUserAgentString(
                userAgent + " OwnerGuard-Android/1.0.35 NativeDrawer StableWebView");

        try {
            CookieManager cookies = CookieManager.getInstance();
            cookies.setAcceptCookie(true);
            cookies.setAcceptThirdPartyCookies(created, false);
        } catch (Throwable cookieError) {
            recordCloudFailure("Cookie initialization", cookieError);
        }

        created.setWebViewClient(new WebViewClient() {
            @Override public boolean shouldOverrideUrlLoading(
                    WebView view, WebResourceRequest request) {
                try {
                    Uri uri = request == null ? null : request.getUrl();
                    if (isAllowed(uri)) return false;
                    openExternal(uri == null ? null : uri.toString());
                    return true;
                } catch (Throwable error) {
                    showCloudFailure("Cloud navigation failed", error);
                    return true;
                }
            }

            @Override public void onPageFinished(WebView view, String url) {
                super.onPageFinished(view, url);
                handlingCloudFailure = false;
                try { hideWebChrome(view); } catch (Throwable error) {
                    recordCloudFailure("Web chrome cleanup", error);
                }
                try { updateNativeRoute(url); } catch (Throwable error) {
                    recordCloudFailure("Route title update", error);
                }
            }

            @Override public void onReceivedError(WebView view, WebResourceRequest request,
                                                  WebResourceError error) {
                super.onReceivedError(view, request, error);
                if (request != null && request.isForMainFrame()) {
                    String detail = error == null || error.getDescription() == null
                            ? "Cloud page could not load"
                            : error.getDescription().toString();
                    showCloudFailure(detail, null);
                }
            }

            @Override public boolean onRenderProcessGone(
                    WebView view, RenderProcessGoneDetail detail) {
                String reason = detail != null && detail.didCrash()
                        ? "Android WebView renderer crashed"
                        : "Android WebView renderer stopped";
                showCloudFailure(reason, null);
                // Returning true tells Android that OwnerGuard handled the renderer loss.
                // Returning false would terminate the application process.
                return true;
            }

            @Override public void onReceivedSslError(
                    WebView view, SslErrorHandler handler, SslError error) {
                if (handler != null) handler.cancel();
                showCloudFailure("Cloud TLS verification failed", null);
            }
        });
        created.setDownloadListener((url, userAgentValue, contentDisposition,
                                     mimeType, contentLength) -> openExternal(url));

        drawerShell.content().addView(created, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
        ));
        setContentView(drawerShell.rootView());
    }
'''
text = replace_method(text, "    private void buildUi() {", new_build_ui)

new_load_route = r'''    private void loadRoute(String route, String title) {
        currentRoute = sanitizeRoute(route);
        currentTitle = title == null ? titleForRoute(currentRoute) : title;
        try {
            if (drawerShell != null) {
                drawerShell.setPage(currentTitle, activeDestinationForRoute(currentRoute));
            }
            if (webView == null) {
                launchCloudUiSafely();
                return;
            }
            webView.stopLoading();
            webView.loadUrl(cloudUrl(currentRoute));
        } catch (Throwable error) {
            showCloudFailure("Cloud page could not open", error);
        }
    }
'''
text = replace_method(text, "    private void loadRoute(String route, String title) {", new_load_route)

helpers = r'''    private void launchCloudUiSafely() {
        if (isFinishing()) return;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN_MR1 && isDestroyed()) return;
        handlingCloudFailure = false;
        try {
            destroyWebViewSafely();
            buildUi();
            loadRoute(currentRoute, currentTitle);
        } catch (Throwable error) {
            showCloudFailure("Embedded Cloud workspace could not start", error);
        }
    }

    private void showCloudFailure(String message, Throwable error) {
        if (isFinishing()) return;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN_MR1 && isDestroyed()) return;
        if (handlingCloudFailure && webView == null) return;
        handlingCloudFailure = true;
        recordCloudFailure(message, error);
        destroyWebViewSafely();
        drawerShell = null;

        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setBackgroundColor(Color.rgb(7, 16, 29));
        LinearLayout page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);
        page.setPadding(dp(20), dp(22), dp(20), dp(22));
        page.setBackgroundColor(Color.rgb(7, 16, 29));
        scroll.addView(page, new ScrollView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));

        TextView mark = fallbackText("⬢", 42, Color.rgb(99, 215, 255));
        mark.setGravity(android.view.Gravity.CENTER);
        page.addView(mark);
        TextView title = fallbackText("Cloud workspace recovery", 25, Color.WHITE);
        title.setGravity(android.view.Gravity.CENTER);
        page.addView(title, topMargin(8));

        String safeMessage = message == null || message.trim().isEmpty()
                ? "The embedded Cloud workspace stopped unexpectedly."
                : message.trim();
        TextView detail = fallbackText(
                safeMessage + "\n\nOwnerGuard stayed open. Local protection and encrypted evidence were not stopped.",
                15, Color.rgb(218, 230, 243));
        detail.setPadding(dp(15), dp(15), dp(15), dp(15));
        detail.setBackground(rounded(Color.rgb(16, 39, 64), 15));
        page.addView(detail, topMargin(16));

        Button retry = fallbackButton("Retry embedded Cloud", v -> launchCloudUiSafely(),
                Color.rgb(8, 145, 178));
        page.addView(retry, topMargin(18));
        Button browser = fallbackButton("Open this page in browser",
                v -> openExternal(cloudUrl(currentRoute)), Color.rgb(28, 42, 72));
        page.addView(browser, topMargin(10));
        Button dashboard = fallbackButton("Return to OwnerGuard dashboard",
                v -> openLocalDestination("dashboard"), Color.rgb(28, 42, 72));
        page.addView(dashboard, topMargin(10));

        String diagnostic = getSharedPreferences("owner_guard_cloud_diagnostics", MODE_PRIVATE)
                .getString("last_failure", "Cloud failure recorded");
        TextView diagnostics = fallbackText("Diagnostic: " + diagnostic,
                12, Color.rgb(159, 177, 198));
        diagnostics.setTextIsSelectable(true);
        page.addView(diagnostics, topMargin(16));

        OwnerGuardRequirements.applySafeInsets(scroll);
        setContentView(scroll);
    }

    private void destroyWebViewSafely() {
        WebView target = webView;
        webView = null;
        if (target == null) return;
        try { target.stopLoading(); } catch (Throwable ignored) {}
        try { target.setDownloadListener(null); } catch (Throwable ignored) {}
        try { target.setWebChromeClient(null); } catch (Throwable ignored) {}
        try { target.setWebViewClient(null); } catch (Throwable ignored) {}
        try {
            android.view.ViewParent parent = target.getParent();
            if (parent instanceof ViewGroup) ((ViewGroup) parent).removeView(target);
        } catch (Throwable ignored) {}
        try { target.removeAllViews(); } catch (Throwable ignored) {}
        try { target.destroy(); } catch (Throwable ignored) {}
    }

    private void recordCloudFailure(String stage, Throwable error) {
        try {
            StringBuilder value = new StringBuilder();
            value.append(stage == null ? "Cloud failure" : stage);
            if (error != null) {
                value.append(" • ").append(error.getClass().getSimpleName());
                if (error.getMessage() != null && !error.getMessage().trim().isEmpty()) {
                    String message = error.getMessage().replace('\n', ' ').replace('\r', ' ').trim();
                    if (message.length() > 180) message = message.substring(0, 180);
                    value.append(": ").append(message);
                }
            }
            value.append(" • route=").append(currentRoute)
                    .append(" • at=").append(System.currentTimeMillis());
            getSharedPreferences("owner_guard_cloud_diagnostics", MODE_PRIVATE)
                    .edit().putString("last_failure", value.toString()).apply();
        } catch (Throwable ignored) {}
    }

    private String cloudUrl(String route) {
        String root = baseUrl == null ? "" : baseUrl.trim();
        while (root.endsWith("/")) root = root.substring(0, root.length() - 1);
        return root + "/" + sanitizeRoute(route);
    }

    private TextView fallbackText(String value, int size, int color) {
        TextView view = new TextView(this);
        view.setText(value);
        view.setTextSize(size);
        view.setTextColor(color);
        return view;
    }

    private Button fallbackButton(String label, View.OnClickListener listener, int color) {
        Button button = new Button(this);
        button.setText(label);
        button.setAllCaps(false);
        button.setTextColor(Color.WHITE);
        button.setMinHeight(dp(52));
        button.setBackground(rounded(color, 15));
        button.setOnClickListener(listener);
        return button;
    }

    private GradientDrawable rounded(int color, int radiusDp) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(color);
        drawable.setCornerRadius(dp(radiusDp));
        return drawable;
    }

    private LinearLayout.LayoutParams topMargin(int value) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        params.topMargin = dp(value);
        return params;
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

'''
text = replace_once(
    text,
    "    private void updateNativeRoute(String url) {\n",
    helpers + "    private void updateNativeRoute(String url) {\n",
    "Cloud recovery helpers",
)

required = [
    "onRenderProcessGone",
    "return true;",
    "Cloud workspace recovery",
    "Retry embedded Cloud",
    "Open this page in browser",
    "destroyWebViewSafely()",
    "OwnerGuard-Android/1.0.35 NativeDrawer StableWebView",
    "View.LAYER_TYPE_SOFTWARE",
    "recordCloudFailure",
]
for token in required:
    if token not in text:
        raise SystemExit(f"Cloud crash-fix output incomplete: {token}")

CLOUD.write_text(text, encoding="utf-8")
print("Applied crash-safe Cloud drawer WebView with native recovery fallback")
