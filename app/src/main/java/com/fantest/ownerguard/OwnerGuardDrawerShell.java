package com.fantest.ownerguard;

import android.app.Activity;
import android.content.pm.PackageInfo;
import android.graphics.Color;
import android.graphics.Insets;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.view.DisplayCutout;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.LinkedHashMap;
import java.util.Map;

final class OwnerGuardDrawerShell {
    interface Handler {
        void onNavigate(String destination);
        void onRefresh();
        void onCloudSignOut();
    }

    private static final int BG = Color.rgb(7, 16, 29);
    private static final int STATUS_BG = Color.rgb(5, 13, 23);
    private static final int HEADER_BG = Color.rgb(8, 19, 33);
    private static final int SURFACE = Color.rgb(11, 26, 44);
    private static final int SURFACE_2 = Color.rgb(16, 39, 64);
    private static final int LINE = Color.rgb(38, 58, 83);
    private static final int TEXT = Color.rgb(232, 241, 251);
    private static final int MUTED = Color.rgb(159, 177, 198);
    private static final int ACCENT = Color.rgb(99, 215, 255);
    private static final int GOOD = Color.rgb(103, 232, 165);

    private final Activity activity;
    private final Handler handler;
    private final FrameLayout root;
    private final LinearLayout app;
    private final View statusBarSpace;
    private final LinearLayout header;
    private final FrameLayout content;
    private final LinearLayout drawer;
    private final View scrim;
    private final TextView pageTitle;
    private final Map<String, TextView> items = new LinkedHashMap<>();
    private final int drawerWidth;

    private TextView accountLine;
    private TextView cloudLine;
    private TextView cryptoLine;
    private TextView versionLine;
    private boolean open;
    private int lastTopInset = -1;
    private int lastBottomInset = -1;
    private int lastLeftInset = -1;
    private int lastRightInset = -1;

    OwnerGuardDrawerShell(Activity activity, Handler handler) {
        this.activity = activity;
        this.handler = handler;
        this.drawerWidth = Math.min(
                (int) (activity.getResources().getDisplayMetrics().widthPixels * 0.82f),
                dp(320)
        );

        configureSystemBars();

        root = new FrameLayout(activity);
        root.setBackgroundColor(BG);

        app = new LinearLayout(activity);
        app.setOrientation(LinearLayout.VERTICAL);
        app.setBackgroundColor(BG);
        root.addView(app, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
        ));

