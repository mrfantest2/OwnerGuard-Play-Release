package com.fantest.ownerguard;

import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Native Android implementation of the OwnerGuard Cloud Vault and Administration
 * workspace. No WebView is used for normal drawer navigation.
 */
public final class NativeCloudActivity extends SecureActivity {
    private static final int BG = Color.rgb(7,16,29);
    private static final int SURFACE = Color.rgb(15,34,56);
    private static final int SURFACE_2 = Color.rgb(20,48,76);
    private static final int TEXT = Color.rgb(236,244,252);
    private static final int MUTED = Color.rgb(161,181,204);
    private static final int ACCENT = Color.rgb(51,199,240);
    private static final int GOOD = Color.rgb(103,232,165);
    private static final int DANGER = Color.rgb(255,136,148);

    private static final String EXTRA_SECTION = "native_section";
    private static final String EXTRA_TITLE = "native_title";

    private OwnerGuardDrawerShell drawerShell;
    private FrameLayout content;
    private String currentSection = "incidents";
    private String currentTitle = "All incidents";
    private final LinkedHashMap<String,String> currentParams = new LinkedHashMap<>();
    private int requestGeneration;

    static Intent intent(Context context, String route, String title) {
        String section = sectionForRoute(route);
        return new Intent(context, NativeCloudActivity.class)
                .putExtra(EXTRA_SECTION, section)
                .putExtra(EXTRA_TITLE, title == null ? titleForSection(section) : title);
    }

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().clearFlags(WindowManager.LayoutParams.FLAG_SECURE);
        if (!AuthSession.isUnlocked()) {
            openLocalDestination("dashboard");
            return;
        }
        if (!OwnerGuardRequirements.allReady(this)) {
            startActivity(new Intent(this, MainActivity.class)
                    .putExtra("show_requirements", true)
                    .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP));
            finish();
            return;
        }
        if (!CloudAccountManager.loggedIn(this)) {
            Toast.makeText(this, "Create or link the Cloud vault from Local setup first.", Toast.LENGTH_LONG).show();
            openLocalDestination("setup");
            return;
        }
        currentSection = sanitizeSection(getIntent().getStringExtra(EXTRA_SECTION));
        String requestedTitle = getIntent().getStringExtra(EXTRA_TITLE);
        currentTitle = requestedTitle == null ? titleForSection(currentSection) : requestedTitle;
        try {
            buildNativeShell();
            loadCurrent();
        } catch (Throwable error) {
            showFatalNativeError(error);
        }
    }

    @Override protected void onResume() {
        super.onResume();
        if (!AuthSession.isUnlocked()) {
            openLocalDestination("dashboard");
            return;
        }
        if (!OwnerGuardRequirements.allReady(this)) {
            startActivity(new Intent(this, MainActivity.class)
                    .putExtra("show_requirements", true)
                    .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP));
            finish();
            return;
        }
        if (!CloudAccountManager.loggedIn(this)) {
            openLocalDestination("setup");
            return;
        }
        AppUpdateManager.onActivityResumed(this);
        if (drawerShell != null) drawerShell.refreshFooter();
    }

    @Override public void onBackPressed() {
        if (drawerShell != null && drawerShell.closeIfOpen()) return;
        if ("incident".equals(currentSection)) {
            currentSection = "incidents";
            currentTitle = "All incidents";
            currentParams.clear();
            loadCurrent();
            return;
        }
        super.onBackPressed();
    }

    private void buildNativeShell() {
        drawerShell = new OwnerGuardDrawerShell(this, new OwnerGuardDrawerShell.Handler() {
            @Override public void onNavigate(String destination) {
                if ("dashboard".equals(destination)
                        || "protection".equals(destination)
                        || "setup".equals(destination)) {
                    openLocalDestination(destination);
                    return;
                }
                currentSection = sanitizeSection(destination);
                currentTitle = titleForSection(currentSection);
                currentParams.clear();
                loadCurrent();
            }

            @Override public void onRefresh() {
                loadCurrent();
            }

            @Override public void onCloudSignOut() {
                CloudAccountManager.logout(NativeCloudActivity.this);
                Toast.makeText(NativeCloudActivity.this,
                        "Cloud account signed out. Local protection remains available.",
                        Toast.LENGTH_SHORT).show();
                openLocalDestination("setup");
            }
        });
        content = drawerShell.content();
        setContentView(drawerShell.rootView());
    }

    private void loadCurrent() {
        if (content == null) return;
        final int generation = ++requestGeneration;
        showLoading();
        NativeCloudClient.load(this, currentSection, currentParams,
                new NativeCloudClient.Callback() {
                    @Override public void onSuccess(JSONObject response) {
                        if (generation != requestGeneration || isFinishing()) return;
                        render(response);
                    }

                    @Override public void onError(String message) {
                        if (generation != requestGeneration || isFinishing()) return;
                        showLoadError(message);
                    }
                });
    }

    private void render(JSONObject response) {
        String section = sanitizeSection(response.optString("section", currentSection));
        currentSection = section;
        currentTitle = titleForSection(section);
        String active = "incident".equals(section) ? "incidents" : section;
        drawerShell.setPage(currentTitle, active);

        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setBackgroundColor(BG);
        LinearLayout page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);
        page.setPadding(dp(16), dp(16), dp(16), dp(30));
        scroll.addView(page, new ScrollView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        JSONObject user = response.optJSONObject("user");
        if (user != null) {
            String role = user.optString("role", "viewer");
            page.addView(infoStrip("Native Cloud workspace • " + role));
        }

        if ("dashboard".equals(section)) renderDashboard(page, response);
        else if ("incidents".equals(section)) renderIncidents(page, response);
        else if ("incident".equals(section)) renderIncident(page, response);
        else if ("owners".equals(section)) renderOwners(page, response);
        else if ("devices".equals(section)) renderDevices(page, response);
        else if ("users".equals(section)) renderUsers(page, response);
        else if ("audit".equals(section)) renderAudit(page, response);
        else if ("system".equals(section)) renderSystem(page, response);
        else if ("updates".equals(section)) renderUpdates(page, response);
        else page.addView(messageCard("Unsupported native section", DANGER));

        replaceContent(scroll);
    }

    private void renderDashboard(LinearLayout page, JSONObject response) {
        page.addView(sectionTitle("Cloud overview"), top(16));
        JSONObject metrics = response.optJSONObject("metrics");
        if (metrics == null) metrics = new JSONObject();
        page.addView(metricCard("Encrypted incidents", metrics.optInt("incidents"), "◆"), top(10));
        page.addView(metricCard("Encrypted objects", metrics.optInt("objects"), "▤"), top(10));
        page.addView(metricCard("Vault owners", metrics.optInt("owners"), "◎"), top(10));
        page.addView(metricCard("Source devices", metrics.optInt("devices"), "▣"), top(10));
        String crypto = metrics.optBoolean("crypto_ready", false) ? "Ready" : "Action required";
        page.addView(detailCard("Vault readiness",
                "Cryptography: " + crypto + "\nBackend: " + metrics.optString("crypto_backend", "Unknown")
                        + "\nLatest upload: " + time(metrics.optLong("latest_upload"))), top(14));
        page.addView(sectionTitle("Recent incidents"), top(20));
        appendIncidentCards(page, response.optJSONArray("items"));
    }

    private void renderIncidents(LinearLayout page, JSONObject response) {
        page.addView(searchBar("Search incident, owner, or device", "q"), top(12));
        JSONObject summary = response.optJSONObject("summary");
        if (summary != null) {
            page.addView(infoStrip("Showing " + summary.optInt("shown") + " of "
                    + summary.optInt("total") + " incidents"), top(10));
        }
        appendIncidentCards(page, response.optJSONArray("items"));
    }

    private void appendIncidentCards(LinearLayout page, JSONArray items) {
        if (items == null || items.length() == 0) {
            page.addView(messageCard("No encrypted incidents found", MUTED), top(12));
            return;
        }
        for (int i = 0; i < items.length(); i++) {
            JSONObject item = items.optJSONObject(i);
            if (item == null) continue;
            String event = item.optString("event", "Unknown incident");
            String owner = item.optString("owner", "Unknown owner");
            String ownerId = item.optString("owner_id", "");
            String detail = owner + "\n" + item.optString("device", "Unknown device")
                    + "\n" + item.optInt("objects") + " encrypted objects • "
                    + item.optInt("photos") + " photos • " + item.optInt("videos") + " videos"
                    + "\nReceived " + time(item.optLong("received_at"));
            LinearLayout card = clickableCard(event, detail);
            card.setOnClickListener(v -> {
                currentSection = "incident";
                currentTitle = "Incident metadata";
                currentParams.clear();
                currentParams.put("event", event);
                if (!ownerId.isEmpty()) currentParams.put("owner", ownerId);
                loadCurrent();
            });
            page.addView(card, top(10));
        }
    }

    private void renderIncident(LinearLayout page, JSONObject response) {
        JSONObject incident = response.optJSONObject("incident");
        if (incident == null) {
            page.addView(messageCard("Incident metadata unavailable", DANGER), top(12));
            return;
        }
        String event = incident.optString("event", "Incident");
        page.addView(detailCard(event,
                "Owner: " + incident.optString("owner", "Unknown")
                        + "\nCaptured: " + time(incident.optLong("captured_at"))
                        + "\nReceived: " + time(incident.optLong("received_at"))
                        + "\nEncrypted bytes: " + incident.optLong("bytes"), top(12));
        page.addView(messageCard(
                "Metadata is displayed without decryption. Opening plaintext media remains a separate audited recovery action.", GOOD), top(10));
        page.addView(sectionTitle("Encrypted objects"), top(20));
        JSONArray objects = incident.optJSONArray("objects");
        if (objects == null || objects.length() == 0) {
            page.addView(messageCard("No accessible encrypted objects", MUTED), top(10));
            return;
        }
        for (int i = 0; i < objects.length(); i++) {
            JSONObject object = objects.optJSONObject(i);
            if (object == null) continue;
            int objectId = object.optInt("id");
            LinearLayout card = verticalCard();
            card.addView(cardTitle(object.optString("name", "Encrypted object")));
            card.addView(cardBody(object.optString("mime", "application/octet-stream")
                    + "\nCaptured: " + time(object.optLong("captured_at"))
                    + "\nReceived: " + time(object.optLong("received_at"))
                    + "\nSize: " + object.optLong("size") + " B"
                    + "\nCrypto: OGC" + object.optInt("crypto_version")
                    + "\nSHA-256: " + object.optString("sha256", "")));
            Button recover = secondaryButton("Recover media in browser");
            recover.setOnClickListener(v -> openBrowser("decrypt.php?id=" + objectId));
            card.addView(recover, top(12));
            page.addView(card, top(10));
        }
    }

    private void renderOwners(LinearLayout page, JSONObject response) {
        page.addView(sectionTitle("Vault owners"), top(12));
        JSONArray items = response.optJSONArray("items");
        if (items == null || items.length() == 0) {
            page.addView(messageCard("No vault owners found", MUTED), top(10));
            return;
        }
        for (int i = 0; i < items.length(); i++) {
            JSONObject item = items.optJSONObject(i);
            if (item == null) continue;
            String id = item.optString("id", "");
            LinearLayout card = clickableCard(item.optString("name", "Vault owner"),
                    "@" + item.optString("username", "legacy")
                            + "\n" + item.optInt("incidents") + " incidents • "
                            + item.optInt("objects") + " objects • "
                            + item.optInt("devices") + " devices"
                            + "\nLatest activity: " + time(item.optLong("last")));
            card.setOnClickListener(v -> {
                currentSection = "incidents";
                currentTitle = "All incidents";
                currentParams.clear();
                currentParams.put("owner", id);
                loadCurrent();
            });
            page.addView(card, top(10));
        }
    }

    private void renderDevices(LinearLayout page, JSONObject response) {
        page.addView(sectionTitle("Source devices"), top(12));
        JSONArray items = response.optJSONArray("items");
        if (items == null || items.length() == 0) {
            page.addView(messageCard("No devices found", MUTED), top(10));
            return;
        }
        for (int i = 0; i < items.length(); i++) {
            JSONObject item = items.optJSONObject(i);
            if (item == null) continue;
            String name = item.optString("name", "Unknown device");
            LinearLayout card = clickableCard(name,
                    item.optString("id", "") + "\n"
                            + item.optInt("incidents") + " incidents • "
                            + item.optInt("objects") + " objects • "
                            + item.optInt("owners") + " owners"
                            + "\n" + item.optInt("photos") + " photos • "
                            + item.optInt("videos") + " videos"
                            + "\nLatest activity: " + time(item.optLong("last")));
            card.setOnClickListener(v -> {
                currentSection = "incidents";
                currentTitle = "All incidents";
                currentParams.clear();
                currentParams.put("device", name);
                loadCurrent();
            });
            page.addView(card, top(10));
        }
    }

    private void renderUsers(LinearLayout page, JSONObject response) {
        page.addView(sectionTitle("Cloud accounts"), top(12));
        JSONArray items = response.optJSONArray("items");
        if (items == null || items.length() == 0) {
            page.addView(messageCard("No Cloud accounts found", MUTED), top(10));
            return;
        }
        for (int i = 0; i < items.length(); i++) {
            JSONObject item = items.optJSONObject(i);
            if (item == null) continue;
            int id = item.optInt("id");
            boolean enabled = item.optBoolean("enabled", false);
            boolean self = item.optBoolean("self", false);
            LinearLayout card = verticalCard();
            card.addView(cardTitle(item.optString("display_name", "Cloud user")));
            card.addView(cardBody("@" + item.optString("username", "")
                    + " • " + item.optString("role", "viewer")
                    + "\nStatus: " + (enabled ? "Enabled" : "Disabled")
                    + "\nCreated: " + time(item.optLong("created_at"))
                    + "\nLast login: " + time(item.optLong("last_login_at"))));
            Button action = secondaryButton(self ? "Active administrator" : (enabled ? "Disable account" : "Enable account"));
            action.setEnabled(!self);
            action.setOnClickListener(v -> confirmToggleUser(id, item.optString("username", "user"), enabled));
            card.addView(action, top(12));
            page.addView(card, top(10));
        }
    }

    private void confirmToggleUser(int id, String username, boolean currentlyEnabled) {
        new AlertDialog.Builder(this)
                .setTitle(currentlyEnabled ? "Disable account" : "Enable account")
                .setMessage("Update @" + username + "? Active API sessions will be revoked.")
                .setPositiveButton(currentlyEnabled ? "Disable" : "Enable", (d,w) -> {
                    showLoading();
                    NativeCloudClient.toggleUser(this, id, new NativeCloudClient.Callback() {
                        @Override public void onSuccess(JSONObject response) {
                            Toast.makeText(NativeCloudActivity.this,
                                    response.optString("message", "User updated"), Toast.LENGTH_SHORT).show();
                            loadCurrent();
                        }
                        @Override public void onError(String message) { showLoadError(message); }
                    });
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void renderAudit(LinearLayout page, JSONObject response) {
        page.addView(searchBar("Search user, action, or detail", "q"), top(12));
        JSONArray items = response.optJSONArray("items");
        if (items == null || items.length() == 0) {
            page.addView(messageCard("No matching audit events", MUTED), top(10));
            return;
        }
        for (int i = 0; i < items.length(); i++) {
            JSONObject item = items.optJSONObject(i);
            if (item == null) continue;
            page.addView(detailCard(item.optString("action", "Audit event"),
                    item.optString("username", "system") + " • " + time(item.optLong("created_at"))
                            + "\n" + item.optString("detail", "")), top(10));
        }
    }

    private void renderSystem(LinearLayout page, JSONObject response) {
        JSONObject system = response.optJSONObject("system");
        if (system == null) {
            page.addView(messageCard("System status unavailable", DANGER), top(10));
            return;
        }
        page.addView(metricCard("Cloud version", system.optString("cloud_version", "Unknown"), "◉"), top(12));
        page.addView(detailCard("Cryptographic backend",
                system.optString("crypto_backend", "Unavailable")
                        + "\nOverall: " + (system.optBoolean("crypto_ready") ? "Operational" : "Action required")
                        + "\nPHP OpenSSL: " + yesNo(system.optBoolean("php_openssl"))
                        + "\nOpenSSL CLI: " + yesNo(system.optBoolean("openssl_cli"))
                        + "\nData directory writable: " + yesNo(system.optBoolean("data_writable"))
                        + "\nPHP: " + system.optString("php_version", "Unknown"), top(10));
        page.addView(detailCard("Database inventory",
                "Users: " + system.optInt("users")
                        + "\nEncrypted objects: " + system.optInt("objects")
                        + "\nAudit events: " + system.optInt("audit_events"), top(10));
    }

    private void renderUpdates(LinearLayout page, JSONObject response) {
        JSONObject update = response.optJSONObject("update");
        if (update == null) {
            page.addView(messageCard("Update manifest unavailable", DANGER), top(10));
            return;
        }
        page.addView(metricCard("Published Android version",
                update.optString("version_name", "Unavailable"), "⇧"), top(12));
        page.addView(detailCard("Mandatory updater",
                "Version code: " + update.optInt("version_code")
                        + "\nMinimum version code: " + update.optInt("minimum_version_code")
                        + "\nPolicy: " + (update.optBoolean("mandatory") ? "Mandatory" : "Optional")
                        + "\nAvailable: " + yesNo(update.optBoolean("available"))
                        + "\nPackage: " + update.optString("package", "")
                        + "\nSize: " + update.optLong("size") + " B"
                        + "\nSHA-256: " + update.optString("sha256", "")
                        + "\n\n" + update.optString("release_notes", ""), top(10));
        String apk = update.optString("apk_url", "");
        if (!apk.isEmpty()) {
            Button download = primaryButton("Open published APK");
            download.setOnClickListener(v -> openAbsoluteOrRelative(apk));
            page.addView(download, top(12));
        }
    }

    private LinearLayout searchBar(String hint, String key) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        EditText input = new EditText(this);
        input.setHint(hint);
        input.setSingleLine(true);
        input.setTextColor(TEXT);
        input.setHintTextColor(MUTED);
        input.setBackground(rounded(SURFACE_2, 12));
        input.setPadding(dp(12), 0, dp(12), 0);
        input.setText(currentParams.get(key));
        row.addView(input, new LinearLayout.LayoutParams(0, dp(48), 1f));
        Button search = primaryButton("Search");
        LinearLayout.LayoutParams buttonParams = new LinearLayout.LayoutParams(dp(96), dp(48));
        buttonParams.leftMargin = dp(8);
        row.addView(search, buttonParams);
        search.setOnClickListener(v -> {
            String value = input.getText().toString().trim();
            if (value.isEmpty()) currentParams.remove(key); else currentParams.put(key, value);
            loadCurrent();
        });
        return row;
    }

    private void showLoading() {
        if (drawerShell != null) drawerShell.setPage(currentTitle,
                "incident".equals(currentSection) ? "incidents" : currentSection);
        LinearLayout page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);
        page.setGravity(Gravity.CENTER);
        page.setBackgroundColor(BG);
        TextView title = text("Loading " + currentTitle + "…", 20, TEXT);
        title.setGravity(Gravity.CENTER);
        page.addView(title);
        page.addView(text("Native encrypted metadata request", 14, MUTED), top(8));
        replaceContent(page);
    }

    private void showLoadError(String message) {
        LinearLayout page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);
        page.setPadding(dp(20), dp(30), dp(20), dp(30));
        page.setBackgroundColor(BG);
        page.addView(text("Native Cloud could not load", 26, TEXT));
        page.addView(messageCard(message == null ? "Unknown Cloud error" : message, DANGER), top(16));
        Button retry = primaryButton("Retry native Cloud");
        retry.setOnClickListener(v -> loadCurrent());
        page.addView(retry, top(16));
        Button browser = secondaryButton("Open this page in browser");
        browser.setOnClickListener(v -> openBrowser(browserRoute()));
        page.addView(browser, top(10));
        Button dashboard = secondaryButton("Return to OwnerGuard dashboard");
        dashboard.setOnClickListener(v -> openLocalDestination("dashboard"));
        page.addView(dashboard, top(10));
        replaceContent(page);
    }

    private void showFatalNativeError(Throwable error) {
        String message = error == null ? "Native workspace initialization failed"
                : error.getClass().getSimpleName() + ": " + String.valueOf(error.getMessage());
        LinearLayout page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);
        page.setPadding(dp(20), dp(36), dp(20), dp(30));
        page.setBackgroundColor(BG);
        page.addView(text("Native Cloud workspace recovery", 26, TEXT));
        page.addView(messageCard(message, DANGER), top(16));
        Button browser = primaryButton("Open Cloud in browser");
        browser.setOnClickListener(v -> openBrowser(browserRoute()));
        page.addView(browser, top(16));
        Button dashboard = secondaryButton("Return to dashboard");
        dashboard.setOnClickListener(v -> openLocalDestination("dashboard"));
        page.addView(dashboard, top(10));
        OwnerGuardRequirements.applySafeInsets(page);
        setContentView(page);
    }

    private void replaceContent(View view) {
        if (content == null) return;
        content.removeAllViews();
        content.addView(view, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
    }

    private void openLocalDestination(String destination) {
        startActivity(new Intent(this, MainActivity.class)
                .putExtra("drawer_destination", destination)
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP));
        finish();
    }

    private void openBrowser(String route) {
        openAbsoluteOrRelative(CloudBackupManager.baseUrl(this) + "/" + route);
    }

    private void openAbsoluteOrRelative(String url) {
        String target = url;
        if (target.startsWith("/")) target = CloudBackupManager.baseUrl(this) + target;
        try {
            startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(target)));
        } catch (Throwable error) {
            Toast.makeText(this, "No browser is available", Toast.LENGTH_LONG).show();
        }
    }

    private String browserRoute() {
        if ("incident".equals(currentSection)) {
            String event = currentParams.get("event");
            String owner = currentParams.get("owner");
            String route = "incident.php?event=" + Uri.encode(event == null ? "" : event);
            if (owner != null && !owner.isEmpty()) route += "&owner=" + Uri.encode(owner);
            return route;
        }
        String route = currentSection + ".php";
        if ("dashboard".equals(currentSection)) route = "dashboard.php";
        return route;
    }

    private LinearLayout clickableCard(String title, String detail) {
        LinearLayout card = verticalCard();
        card.setClickable(true);
        card.setFocusable(true);
        card.addView(cardTitle(title));
        card.addView(cardBody(detail));
        TextView open = text("Open native details  ›", 13, ACCENT);
        card.addView(open, top(10));
        return card;
    }

    private LinearLayout detailCard(String title, String detail) {
        LinearLayout card = verticalCard();
        card.addView(cardTitle(title));
        card.addView(cardBody(detail));
        return card;
    }

    private LinearLayout metricCard(String label, int value, String icon) {
        return metricCard(label, Integer.toString(value), icon);
    }

    private LinearLayout metricCard(String label, String value, String icon) {
        LinearLayout card = verticalCard();
        TextView glyph = text(icon, 26, ACCENT);
        card.addView(glyph);
        TextView number = text(value, 25, TEXT);
        number.setPadding(0, dp(5), 0, 0);
        card.addView(number);
        card.addView(text(label, 13, MUTED), top(3));
        return card;
    }

    private LinearLayout verticalCard() {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(15), dp(14), dp(15), dp(14));
        card.setBackground(rounded(SURFACE, 15));
        card.setElevation(dp(1));
        return card;
    }

    private TextView cardTitle(String value) {
        return text(value, 18, TEXT);
    }

    private TextView cardBody(String value) {
        TextView body = text(value, 14, MUTED);
        body.setLineSpacing(0f, 1.16f);
        body.setPadding(0, dp(8), 0, 0);
        body.setTextIsSelectable(true);
        return body;
    }

    private TextView sectionTitle(String value) {
        TextView title = text(value, 20, TEXT);
        title.setPadding(dp(2), 0, 0, 0);
        return title;
    }

    private TextView infoStrip(String value) {
        TextView view = text(value, 13, MUTED);
        view.setPadding(dp(13), dp(10), dp(13), dp(10));
        view.setBackground(rounded(Color.rgb(10,29,48), 12));
        return view;
    }

    private TextView messageCard(String value, int accent) {
        TextView view = text(value, 14, TEXT);
        view.setPadding(dp(14), dp(13), dp(14), dp(13));
        GradientDrawable background = rounded(SURFACE, 14);
        background.setStroke(dp(1), accent);
        view.setBackground(background);
        return view;
    }

    private Button primaryButton(String label) {
        Button button = new Button(this);
        button.setText(label);
        button.setTextColor(Color.WHITE);
        button.setTextSize(15);
        button.setAllCaps(false);
        button.setBackground(rounded(Color.rgb(15,151,190), 14));
        button.setMinHeight(dp(50));
        return button;
    }

    private Button secondaryButton(String label) {
        Button button = new Button(this);
        button.setText(label);
        button.setTextColor(TEXT);
        button.setTextSize(15);
        button.setAllCaps(false);
        button.setBackground(rounded(Color.rgb(28,48,80), 14));
        button.setMinHeight(dp(50));
        return button;
    }

    private TextView text(String value, int sp, int color) {
        TextView view = new TextView(this);
        view.setText(value == null ? "" : value);
        view.setTextSize(sp);
        view.setTextColor(color);
        return view;
    }

    private GradientDrawable rounded(int color, int radiusDp) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(color);
        drawable.setCornerRadius(dp(radiusDp));
        return drawable;
    }

    private LinearLayout.LayoutParams top(int marginDp) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        params.topMargin = dp(marginDp);
        return params;
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private String time(long epoch) {
        if (epoch <= 0) return "Never";
        return new SimpleDateFormat("dd MMM yyyy, HH:mm", Locale.getDefault())
                .format(new Date(epoch * 1000L));
    }

    private static String yesNo(boolean value) {
        return value ? "Ready" : "Unavailable";
    }

    private static String sanitizeSection(String section) {
        if (section == null) return "incidents";
        String value = section.trim().toLowerCase(Locale.US);
        switch (value) {
            case "dashboard":
            case "incidents":
            case "incident":
            case "owners":
            case "devices":
            case "users":
            case "audit":
            case "system":
            case "updates":
                return value;
            default:
                return "incidents";
        }
    }

    private static String sectionForRoute(String route) {
        if (route == null) return "incidents";
        String value = route.trim().toLowerCase(Locale.US);
        int query = value.indexOf('?');
        if (query >= 0) value = value.substring(0, query);
        if (value.endsWith(".php")) value = value.substring(0, value.length() - 4);
        if ("index".equals(value)) return "dashboard";
        return sanitizeSection(value);
    }

    private static String titleForSection(String section) {
        switch (sanitizeSection(section)) {
            case "dashboard": return "Cloud dashboard";
            case "incidents": return "All incidents";
            case "incident": return "Incident metadata";
            case "owners": return "Vault owners";
            case "devices": return "Devices";
            case "users": return "Users";
            case "audit": return "Audit log";
            case "system": return "System status";
            case "updates": return "Update management";
            default: return "OwnerGuard Cloud";
        }
    }
}
