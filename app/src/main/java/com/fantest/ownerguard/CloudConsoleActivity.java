package com.fantest.ownerguard;

import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.net.Uri;
import android.net.http.SslError;
import android.os.Bundle;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.webkit.CookieManager;
import android.webkit.SslErrorHandler;
import android.webkit.WebResourceRequest;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;
import android.widget.Toast;

import java.net.URI;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

public final class CloudConsoleActivity extends SecureActivity {
    private static final String EXTRA_ROUTE = "route";
    private static final String EXTRA_TITLE = "title";
    private static final Set<String> ALLOWED_ROUTES = new HashSet<>(Arrays.asList(
            "index.php", "dashboard.php", "incidents.php", "owners.php", "devices.php",
            "users.php", "audit.php", "system.php", "updates.php", "incident.php",
            "decrypt.php"
    ));

    private OwnerGuardDrawerShell drawerShell;
    private WebView webView;
    private String baseUrl;
    private String baseHost;
    private String basePath;

    static Intent intent(Context context, String route, String title) {
        return new Intent(context, CloudConsoleActivity.class)
                .putExtra(EXTRA_ROUTE, sanitizeRoute(route))
                .putExtra(EXTRA_TITLE, title == null ? "OwnerGuard Cloud" : title);
    }

    private static String sanitizeRoute(String route) {
        if (route == null) return "index.php";
        String clean = route.trim();
        int query = clean.indexOf('?');
        String file = query >= 0 ? clean.substring(0, query) : clean;
        return ALLOWED_ROUTES.contains(file) ? clean : "index.php";
    }

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE);
        if (!AuthSession.isUnlocked()) {
            returnToUnlock();
            return;
        }
        if (!CloudAccountManager.loggedIn(this)) {
            returnToLocalSetup("Cloud vault setup is optional. Create or link it after local setup.");
            return;
        }

        baseUrl = CloudBackupManager.baseUrl(this);
        try {
            URI parsed = URI.create(baseUrl);
            baseHost = parsed.getHost();
            basePath = parsed.getPath() == null ? "" : parsed.getPath();
            if (baseHost == null || baseHost.trim().isEmpty()
                    || !"https".equalsIgnoreCase(parsed.getScheme())) {
                throw new IllegalArgumentException("HTTPS OwnerGuard Cloud URL required");
            }
        } catch (Throwable error) {
            Toast.makeText(this, "Invalid OwnerGuard Cloud URL", Toast.LENGTH_LONG).show();
            returnToLocalSetup(null);
            return;
        }

        buildUi();
        String route = sanitizeRoute(getIntent().getStringExtra(EXTRA_ROUTE));
        String title = getIntent().getStringExtra(EXTRA_TITLE);
        loadRoute(route, title == null ? titleForRoute(route) : title);
    }

    @Override protected void onResume() {
        super.onResume();
        if (!AuthSession.isUnlocked()) {
            returnToUnlock();
            return;
        }
        if (!CloudAccountManager.loggedIn(this)) {
            returnToLocalSetup("Cloud account is not linked. Local protection remains available.");
            return;
        }
        AppUpdateManager.onActivityResumed(this);
        if (drawerShell != null) drawerShell.refreshFooter();
    }

    @Override protected void onDestroy() {
        if (webView != null) {
            webView.stopLoading();
            webView.loadUrl("about:blank");
            webView.setWebViewClient(null);
            webView.removeAllViews();
            webView.destroy();
            webView = null;
        }
        super.onDestroy();
    }

    @Override public void onBackPressed() {
        if (drawerShell != null && drawerShell.closeIfOpen()) return;
        if (webView != null && webView.canGoBack()) webView.goBack();
        else super.onBackPressed();
    }

    private void buildUi() {
        drawerShell = new OwnerGuardDrawerShell(this, new OwnerGuardDrawerShell.Handler() {
            @Override public void onNavigate(String destination) {
                if ("dashboard".equals(destination)
                        || "protection".equals(destination)
                        || "setup".equals(destination)) {
                    openLocalDestination(destination);
                    return;
                }
                String route = routeForDestination(destination);
                loadRoute(route, titleForDestination(destination));
            }

            @Override public void onRefresh() {
                if (webView != null) webView.reload();
            }

            @Override public void onCloudSignOut() {
                CloudAccountManager.logout(CloudConsoleActivity.this);
                CookieManager cookies = CookieManager.getInstance();
                cookies.removeAllCookies(removed -> {
                    cookies.flush();
                    Toast.makeText(CloudConsoleActivity.this,
                            "Cloud account signed out. Local protection remains available.",
                            Toast.LENGTH_SHORT).show();
                    openLocalDestination("setup");
                });
            }
        });

        webView = new WebView(this);
        webView.setBackgroundColor(Color.rgb(7, 16, 29));
        webView.getSettings().setJavaScriptEnabled(true);
        webView.getSettings().setDomStorageEnabled(true);
        webView.getSettings().setAllowFileAccess(false);
        webView.getSettings().setAllowContentAccess(false);
        webView.getSettings().setMixedContentMode(
                android.webkit.WebSettings.MIXED_CONTENT_NEVER_ALLOW);
        webView.getSettings().setSaveFormData(false);
        webView.getSettings().setSupportMultipleWindows(false);
        webView.getSettings().setJavaScriptCanOpenWindowsAutomatically(false);
        String userAgent = webView.getSettings().getUserAgentString();
        webView.getSettings().setUserAgentString(
                userAgent + " OwnerGuard-Android/1.0.32 NativeDrawer");

        CookieManager cookies = CookieManager.getInstance();
        cookies.setAcceptCookie(true);
        cookies.setAcceptThirdPartyCookies(webView, false);

        webView.setWebViewClient(new WebViewClient() {
            @Override public boolean shouldOverrideUrlLoading(
                    WebView view, WebResourceRequest request) {
                Uri uri = request.getUrl();
                if (isAllowed(uri)) return false;
                openExternal(uri == null ? null : uri.toString());
                return true;
            }

            @Override public void onPageFinished(WebView view, String url) {
                super.onPageFinished(view, url);
                hideWebChrome(view);
                updateNativeRoute(url);
            }

            @Override public void onReceivedSslError(
                    WebView view, SslErrorHandler handler, SslError error) {
                handler.cancel();
                Toast.makeText(CloudConsoleActivity.this,
                        "Cloud TLS verification failed", Toast.LENGTH_LONG).show();
            }
        });
        webView.setDownloadListener((url, userAgentValue, contentDisposition,
                                     mimeType, contentLength) -> openExternal(url));

        drawerShell.content().addView(webView, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
        ));
        setContentView(drawerShell.rootView());
    }

    private void loadRoute(String route, String title) {
        String clean = sanitizeRoute(route);
        if (drawerShell != null) {
            drawerShell.setPage(title, activeDestinationForRoute(clean));
        }
        if (webView != null) webView.loadUrl(baseUrl + "/" + clean);
    }

    private void updateNativeRoute(String url) {
        if (drawerShell == null || url == null) return;
        try {
            Uri uri = Uri.parse(url);
            String file = uri.getLastPathSegment();
            if (file == null || file.trim().isEmpty()) file = "index.php";
            drawerShell.setPage(titleForRoute(file), activeDestinationForRoute(file));
        } catch (Throwable ignored) {
        }
    }

    private void hideWebChrome(WebView view) {
        String script = "(function(){"
                + "var id='og-native-android-shell';"
                + "var s=document.getElementById(id);"
                + "if(!s){s=document.createElement('style');s.id=id;"
                + "s.textContent='.app-drawer,.drawer-scrim,.app-header{display:none!important}'"
                + "+'.app-content{margin-left:0!important}'"
                + "+'.wrap{max-width:none!important;padding-top:14px!important}'"
                + "+'body{padding:0!important}';"
                + "(document.head||document.documentElement).appendChild(s);}" 
                + "})();";
        view.evaluateJavascript(script, null);
    }

    private boolean isAllowed(Uri uri) {
        if (uri == null || !"https".equalsIgnoreCase(uri.getScheme())) return false;
        if (baseHost == null || !baseHost.equalsIgnoreCase(uri.getHost())) return false;
        String path = uri.getPath() == null ? "" : uri.getPath();
        return path.startsWith(basePath + "/") || path.equals(basePath);
    }

    private void returnToUnlock() {
        startActivity(new Intent(this, MainActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP));
        finish();
    }

    private void returnToLocalSetup(String message) {
        if (message != null && !message.trim().isEmpty()) {
            Toast.makeText(this, message, Toast.LENGTH_LONG).show();
        }
        openLocalDestination("setup");
    }

    private void openLocalDestination(String destination) {
        startActivity(new Intent(this, MainActivity.class)
                .putExtra("drawer_destination", destination)
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP));
        finish();
    }

    private void openExternal(String url) {
        if (url == null || url.trim().isEmpty()) return;
        try {
            startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)));
        } catch (Throwable error) {
            Toast.makeText(this, "No browser is available", Toast.LENGTH_LONG).show();
        }
    }

    private static String routeForDestination(String destination) {
        if ("incidents".equals(destination)) return "incidents.php";
        if ("owners".equals(destination)) return "owners.php";
        if ("devices".equals(destination)) return "devices.php";
        if ("users".equals(destination)) return "users.php";
        if ("audit".equals(destination)) return "audit.php";
        if ("system".equals(destination)) return "system.php";
        if ("updates".equals(destination)) return "updates.php";
        return "index.php";
    }

    private static String titleForDestination(String destination) {
        if ("incidents".equals(destination)) return "All incidents";
        if ("owners".equals(destination)) return "Vault owners";
        if ("devices".equals(destination)) return "Devices";
        if ("users".equals(destination)) return "Users";
        if ("audit".equals(destination)) return "Audit log";
        if ("system".equals(destination)) return "System status";
        if ("updates".equals(destination)) return "Update management";
        return "Dashboard";
    }

    private static String activeDestinationForRoute(String route) {
        String file = route == null ? "" : route;
        int query = file.indexOf('?');
        if (query >= 0) file = file.substring(0, query);
        if ("incident.php".equals(file) || "decrypt.php".equals(file)) return "incidents";
        if ("index.php".equals(file) || "dashboard.php".equals(file)) return "dashboard";
        if (file.endsWith(".php")) file = file.substring(0, file.length() - 4);
        return file;
    }

    private static String titleForRoute(String route) {
        String file = route == null ? "" : route;
        int query = file.indexOf('?');
        if (query >= 0) file = file.substring(0, query);
        if ("index.php".equals(file) || "dashboard.php".equals(file)) return "Dashboard";
        if ("incidents.php".equals(file)) return "All incidents";
        if ("owners.php".equals(file)) return "Vault owners";
        if ("devices.php".equals(file)) return "Devices";
        if ("users.php".equals(file)) return "Users";
        if ("audit.php".equals(file)) return "Audit log";
        if ("system.php".equals(file)) return "System status";
        if ("updates.php".equals(file)) return "Update management";
        if ("incident.php".equals(file)) return "Incident metadata";
        if ("decrypt.php".equals(file)) return "Recover media";
        return "OwnerGuard Cloud";
    }
}