        statusBarSpace = new View(activity);
        statusBarSpace.setBackgroundColor(STATUS_BG);
        app.addView(statusBarSpace, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                0
        ));

        header = new LinearLayout(activity);
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.setPadding(dp(10), 0, dp(10), 0);
        header.setBackgroundColor(HEADER_BG);
        header.setElevation(dp(4));
        app.addView(header, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dp(60)
        ));

        TextView menu = headerButton("☰", "Open navigation");
        menu.setOnClickListener(v -> open());
        header.addView(menu, square(42));

        pageTitle = new TextView(activity);
        pageTitle.setTextColor(TEXT);
        pageTitle.setTextSize(21);
        pageTitle.setGravity(Gravity.CENTER_VERTICAL);
        pageTitle.setSingleLine(true);
        pageTitle.setEllipsize(android.text.TextUtils.TruncateAt.END);
        LinearLayout.LayoutParams titleParams = new LinearLayout.LayoutParams(
                0,
                ViewGroup.LayoutParams.MATCH_PARENT,
                1f
        );
        titleParams.setMargins(dp(10), 0, dp(8), 0);
        header.addView(pageTitle, titleParams);

        TextView refresh = headerButton("↻", "Refresh current page");
        refresh.setOnClickListener(v -> handler.onRefresh());
        header.addView(refresh, square(42));

        content = new FrameLayout(activity);
        app.addView(content, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                0,
                1f
        ));

        scrim = new View(activity);
        scrim.setBackgroundColor(Color.argb(170, 0, 7, 14));
        scrim.setVisibility(View.GONE);
        scrim.setAlpha(0f);
        scrim.setOnClickListener(v -> close());
        root.addView(scrim, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
        ));

        drawer = new LinearLayout(activity);
        drawer.setOrientation(LinearLayout.VERTICAL);
        drawer.setBackgroundColor(SURFACE);
        drawer.setElevation(dp(18));
        root.addView(drawer, new FrameLayout.LayoutParams(
                drawerWidth,
                ViewGroup.LayoutParams.MATCH_PARENT,
                Gravity.START
        ));
        buildDrawer();
        drawer.post(() -> drawer.setTranslationX(-drawerWidth));

        installSystemInsetHandling();
    }

    View rootView() {
        return root;
    }

    FrameLayout content() {
        return content;
    }

    void setPage(String title, String activeDestination) {
        pageTitle.setText(title);
        for (Map.Entry<String, TextView> entry : items.entrySet()) {
            boolean active = entry.getKey().equals(activeDestination);
            TextView item = entry.getValue();
            item.setTextColor(active ? Color.WHITE : MUTED);
            item.setBackground(active
                    ? rounded(Color.rgb(20, 62, 94), 11)
                    : rounded(Color.TRANSPARENT, 11));
        }
        refreshFooter();
    }

    boolean closeIfOpen() {
        if (!open) return false;
        close();
        return true;
    }

    void refreshFooter() {
        if (accountLine == null || cloudLine == null || cryptoLine == null || versionLine == null) {
            return;
        }
        boolean signedIn = CloudAccountManager.loggedIn(activity);
        accountLine.setText(signedIn
                ? "Signed in as @" + CloudAccountManager.username(activity)
                : "Cloud account not signed in");
        cloudLine.setText(signedIn ? "●  Cloud account ready" : "○  Cloud sign-in required");
        cloudLine.setTextColor(signedIn ? GOOD : MUTED);

        boolean crypto = VaultCrypto.selfTest(activity);
        cryptoLine.setText((crypto ? "●" : "○") + "  Cryptography "
                + (crypto ? "ready" : "requires attention"));
        cryptoLine.setTextColor(crypto ? GOOD : Color.rgb(255, 138, 138));
        versionLine.setText("●  Mandatory APK " + appVersion());
    }

    private void configureSystemBars() {
        activity.getWindow().setStatusBarColor(STATUS_BG);
        activity.getWindow().setNavigationBarColor(BG);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            WindowInsetsController controller = activity.getWindow().getInsetsController();
            if (controller != null) {
                controller.setSystemBarsAppearance(
                        0,
                        WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS
                                | WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS
                );
            }
        } else {
            int flags = activity.getWindow().getDecorView().getSystemUiVisibility();
            flags &= ~View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                flags &= ~View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR;
            }
            activity.getWindow().getDecorView().setSystemUiVisibility(flags);
        }
    }

    private void installSystemInsetHandling() {
        root.setOnApplyWindowInsetsListener((view, windowInsets) -> {
            int top;
            int bottom;
            int left;
            int right;

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                Insets statusAndCutout = windowInsets.getInsets(
                        WindowInsets.Type.statusBars() | WindowInsets.Type.displayCutout()
                );
                Insets navigation = windowInsets.getInsets(WindowInsets.Type.navigationBars());
                top = statusAndCutout.top;
                left = Math.max(statusAndCutout.left, navigation.left);
                right = Math.max(statusAndCutout.right, navigation.right);
                bottom = navigation.bottom;
            } else {
                top = windowInsets.getSystemWindowInsetTop();
                bottom = windowInsets.getSystemWindowInsetBottom();
                left = windowInsets.getSystemWindowInsetLeft();
                right = windowInsets.getSystemWindowInsetRight();
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    DisplayCutout cutout = windowInsets.getDisplayCutout();
                    if (cutout != null) {
                        top = Math.max(top, cutout.getSafeInsetTop());
                        bottom = Math.max(bottom, cutout.getSafeInsetBottom());
                        left = Math.max(left, cutout.getSafeInsetLeft());
                        right = Math.max(right, cutout.getSafeInsetRight());
                    }
                }
            }

            applySystemInsets(top, bottom, left, right);
            return windowInsets;
        });
        root.post(root::requestApplyInsets);
    }

    private void applySystemInsets(int top, int bottom, int left, int right) {
        if (top == lastTopInset && bottom == lastBottomInset
                && left == lastLeftInset && right == lastRightInset) {
            return;
        }
        lastTopInset = top;
        lastBottomInset = bottom;
        lastLeftInset = left;
        lastRightInset = right;

        ViewGroup.LayoutParams spacerParams = statusBarSpace.getLayoutParams();
        spacerParams.height = Math.max(0, top);
        statusBarSpace.setLayoutParams(spacerParams);

        header.setPadding(dp(10) + Math.max(0, left), 0,
                dp(10) + Math.max(0, right), 0);
        content.setPadding(Math.max(0, left), 0,
                Math.max(0, right), Math.max(0, bottom));
        drawer.setPadding(Math.max(0, left), Math.max(0, top),
                0, Math.max(0, bottom));
    }

    private void buildDrawer() {
        LinearLayout brand = new LinearLayout(activity);
        brand.setGravity(Gravity.CENTER_VERTICAL);
        brand.setPadding(dp(14), 0, dp(10), 0);
        brand.setBackgroundColor(HEADER_BG);
        drawer.addView(brand, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dp(64)
        ));

        TextView mark = text("⬢", 25, ACCENT);
        mark.setGravity(Gravity.CENTER);
        mark.setBackground(rounded(Color.rgb(28, 88, 125), 12));
        brand.addView(mark, square(40));

        LinearLayout brandCopy = new LinearLayout(activity);
        brandCopy.setOrientation(LinearLayout.VERTICAL);
        brandCopy.setPadding(dp(11), 0, 0, 0);
        brandCopy.addView(text("OwnerGuard", 17, TEXT));
        brandCopy.addView(text("Secure incident workspace", 11, MUTED));
        brand.addView(brandCopy, new LinearLayout.LayoutParams(
                0,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                1f
        ));

        TextView close = headerButton("×", "Close navigation");
        close.setTextSize(25);
        close.setOnClickListener(v -> close());
        brand.addView(close, square(40));

        ScrollView scroll = new ScrollView(activity);
        scroll.setFillViewport(true);
        LinearLayout nav = new LinearLayout(activity);
        nav.setOrientation(LinearLayout.VERTICAL);
        nav.setPadding(dp(10), dp(10), dp(10), dp(18));
        scroll.addView(nav, new ScrollView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
        ));
        drawer.addView(scroll, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                0,
                1f
        ));

        section(nav, "OVERVIEW");
        item(nav, "dashboard", "⌂", "Dashboard");

        section(nav, "VAULT");
        item(nav, "incidents", "◆", "All incidents");
        item(nav, "owners", "◎", "Vault owners");
        item(nav, "devices", "▣", "Devices");

        section(nav, "ADMINISTRATION");
        item(nav, "users", "♙", "Users");
        item(nav, "audit", "≡", "Audit log");
        item(nav, "system", "◉", "System status");
        item(nav, "updates", "⇧", "Update management");

        LinearLayout footer = new LinearLayout(activity);
        footer.setOrientation(LinearLayout.VERTICAL);
        footer.setPadding(dp(14), dp(12), dp(14), dp(14));
        footer.setBackgroundColor(Color.rgb(6, 17, 29));
        drawer.addView(footer, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
        ));

        cloudLine = footerLine();
        cryptoLine = footerLine();
        versionLine = footerLine();
        footer.addView(cloudLine);
        footer.addView(cryptoLine);
        footer.addView(versionLine);

        LinearLayout account = new LinearLayout(activity);
        account.setGravity(Gravity.CENTER_VERTICAL);
        account.setPadding(dp(11), dp(9), dp(8), dp(9));
        account.setBackground(rounded(SURFACE_2, 12));
        LinearLayout.LayoutParams accountParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
        );
        accountParams.topMargin = dp(11);
        footer.addView(account, accountParams);

        accountLine = text("", 12, TEXT);
        accountLine.setSingleLine(true);
        accountLine.setEllipsize(android.text.TextUtils.TruncateAt.END);
        account.addView(accountLine, new LinearLayout.LayoutParams(
                0,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                1f
        ));

        TextView signOut = text("Sign out", 12, TEXT);
        signOut.setGravity(Gravity.CENTER);
        signOut.setPadding(dp(10), dp(7), dp(10), dp(7));
        signOut.setBackground(rounded(Color.rgb(64, 31, 42), 9));
        signOut.setOnClickListener(v -> {
            close();
            handler.onCloudSignOut();
        });
        account.addView(signOut);
        refreshFooter();
    }

    private void section(LinearLayout nav, String label) {
        TextView view = text(label, 10, Color.rgb(114, 137, 162));
        view.setAllCaps(true);
        view.setLetterSpacing(0.12f);
        view.setPadding(dp(12), dp(14), dp(12), dp(5));
        nav.addView(view);
    }

    private void item(LinearLayout nav, String destination, String icon, String label) {
        TextView view = text(icon + "   " + label, 15, MUTED);
        view.setGravity(Gravity.CENTER_VERTICAL);
        view.setPadding(dp(13), 0, dp(12), 0);
        view.setSingleLine(true);
        view.setBackground(rounded(Color.TRANSPARENT, 11));
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dp(46)
        );
        params.topMargin = dp(3);
        nav.addView(view, params);
        items.put(destination, view);
        view.setOnClickListener(v -> {
            close();
            root.postDelayed(() -> handler.onNavigate(destination), 120L);
        });
    }

    private void open() {
        if (open) return;
        open = true;
        refreshFooter();
        scrim.setVisibility(View.VISIBLE);
        scrim.animate().alpha(1f).setDuration(180L).start();
        drawer.animate().translationX(0f).setDuration(220L).start();
    }

    private void close() {
        if (!open) return;
        open = false;
        scrim.animate()
                .alpha(0f)
                .setDuration(160L)
                .withEndAction(() -> scrim.setVisibility(View.GONE))
                .start();
        drawer.animate().translationX(-drawerWidth).setDuration(200L).start();
    }

    private String appVersion() {
        try {
            PackageInfo info = activity.getPackageManager()
                    .getPackageInfo(activity.getPackageName(), 0);
            return info.versionName == null ? "1.0.31" : info.versionName;
        } catch (Throwable ignored) {
            return "1.0.31";
        }
    }

    private TextView headerButton(String value, String description) {
        TextView view = text(value, 21, TEXT);
        view.setGravity(Gravity.CENTER);
        view.setContentDescription(description);
        view.setBackground(rounded(SURFACE_2, 11));
        return view;
    }

    private TextView footerLine() {
        TextView line = text("", 12, MUTED);
        line.setPadding(0, dp(3), 0, dp(3));
        return line;
    }

    private TextView text(String value, float size, int color) {
        TextView view = new TextView(activity);
        view.setText(value);
        view.setTextSize(size);
        view.setTextColor(color);
        return view;
    }

    private GradientDrawable rounded(int color, int radiusDp) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(color);
        drawable.setCornerRadius(dp(radiusDp));
        if (color != Color.TRANSPARENT) drawable.setStroke(dp(1), LINE);
        return drawable;
    }

    private LinearLayout.LayoutParams square(int sizeDp) {
        return new LinearLayout.LayoutParams(dp(sizeDp), dp(sizeDp));
    }

    private int dp(int value) {
        return Math.round(value * activity.getResources().getDisplayMetrics().density);
    }
}
