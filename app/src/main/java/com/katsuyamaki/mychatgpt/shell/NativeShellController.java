package com.katsuyamaki.mychatgpt.shell;

import android.app.Activity;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.res.Configuration;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.content.Intent;
import android.net.Uri;
import android.provider.Settings;
import android.text.TextUtils;
import android.os.Build;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowInsets;
import android.view.WindowManager;
import android.webkit.WebView;
import android.widget.FrameLayout;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.ScrollView;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import com.katsuyamaki.mychatgpt.R;
import com.katsuyamaki.mychatgpt.site.ChatGptSiteContract;
import com.katsuyamaki.mychatgpt.webview.ChatTabsController;
import com.katsuyamaki.mychatgpt.notifications.NotificationController;
import com.katsuyamaki.mychatgpt.notifications.NotificationStore;

import java.util.List;

/**
 * Native MyChatGPT shell chrome layered around the main WebView.
 *
 * Owns wallpaper passthrough, transparency controls, control placement,
 * app-local UI scale, text zoom and the user-triggered force-reload action.
 */
public final class NativeShellController {

    private static final String PREFS = "mychatgpt_native_shell";
    private static final String KEY_BACKDROP = "backdrop_opacity_percent";
    private static final String KEY_SURFACE_OPACITY = "chatgpt_surface_opacity_percent";
    private static final String KEY_CORNER = "controls_corner"; // Legacy corner migration.
    private static final String KEY_POSITION_X = "controls_position_x_fraction";
    private static final String KEY_POSITION_Y = "controls_position_y_fraction";
    private static final String KEY_UI_SCALE = "ui_scale_percent";
    private static final String KEY_TEXT_SCALE = "text_scale_percent";

    private static final int DEFAULT_BACKDROP = 30;
    private static final int DEFAULT_SURFACE_OPACITY = 0;
    private static final int DEFAULT_UI_SCALE = 100;
    private static final int DEFAULT_TEXT_SCALE = 100;

    private static final int MIN_UI_SCALE = 75;
    private static final int MAX_UI_SCALE = 150;
    private static final int UI_SCALE_STEP = 5;

    private static final int MIN_TEXT_SCALE = 80;
    private static final int MAX_TEXT_SCALE = 160;
    private static final int TEXT_SCALE_STEP = 10;

    private static final int CORNER_TOP_LEFT = 0;
    private static final int CORNER_TOP_RIGHT = 1;
    private static final int CORNER_BOTTOM_RIGHT = 2;
    private static final int CORNER_BOTTOM_LEFT = 3;
    private static final int HANDLE_SIZE_DP = 48;
    private static final int EDGE_MARGIN_DP = 8;
    private static final int PANEL_GAP_DP = 6;
    private static final int PAGE_HOME = 0;
    private static final int PAGE_TUNE = 1;
    private static final int PAGE_NOTIFICATIONS = 2;
    private static final int PAGE_TABS = 3;
    private static final int PAGE_SIZE = 30;

    public interface Host {
        WebView getMainWebView();
        void forceReloadCurrentChat();
        NotificationController getNotificationController();
        void openNotification(long id);
        void requestNotificationPermission();
        void sendTestNotification();
        void testSiteCapture();
        void testUnmarkedCapture();
        void copyMonitorDiagnostics();
        List<ChatTabsController.TabInfo> getTabs();
        boolean addTab();
        void selectTab(String id);
        void closeTab(String id);
        void renameTab(String id, String title);
        void moveTab(String id, int direction);
    }

    private final Activity activity;
    private final FrameLayout root;
    private final Host host;
    private final SharedPreferences prefs;

    private LinearLayout panel;
    private FrameLayout menuButton;
    private TextView unreadBadge;
    private TextView headerTitle;
    private TextView backButton;
    private ScrollView pageScroll;
    private LinearLayout pageContent;
    private int currentPage = PAGE_HOME;
    private int notificationOffset;
    // Hidden by default: historical copy/sidebar/download alerts and explicit tests.
    private boolean showOtherHistory;
    private TextView backdropLabel;
    private TextView surfaceOpacityLabel;
    private TextView uiScaleLabel;
    private TextView textScaleLabel;

    private int backdropPercent;
    private int surfaceOpacityPercent;
    // Normalized coordinates preserve the pin across fold/cover and rotation.
    private float positionX;
    private float positionY;
    private int uiScalePercent;
    private int textScalePercent;

    private int insetLeft;
    private int insetTop;
    private int insetRight;
    private int insetBottom;

    private final int touchSlop;
    private float touchDownRawX;
    private float touchDownRawY;
    private int dragStartLeft;
    private int dragStartTop;
    private boolean dragging;
    private boolean gestureCancelled;

    private final View.OnLayoutChangeListener rootLayoutListener =
            (view, left, top, right, bottom, oldLeft, oldTop, oldRight, oldBottom) -> {
                if (right - left != oldRight - oldLeft
                        || bottom - top != oldBottom - oldTop) {
                    updateFloatingPlacement();
                }
            };

    public NativeShellController(Activity activity, FrameLayout root, Host host) {
        this.activity = activity;
        this.root = root;
        this.host = host;
        this.prefs = activity.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        this.touchSlop = ViewConfiguration.get(activity).getScaledTouchSlop();

        backdropPercent = clamp(prefs.getInt(KEY_BACKDROP, DEFAULT_BACKDROP), 0, 100);
        surfaceOpacityPercent = clamp(prefs.getInt(KEY_SURFACE_OPACITY, DEFAULT_SURFACE_OPACITY), 0, 100);
        // Preserve the previous corner until the user first drags or resets.
        int legacyCorner = clamp(prefs.getInt(KEY_CORNER, CORNER_TOP_RIGHT), 0, 3);
        float defaultX = (legacyCorner == CORNER_TOP_RIGHT
                || legacyCorner == CORNER_BOTTOM_RIGHT) ? 1f : 0f;
        float defaultY = (legacyCorner == CORNER_BOTTOM_LEFT
                || legacyCorner == CORNER_BOTTOM_RIGHT) ? 1f : 0f;
        positionX = clampFraction(prefs.getFloat(KEY_POSITION_X, defaultX));
        positionY = clampFraction(prefs.getFloat(KEY_POSITION_Y, defaultY));
        uiScalePercent = clamp(
                prefs.getInt(KEY_UI_SCALE, DEFAULT_UI_SCALE),
                MIN_UI_SCALE,
                MAX_UI_SCALE);
        textScalePercent = clamp(
                prefs.getInt(KEY_TEXT_SCALE, DEFAULT_TEXT_SCALE),
                MIN_TEXT_SCALE,
                MAX_TEXT_SCALE);

        configureWallpaperWindow();
        applyBackdrop();
        buildControls();
        installInsetTracking();
    }

    public void applyToMainWebView(WebView webView) {
        if (webView == null) return;
        webView.setBackgroundColor(Color.TRANSPARENT);
        // Keep the WebView itself fully opaque. Like Termux Launcher's wallpaper mode,
        // transparency belongs to painted background surfaces, not View alpha; fading
        // the whole WebView forces expensive full-surface compositing on every edit.
        if (webView.getAlpha() != 1f) webView.setAlpha(1f);
        applyTextScale(webView);
        applyUiScale(webView);
    }

    public void onPageFinished(WebView webView) {
        // Activity only forwards managed chat tabs, never popup WebViews.
        if (webView == null) return;
        webView.setBackgroundColor(Color.TRANSPARENT);
        webView.evaluateJavascript(ChatGptSiteContract.WALLPAPER_TRANSPARENCY_JS, null);
        applyUiScale(webView);
    }

    public void onConfigurationChanged() {
        configureWallpaperWindow();
        applyBackdrop();
        updateFloatingPlacement();
        applyToMainWebView(host.getMainWebView());
    }

    public boolean closePanelIfOpen() {
        if (panel != null && panel.getVisibility() == View.VISIBLE) {
            panel.setVisibility(View.GONE);
            return true;
        }
        return false;
    }

    public void destroy() {
        root.removeOnLayoutChangeListener(rootLayoutListener);
        root.setOnApplyWindowInsetsListener(null);
        if (panel != null) root.removeView(panel);
        if (menuButton != null) root.removeView(menuButton);
        panel = null;
        menuButton = null;
        unreadBadge = null;
        pageScroll = null;
        pageContent = null;
        headerTitle = null;
        backButton = null;
    }

    public void showNotificationsPage() {
        if (panel == null) return;
        notificationOffset = 0;
        showOtherHistory = false;
        showPage(PAGE_NOTIFICATIONS);
        openPanel();
    }

    /** The unread counter and inbox stay in sync with newly captured site toasts. */
    public void refreshNotifications() {
        if (unreadBadge == null) return;
        NotificationController notifications = host.getNotificationController();
        int unread = notifications == null ? 0 : notifications.unreadCount();
        unreadBadge.setVisibility(unread == 0 ? View.GONE : View.VISIBLE);
        unreadBadge.setText(unread > 99 ? "99+" : Integer.toString(unread));
        if (currentPage == PAGE_NOTIFICATIONS && panel != null
                && panel.getVisibility() == View.VISIBLE) {
            int previousScroll = pageScroll.getScrollY();
            buildNotificationPage();
            pageScroll.post(() -> pageScroll.scrollTo(0, previousScroll));
        }
    }

    /** Rebuild the open tab picker when titles or routes change. */
    public void refreshTabs() {
        if (currentPage == PAGE_TABS && pageScroll != null && panel != null
                && panel.getVisibility() == View.VISIBLE) {
            int scroll = pageScroll.getScrollY();
            buildTabsPage();
            pageScroll.post(() -> pageScroll.scrollTo(0, scroll));
        }
    }

    private void configureWallpaperWindow() {
        Window window = activity.getWindow();
        if (window == null) return;

        window.addFlags(WindowManager.LayoutParams.FLAG_SHOW_WALLPAPER);
        window.clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
        window.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
        window.setStatusBarColor(Color.TRANSPARENT);
        window.setNavigationBarColor(Color.TRANSPARENT);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            window.setStatusBarContrastEnforced(false);
            window.setNavigationBarContrastEnforced(false);
        }

        // Match the validated prototype: white system-bar icons over the
        // wallpaper rather than theme-dependent opaque bar surfaces.
        View decor = window.getDecorView();
        int visibility = decor.getSystemUiVisibility();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            visibility &= ~View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR;
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            visibility &= ~View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR;
        }
        decor.setSystemUiVisibility(visibility);
    }

    private void buildControls() {
        // The launcher and panel are separate overlays, so expanding the
        // menu never resizes or displaces the pinned launcher.
        // Compact launcher: the application's own icon replaces the TUNE text.
        menuButton = new FrameLayout(activity);
        menuButton.setContentDescription("MyChatGPT menu. Drag to move, tap to open.");
        menuButton.setElevation(dp(16));
        menuButton.setBackground(makeRoundedBackground(
                Color.argb(225, 30, 30, 30), 24));
        ImageView icon = new ImageView(activity);
        icon.setImageResource(R.mipmap.ic_launcher);
        icon.setScaleType(ImageView.ScaleType.FIT_CENTER);
        FrameLayout.LayoutParams iconParams = new FrameLayout.LayoutParams(
                dp(42), dp(42), Gravity.CENTER);
        menuButton.addView(icon, iconParams);
        unreadBadge = makeButton("");
        unreadBadge.setTextSize(9f);
        unreadBadge.setBackground(makeRoundedBackground(Color.rgb(178, 40, 40), 10));
        FrameLayout.LayoutParams badgeParams = new FrameLayout.LayoutParams(
                dp(21), dp(21), Gravity.TOP | Gravity.END);
        badgeParams.topMargin = -dp(3);
        badgeParams.rightMargin = -dp(3);
        menuButton.addView(unreadBadge, badgeParams);

        panel = new LinearLayout(activity);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setPadding(dp(10), dp(10), dp(10), dp(10));
        panel.setBackground(makeRoundedBackground(
                Color.argb(244, 24, 24, 24), 16));
        panel.setVisibility(View.GONE);
        panel.setElevation(dp(16));
        panel.setClickable(true);

        LinearLayout header = new LinearLayout(activity);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);
        backButton = makeButton("BACK");
        header.addView(backButton, new LinearLayout.LayoutParams(dp(56), dp(34)));
        headerTitle = makeLabel("MyChatGPT", 15f);
        headerTitle.setGravity(Gravity.CENTER_VERTICAL);
        headerTitle.setTypeface(null, android.graphics.Typeface.BOLD);
        LinearLayout.LayoutParams titleParams = new LinearLayout.LayoutParams(0, dp(36), 1f);
        titleParams.leftMargin = dp(8);
        header.addView(headerTitle, titleParams);
        TextView close = makeButton("×");
        header.addView(close, new LinearLayout.LayoutParams(dp(34), dp(34)));
        panel.addView(header, fullWidthWrap());

        pageScroll = new ScrollView(activity);
        pageScroll.setFillViewport(false);
        pageScroll.setVerticalScrollBarEnabled(true);
        pageContent = new LinearLayout(activity);
        pageContent.setOrientation(LinearLayout.VERTICAL);
        pageContent.setPadding(dp(2), dp(8), dp(2), dp(4));
        pageScroll.addView(pageContent);
        panel.addView(pageScroll);

        backButton.setOnClickListener(v -> showPage(PAGE_HOME));
        close.setOnClickListener(v -> panel.setVisibility(View.GONE));
        menuButton.setOnClickListener(v -> {
            if (closePanelIfOpen()) return;
            showPage(PAGE_HOME);
            openPanel();
        });
        menuButton.setOnTouchListener(this::onMenuTouch);

        showPage(PAGE_HOME);
        refreshNotifications();

        // Remain under the page's loading overlay, above the WebView.
        root.addView(panel, Math.min(1, root.getChildCount()),
                new FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                        Gravity.TOP | Gravity.LEFT));
        root.addView(menuButton, Math.min(2, root.getChildCount()),
                new FrameLayout.LayoutParams(dp(HANDLE_SIZE_DP),
                        dp(HANDLE_SIZE_DP), Gravity.TOP | Gravity.LEFT));
        updateFloatingPlacement();
    }

    private void showPage(int page) {
        if (pageContent == null) return;
        currentPage = page;
        pageContent.removeAllViews();
        backdropLabel = null;
        surfaceOpacityLabel = null;
        uiScaleLabel = null;
        textScaleLabel = null;
        backButton.setVisibility(page == PAGE_HOME ? View.INVISIBLE : View.VISIBLE);
        headerTitle.setText(page == PAGE_TUNE ? "Tune"
                : page == PAGE_NOTIFICATIONS ? "Notifications"
                : page == PAGE_TABS ? "Chat tabs" : "MyChatGPT");
        if (page == PAGE_TUNE) buildTunePage();
        else if (page == PAGE_NOTIFICATIONS) buildNotificationPage();
        else if (page == PAGE_TABS) buildTabsPage();
        else buildHomePage();
        updatePanelPlacement();
        pageScroll.post(() -> pageScroll.scrollTo(0, 0));
    }

    private void buildHomePage() {
        TextView intro = makeLabel("Choose a section", 12f);
        intro.setPadding(dp(4), 0, 0, dp(10));
        pageContent.addView(intro);

        int tabCount = host.getTabs().size();
        TextView tabs = makeButton("TABS  ·  " + tabCount + " open sessions");
        pageContent.addView(tabs, fullWidthButton(45));
        tabs.setOnClickListener(v -> showPage(PAGE_TABS));

        TextView tune = makeButton("TUNE  ·  Appearance and layout");
        LinearLayout.LayoutParams tuneParams = fullWidthButton(45);
        tuneParams.topMargin = dp(8);
        pageContent.addView(tune, tuneParams);
        tune.setOnClickListener(v -> showPage(PAGE_TUNE));

        NotificationController notifications = host.getNotificationController();
        int unread = notifications == null ? 0 : notifications.unreadCount();
        TextView inbox = makeButton("NOTIFICATIONS  ·  " + unread + " unread");
        LinearLayout.LayoutParams inboxParams = fullWidthButton(45);
        inboxParams.topMargin = dp(8);
        pageContent.addView(inbox, inboxParams);
        inbox.setOnClickListener(v -> {
            notificationOffset = 0;
            showOtherHistory = false;
            showPage(PAGE_NOTIFICATIONS);
        });
    }

    private void buildTabsPage() {
        pageContent.removeAllViews();
        List<ChatTabsController.TabInfo> tabs = host.getTabs();

        TextView add = makeButton("+ NEW CHAT TAB  ·  " + tabs.size()
                + "/" + ChatTabsController.MAX_TABS);
        pageContent.addView(add, fullWidthButton(42));
        boolean hasRoom = tabs.size() < ChatTabsController.MAX_TABS;
        add.setEnabled(hasRoom);
        add.setAlpha(hasRoom ? 1f : 0.4f);
        add.setOnClickListener(v -> {
            if (host.addTab()) {
                // Show the new blank chat immediately. The other tab stays live.
                panel.setVisibility(View.GONE);
            } else {
                Toast.makeText(activity, "Five tabs maximum", Toast.LENGTH_SHORT).show();
            }
        });

        TextView hint = makeLabel(
                "Tap a chat to switch instantly. EDIT renames it; arrows change order.", 10f);
        hint.setTextColor(Color.LTGRAY);
        hint.setPadding(dp(3), dp(10), dp(3), dp(8));
        pageContent.addView(hint, fullWidthWrap());

        for (int i = 0; i < tabs.size(); i++) {
            final ChatTabsController.TabInfo tab = tabs.get(i);
            LinearLayout item = new LinearLayout(activity);
            item.setOrientation(LinearLayout.VERTICAL);
            item.setPadding(dp(7), dp(7), dp(7), dp(7));
            item.setBackground(makeRoundedBackground(tab.active
                    ? Color.rgb(39, 76, 101) : Color.argb(200, 45, 45, 45), 10));
            LinearLayout.LayoutParams itemParams = fullWidthWrap();
            itemParams.bottomMargin = dp(7);
            pageContent.addView(item, itemParams);

            TextView name = makeLabel((tab.active ? "●  " : "     ") + tab.title, 13f);
            name.setTypeface(null, android.graphics.Typeface.BOLD);
            name.setGravity(Gravity.CENTER_VERTICAL | Gravity.LEFT);
            name.setMinHeight(dp(36));
            name.setSingleLine(true);
            name.setEllipsize(TextUtils.TruncateAt.END);
            name.setContentDescription("Open " + tab.title);
            item.addView(name, fullWidthWrap());
            name.setOnClickListener(v -> {
                host.selectTab(tab.id);
                panel.setVisibility(View.GONE);
            });

            TextView status = makeLabel(tab.active ? "CURRENT SESSION"
                    : tab.warm ? "LOADED · READY TO SWITCH"
                    : "SAVED · OPENS ON FIRST USE", 10f);
            status.setTextColor(Color.LTGRAY);
            status.setPadding(dp(4), dp(0), 0, dp(6));
            item.addView(status, fullWidthWrap());

            LinearLayout actions = new LinearLayout(activity);
            actions.setOrientation(LinearLayout.HORIZONTAL);
            TextView up = makeButton("↑");
            TextView down = makeButton("↓");
            TextView rename = makeButton("EDIT");
            TextView close = makeButton("×");
            actions.addView(up, new LinearLayout.LayoutParams(dp(42), dp(34)));
            LinearLayout.LayoutParams downParams =
                    new LinearLayout.LayoutParams(dp(42), dp(34));
            downParams.leftMargin = dp(5);
            actions.addView(down, downParams);
            LinearLayout.LayoutParams renameParams =
                    new LinearLayout.LayoutParams(0, dp(34), 1f);
            renameParams.leftMargin = dp(5);
            actions.addView(rename, renameParams);
            LinearLayout.LayoutParams closeParams =
                    new LinearLayout.LayoutParams(dp(42), dp(34));
            closeParams.leftMargin = dp(5);
            actions.addView(close, closeParams);
            item.addView(actions, fullWidthWrap());

            up.setEnabled(i > 0);
            down.setEnabled(i < tabs.size() - 1);
            close.setEnabled(tabs.size() > 1);
            up.setAlpha(i > 0 ? 1f : 0.3f);
            down.setAlpha(i < tabs.size() - 1 ? 1f : 0.3f);
            close.setAlpha(tabs.size() > 1 ? 1f : 0.3f);
            up.setContentDescription("Move " + tab.title + " up");
            down.setContentDescription("Move " + tab.title + " down");
            rename.setContentDescription("Rename " + tab.title);
            close.setContentDescription("Close " + tab.title);

            up.setOnClickListener(v -> {
                host.moveTab(tab.id, -1);
                refreshTabs();
            });
            down.setOnClickListener(v -> {
                host.moveTab(tab.id, 1);
                refreshTabs();
            });
            rename.setOnClickListener(v -> showRenameTabDialog(tab));
            close.setOnClickListener(v -> showCloseTabDialog(tab));
        }

        TextView footer = makeLabel(
                "Open tabs retain their live WebViews while the app runs. After a process restart, saved chats load when selected. Closing a tab discards its local unsent draft.", 10f);
        footer.setTextColor(Color.LTGRAY);
        footer.setPadding(dp(3), dp(4), dp(3), dp(6));
        pageContent.addView(footer, fullWidthWrap());
        updatePanelPlacement();
    }

    private void showRenameTabDialog(ChatTabsController.TabInfo tab) {
        EditText input = new EditText(activity);
        input.setSingleLine(true);
        input.setText(tab.title);
        input.selectAll();
        input.setHint("Tab name");
        int pad = dp(16);
        FrameLayout wrap = new FrameLayout(activity);
        wrap.setPadding(pad, dp(6), pad, dp(6));
        wrap.addView(input, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));
        new androidx.appcompat.app.AlertDialog.Builder(activity)
                .setTitle("Rename chat tab")
                .setView(wrap)
                .setPositiveButton("SAVE", (dialog, which) -> {
                    host.renameTab(tab.id, input.getText().toString());
                    refreshTabs();
                })
                .setNegativeButton("CANCEL", null)
                .show();
    }

    private void showCloseTabDialog(ChatTabsController.TabInfo tab) {
        new androidx.appcompat.app.AlertDialog.Builder(activity)
                .setTitle("Close " + tab.title + "?")
                .setMessage("Its live page and any unsent text will be discarded. The ChatGPT conversation remains in your account.")
                .setPositiveButton("CLOSE TAB", (dialog, which) -> {
                    host.closeTab(tab.id);
                    refreshTabs();
                })
                .setNegativeButton("CANCEL", null)
                .show();
    }

    private void buildTunePage() {
        backdropLabel = makeLabel("", 12f);
        backdropLabel.setPadding(0, dp(4), 0, 0);
        pageContent.addView(backdropLabel);
        SeekBar backdropSlider = new SeekBar(activity);
        backdropSlider.setMax(100);
        backdropSlider.setProgress(backdropPercent);
        pageContent.addView(backdropSlider, fullWidthWrap());

        surfaceOpacityLabel = makeLabel("", 12f);
        pageContent.addView(surfaceOpacityLabel);
        SeekBar opacitySlider = new SeekBar(activity);
        opacitySlider.setMax(100);
        opacitySlider.setProgress(surfaceOpacityPercent);
        pageContent.addView(opacitySlider, fullWidthWrap());

        uiScaleLabel = makeLabel("", 12f);
        pageContent.addView(uiScaleLabel);
        pageContent.addView(makeStepRow(
                () -> setUiScale(uiScalePercent - UI_SCALE_STEP),
                this::resetUiScale,
                () -> setUiScale(uiScalePercent + UI_SCALE_STEP)));

        textScaleLabel = makeLabel("", 12f);
        pageContent.addView(textScaleLabel);
        pageContent.addView(makeStepRow(
                () -> setTextScale(textScalePercent - TEXT_SCALE_STEP),
                this::resetTextScale,
                () -> setTextScale(textScalePercent + TEXT_SCALE_STEP)));

        LinearLayout actions = new LinearLayout(activity);
        actions.setOrientation(LinearLayout.HORIZONTAL);
        actions.setPadding(0, dp(6), 0, 0);
        TextView move = makeButton("RESET POSITION");
        TextView reload = makeButton("FORCE RELOAD");
        actions.addView(move, new LinearLayout.LayoutParams(0, dp(38), 1f));
        LinearLayout.LayoutParams right = new LinearLayout.LayoutParams(0, dp(38), 1f);
        right.leftMargin = dp(6);
        actions.addView(reload, right);
        pageContent.addView(actions, fullWidthWrap());

        TextView reset = makeButton("RESET TRANSPARENCY");
        LinearLayout.LayoutParams resetParams = fullWidthButton(38);
        resetParams.topMargin = dp(6);
        pageContent.addView(reset, resetParams);
        TextView hint = makeLabel(
                "Drag the floating icon to pin it anywhere. Tap to open. UI scale changes the page; text size is independent.", 10f);
        hint.setTextColor(Color.LTGRAY);
        hint.setPadding(0, dp(6), 0, 0);
        pageContent.addView(hint);

        backdropSlider.setOnSeekBarChangeListener(new SimpleSeekBarListener() {
            @Override public void onProgressChanged(
                    SeekBar bar, int value, boolean fromUser) {
                if (!fromUser) return;
                backdropPercent = value;
                prefs.edit().putInt(KEY_BACKDROP, value).apply();
                applyBackdrop();
                updateLabels();
            }
        });
        opacitySlider.setOnSeekBarChangeListener(new SimpleSeekBarListener() {
            @Override public void onProgressChanged(
                    SeekBar bar, int value, boolean fromUser) {
                if (!fromUser) return;
                surfaceOpacityPercent = value;
                prefs.edit().putInt(KEY_SURFACE_OPACITY, value).apply();
                applyBackdrop();
                updateLabels();
            }
        });
        move.setOnClickListener(v -> resetPosition());
        reload.setOnClickListener(v -> {
            panel.setVisibility(View.GONE);
            com.katsuyamaki.mychatgpt.notifications.AppToast.makeText(activity, "Refreshing current chat…", Toast.LENGTH_SHORT).show();
            host.forceReloadCurrentChat();
        });
        reset.setOnClickListener(v -> {
            backdropPercent = DEFAULT_BACKDROP;
            surfaceOpacityPercent = DEFAULT_SURFACE_OPACITY;
            prefs.edit()
                    .putInt(KEY_BACKDROP, backdropPercent)
                    .putInt(KEY_SURFACE_OPACITY, surfaceOpacityPercent)
                    .apply();
            backdropSlider.setProgress(backdropPercent);
            opacitySlider.setProgress(surfaceOpacityPercent);
            applyBackdrop();
            updateLabels();
        });
        updateLabels();
    }

    private void buildNotificationPage() {
        pageContent.removeAllViews();
        NotificationController notifications = host.getNotificationController();
        if (notifications == null) return;

        String status = !notifications.isAndroidEnabled()
                ? "Android alerts off · history still saved"
                : notifications.needsPermission()
                    ? "Android permission needed · history still saved"
                    : notifications.canPostAndroid()
                        ? "Android alerts on · replies and scheduled tasks only"
                        : "Android alerts blocked in system settings";
        TextView state = makeLabel(status, 11f);
        state.setPadding(dp(4), 0, dp(4), dp(9));
        pageContent.addView(state);

        TextView webHeading = makeLabel(
                "CHATGPT WEBVIEW · NO OTHER APP REQUIRED", 11f);
        webHeading.setTypeface(null, android.graphics.Typeface.BOLD);
        webHeading.setPadding(dp(4), dp(5), dp(4), dp(3));
        pageContent.addView(webHeading);

        String watcher = notifications.isSiteMonitorActive()
                ? "Site popup listener: ACTIVE"
                : "Site popup listener: waiting for ChatGPT";
        TextView monitor = makeLabel(watcher
                + "  ·  Task alerts: " + notifications.siteCapturesThisSession()
                + "  ·  Completed replies: " + notifications.completionsRecordedThisSession(), 11f);
        monitor.setTextColor(notifications.isSiteMonitorActive()
                ? 0xFFAFE8D0 : Color.LTGRAY);
        monitor.setPadding(dp(4), 0, dp(4), dp(9));
        pageContent.addView(monitor);
        TextView captureStats = makeLabel(notifications.siteMonitorSummary(), 10f);
        captureStats.setPadding(dp(4), 0, dp(4), dp(9));
        pageContent.addView(captureStats);

        TextView toggle = makeButton(
                !notifications.isAndroidEnabled() ? "ENABLE ANDROID ALERTS"
                : notifications.needsPermission() ? "ALLOW MYCHATGPT ALERTS"
                : "DISABLE ANDROID ALERTS");
        pageContent.addView(toggle, fullWidthButton(40));
        toggle.setOnClickListener(v -> {
            if (!notifications.isAndroidEnabled()) {
                notifications.setAndroidEnabled(true);
                if (notifications.needsPermission()) host.requestNotificationPermission();
            } else if (notifications.needsPermission()) {
                host.requestNotificationPermission();
            } else {
                notifications.setAndroidEnabled(false);
            }
            refreshNotifications();
        });

        LinearLayout actions = new LinearLayout(activity);
        actions.setOrientation(LinearLayout.HORIZONTAL);
        TextView test = makeButton("SEND TEST");
        TextView system = makeButton("ANDROID SETTINGS");
        actions.addView(test, new LinearLayout.LayoutParams(0, dp(38), 1f));
        LinearLayout.LayoutParams sysParams = new LinearLayout.LayoutParams(0, dp(38), 1.2f);
        sysParams.leftMargin = dp(6);
        actions.addView(system, sysParams);
        LinearLayout.LayoutParams actionParams = fullWidthWrap();
        actionParams.topMargin = dp(6);
        pageContent.addView(actions, actionParams);
        test.setOnClickListener(v -> host.sendTestNotification());
        system.setOnClickListener(v -> openAndroidNotificationSettings());

        TextView webProbe = makeButton("TEST SITE POPUP CAPTURE");
        LinearLayout.LayoutParams probeParams = fullWidthButton(38);
        probeParams.topMargin = dp(6);
        probeParams.bottomMargin = dp(4);
        pageContent.addView(webProbe, probeParams);
        webProbe.setOnClickListener(v -> host.testSiteCapture());

        TextView genericProbe = makeButton("TEST UNMARKED POPUP");
        LinearLayout.LayoutParams genericParams = fullWidthButton(38);
        genericParams.topMargin = dp(5);
        pageContent.addView(genericProbe, genericParams);
        genericProbe.setOnClickListener(v -> host.testUnmarkedCapture());

        TextView diagnostics = makeButton("COPY CAPTURE COUNTERS");
        LinearLayout.LayoutParams diagParams = fullWidthButton(34);
        diagParams.topMargin = dp(5);
        diagParams.bottomMargin = dp(5);
        pageContent.addView(diagnostics, diagParams);
        diagnostics.setOnClickListener(v -> host.copyMonitorDiagnostics());

        TextView testTip = makeLabel(
                "Diagnostic test alerts are hidden from the main inbox. "
                + "Use Show Other History / Tests to inspect them.", 10f);
        testTip.setPadding(dp(4), dp(5), dp(4), dp(5));
        pageContent.addView(testTip);

        TextView companionHeading = makeLabel("OPTIONAL OFFICIAL-APP MIRROR", 11f);
        companionHeading.setTypeface(null, android.graphics.Typeface.BOLD);
        companionHeading.setPadding(dp(4), dp(12), dp(4), dp(4));
        pageContent.addView(companionHeading);

        // INPUT, not output: the tests below create local notifications; this
        // Android listener is the source for real official ChatGPT pushes.
        boolean listenerAccess = notifications.hasOfficialPushMirrorAccess();
        String sourceStatus = !listenerAccess
                ? "Optional companion push: not connected"
                : notifications.isOsListenerConnected()
                    ? "Optional companion push: listening"
                    : "Optional companion push: connecting";
        TextView pushStatus = makeLabel(sourceStatus, 12f);
        pushStatus.setTypeface(null, android.graphics.Typeface.BOLD);
        pushStatus.setTextColor(listenerAccess ? 0xFFAFE8D0 : 0xFFFFD5A0);
        pushStatus.setPadding(dp(4), 0, dp(4), dp(7));
        pageContent.addView(pushStatus);

        long seen = notifications.latestOfficialSeenAt();
        long saved = notifications.latestOfficialSavedAt();
        TextView sourceActivity = makeLabel(
                "Last official event: " + (seen == 0 ? "none"
                        : NotificationController.formatTimestamp(seen))
                + "  ·  Last saved: " + (saved == 0 ? "none"
                        : NotificationController.formatTimestamp(saved)), 10f);
        sourceActivity.setPadding(dp(4), 0, dp(4), dp(6));
        pageContent.addView(sourceActivity);

        TextView sourceHelp = makeLabel(
                "Requires official ChatGPT app alerts ON. Android Notification "
                + "Access can read all app alerts; MyChatGPT only processes "
                + "ChatGPT's package. Both apps may show an Android alert.", 10f);
        sourceHelp.setPadding(dp(4), 0, dp(4), dp(9));
        pageContent.addView(sourceHelp);

        TextView access = makeButton(listenerAccess
                ? "MANAGE MIRROR ACCESS" : "GRANT MIRROR ACCESS");
        pageContent.addView(access, fullWidthButton(38));
        access.setOnClickListener(v -> openNotificationListenerSettings());

        TextView officialSettings = makeButton("CHATGPT APP ALERT SETTINGS");
        LinearLayout.LayoutParams nativeSettingsParams = fullWidthButton(36);
        nativeSettingsParams.topMargin = dp(5);
        nativeSettingsParams.bottomMargin = dp(10);
        pageContent.addView(officialSettings, nativeSettingsParams);
        officialSettings.setOnClickListener(v -> openOfficialChatGptNotificationSettings());

        LinearLayout toolbar = new LinearLayout(activity);
        toolbar.setOrientation(LinearLayout.HORIZONTAL);
        toolbar.setGravity(Gravity.CENTER_VERTICAL);
        TextView history = makeLabel(
                (showOtherHistory ? "All history (" : "Replies & tasks (")
                + notifications.totalCount(showOtherHistory) + ")", 13f);
        history.setTypeface(null, android.graphics.Typeface.BOLD);
        toolbar.addView(history, new LinearLayout.LayoutParams(0, dp(40), 1f));
        TextView clear = makeButton("CLEAR");
        toolbar.addView(clear, new LinearLayout.LayoutParams(dp(65), dp(34)));
        pageContent.addView(toolbar, fullWidthWrap());
        clear.setOnClickListener(v -> new android.app.AlertDialog.Builder(activity)
                .setTitle("Clear notification history?")
                .setMessage("This deletes all saved notifications, including hidden older alerts and tests. It cannot be undone.")
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Clear", (dialog, which) -> {
                    notifications.clearHistory();
                    notificationOffset = 0;
                    refreshNotifications();
                }).show());

        TextView otherHistory = makeButton(
                showOtherHistory ? "SHOW ONLY REPLIES & TASKS" : "SHOW OTHER HISTORY / TESTS");
        LinearLayout.LayoutParams otherParams = fullWidthButton(34);
        otherParams.bottomMargin = dp(7);
        pageContent.addView(otherHistory, otherParams);
        otherHistory.setOnClickListener(v -> {
            showOtherHistory = !showOtherHistory;
            notificationOffset = 0;
            refreshNotifications();
        });

        int total = notifications.totalCount(showOtherHistory);
        if (total == 0) {
            TextView empty = makeLabel(
                    showOtherHistory
                        ? "No saved notifications yet."
                        : "No completed replies or scheduled tasks yet. Older routine and test alerts are hidden.",
                    12f);
            empty.setPadding(dp(4), dp(8), dp(4), dp(10));
            pageContent.addView(empty);
            return;
        }
        if (notificationOffset >= total) notificationOffset = Math.max(0, total - PAGE_SIZE);
        List<NotificationStore.Entry> entries =
                notifications.recent(PAGE_SIZE, notificationOffset, showOtherHistory);
        for (NotificationStore.Entry item : entries) {
            LinearLayout row = new LinearLayout(activity);
            row.setOrientation(LinearLayout.VERTICAL);
            row.setPadding(dp(9), dp(8), dp(9), dp(8));
            row.setBackground(makeRoundedBackground(
                    Color.argb(item.isUnread() ? 215 : 135, 54, 54, 54), 10));
            TextView title = makeLabel((item.isUnread() ? "●  " : "")
                    + item.title, 12f);
            title.setTypeface(null, android.graphics.Typeface.BOLD);
            row.addView(title);
            TextView time = makeLabel(
                    NotificationController.formatTimestamp(item.createdAt), 10f);
            time.setTextColor(Color.LTGRAY);
            row.addView(time);
            TextView body = makeLabel(item.body, 12f);
            body.setMaxLines(3);
            body.setEllipsize(TextUtils.TruncateAt.END);
            body.setPadding(0, dp(4), 0, dp(2));
            row.addView(body);
            String destination = NotificationController.safeChatUrl(item.chatUrl);
            TextView link = makeLabel(
                    destination != null ? "Tap to open chat · Hold to copy link"
                    : ("official-task".equals(item.source) || "official-chatgpt".equals(item.source))
                        ? "Android did not provide a chat link"
                        : "No conversation link supplied", 10f);
            link.setTextColor(0xFFA8D4FF);
            row.addView(link);
            LinearLayout.LayoutParams itemParams = fullWidthWrap();
            itemParams.bottomMargin = dp(7);
            pageContent.addView(row, itemParams);
            row.setOnClickListener(v -> host.openNotification(item.id));
            row.setOnLongClickListener(v -> {
                // Diagnostic for routing issues: user can inspect the exact
                // HTTPS chat destination preserved with this event. Never
                // substitute the currently open WebView URL.
                if (destination == null) {
                    Toast.makeText(activity, "No chat URL saved for this notice",
                            Toast.LENGTH_SHORT).show();
                    return true;
                }
                android.content.ClipboardManager clipboard =
                        (android.content.ClipboardManager) activity.getSystemService(
                                Context.CLIPBOARD_SERVICE);
                if (clipboard != null) {
                    clipboard.setPrimaryClip(android.content.ClipData.newPlainText(
                            "MyChatGPT notification chat link", destination));
                    Toast.makeText(activity, "Saved chat link copied",
                            Toast.LENGTH_SHORT).show();
                }
                return true;
            });
        }
        LinearLayout pager = new LinearLayout(activity);
        pager.setOrientation(LinearLayout.HORIZONTAL);
        TextView newer = makeButton("NEWER");
        newer.setAlpha(notificationOffset == 0 ? 0.45f : 1f);
        newer.setEnabled(notificationOffset > 0);
        TextView older = makeButton("OLDER");
        older.setAlpha(notificationOffset + PAGE_SIZE >= total ? 0.45f : 1f);
        older.setEnabled(notificationOffset + PAGE_SIZE < total);
        pager.addView(newer, new LinearLayout.LayoutParams(0, dp(38), 1f));
        LinearLayout.LayoutParams olderParams = new LinearLayout.LayoutParams(0, dp(38), 1f);
        olderParams.leftMargin = dp(6);
        pager.addView(older, olderParams);
        pageContent.addView(pager, fullWidthWrap());
        newer.setOnClickListener(v -> {
            notificationOffset = Math.max(0, notificationOffset - PAGE_SIZE);
            showPage(PAGE_NOTIFICATIONS);
        });
        older.setOnClickListener(v -> {
            notificationOffset += PAGE_SIZE;
            showPage(PAGE_NOTIFICATIONS);
        });
    }

    private void openNotificationListenerSettings() {
        try {
            activity.startActivity(new Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS));
        } catch (Exception ex) {
            com.katsuyamaki.mychatgpt.notifications.AppToast.makeText(
                    activity, "Could not open Notification Access settings",
                    Toast.LENGTH_LONG).show();
        }
    }

    private void openOfficialChatGptNotificationSettings() {
        try {
            String app = NotificationController.OFFICIAL_CHATGPT_PACKAGE;
            Intent intent;
            if (Build.VERSION.SDK_INT >= 26) {
                intent = new Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS);
                intent.putExtra(Settings.EXTRA_APP_PACKAGE, app);
            } else {
                intent = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                        Uri.parse("package:" + app));
            }
            activity.startActivity(intent);
        } catch (Exception ex) {
            com.katsuyamaki.mychatgpt.notifications.AppToast.makeText(
                    activity, "Could not open ChatGPT notification settings",
                    Toast.LENGTH_LONG).show();
        }
    }

    private void openAndroidNotificationSettings() {
        try {
            Intent intent;
            if (Build.VERSION.SDK_INT >= 26) {
                intent = new Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS);
                intent.putExtra(Settings.EXTRA_APP_PACKAGE, activity.getPackageName());
            } else {
                intent = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                        Uri.parse("package:" + activity.getPackageName()));
            }
            activity.startActivity(intent);
        } catch (Exception e) {
            com.katsuyamaki.mychatgpt.notifications.AppToast.makeText(activity, "Cannot open Android settings",
                    Toast.LENGTH_SHORT).show();
        }
    }

    private LinearLayout.LayoutParams fullWidthButton(int heightDp) {
        return new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(heightDp));
    }

    private void openPanel() {
        if (panel == null) return;
        // Pre-position while invisible so its first visible frame is stable.
        panel.setVisibility(View.INVISIBLE);
        updatePanelPlacement();
        panel.setVisibility(View.VISIBLE);
    }

    private void updatePanelPlacement() {
        if (panel == null || menuButton == null || pageScroll == null
                || panel.getLayoutParams() == null || menuButton.getLayoutParams() == null
                || root.getWidth() <= 0 || root.getHeight() <= 0) return;

        int margin = dp(EDGE_MARGIN_DP);
        int safeLeft = clamp(insetLeft + margin, 0, root.getWidth() - 1);
        int safeTop = clamp(insetTop + margin, 0, root.getHeight() - 1);
        int safeRight = Math.max(safeLeft + 1, root.getWidth() - insetRight - margin);
        int safeBottom = Math.max(safeTop + 1, root.getHeight() - insetBottom - margin);
        int width = Math.min(dp(310), safeRight - safeLeft);

        FrameLayout.LayoutParams handle =
                (FrameLayout.LayoutParams) menuButton.getLayoutParams();
        int gap = dp(PANEL_GAP_DP);
        int belowRoom = Math.max(0,
                safeBottom - (handle.topMargin + handleSize() + gap));
        int aboveRoom = Math.max(0, handle.topMargin - gap - safeTop);
        boolean below = belowRoom >= aboveRoom;
        int availableHeight = Math.max(1, below ? belowRoom : aboveRoom);

        // Preserve existing per-page scroll behavior but bound it by the
        // room beside the handle. The header + vertical padding take ~56dp.
        int desiredScroll = currentPage == PAGE_HOME ? dp(200)
                : currentPage == PAGE_TUNE ? dp(365)
                : currentPage == PAGE_TABS ? dp(420) : dp(400);
        int scrollHeight = Math.max(1,
                Math.min(desiredScroll, availableHeight - dp(56)));
        LinearLayout.LayoutParams scrollParams =
                (LinearLayout.LayoutParams) pageScroll.getLayoutParams();
        if (scrollParams.height != scrollHeight) {
            scrollParams.height = scrollHeight;
            pageScroll.setLayoutParams(scrollParams);
        }
        panel.measure(
                View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(availableHeight, View.MeasureSpec.AT_MOST));
        int height = Math.max(1,
                Math.min(availableHeight, panel.getMeasuredHeight()));

        // Align to the nearer screen edge and never obscure the drag handle.
        boolean alignLeft = handle.leftMargin + handleSize() / 2
                < safeLeft + (safeRight - safeLeft) / 2;
        int left = alignLeft ? handle.leftMargin
                : handle.leftMargin + handleSize() - width;
        left = clamp(left, safeLeft, Math.max(safeLeft, safeRight - width));
        int top = below ? handle.topMargin + handleSize() + gap
                : handle.topMargin - gap - height;
        top = clamp(top, safeTop, Math.max(safeTop, safeBottom - height));

        FrameLayout.LayoutParams params =
                (FrameLayout.LayoutParams) panel.getLayoutParams();
        if (params.width != width || params.height != height
                || params.leftMargin != left || params.topMargin != top) {
            params.width = width;
            params.height = height;
            params.leftMargin = left;
            params.topMargin = top;
            panel.setLayoutParams(params);
        }
    }

    private LinearLayout makeStepRow(
            Runnable decrement, Runnable reset, Runnable increment) {
        LinearLayout row = new LinearLayout(activity);
        row.setOrientation(LinearLayout.HORIZONTAL);

        TextView minus = makeButton("−");
        TextView resetButton = makeButton("RESET");
        TextView plus = makeButton("+");

        LinearLayout.LayoutParams edge =
                new LinearLayout.LayoutParams(0, dp(34), 1f);
        row.addView(minus, edge);

        LinearLayout.LayoutParams center =
                new LinearLayout.LayoutParams(0, dp(34), 1.4f);
        center.leftMargin = dp(6);
        center.rightMargin = dp(6);
        row.addView(resetButton, center);

        LinearLayout.LayoutParams edge2 =
                new LinearLayout.LayoutParams(0, dp(34), 1f);
        row.addView(plus, edge2);

        minus.setOnClickListener(v -> decrement.run());
        resetButton.setOnClickListener(v -> reset.run());
        plus.setOnClickListener(v -> increment.run());

        return row;
    }

    private void setUiScale(int value) {
        int clamped = clamp(value, MIN_UI_SCALE, MAX_UI_SCALE);
        if (clamped == uiScalePercent) return;
        uiScalePercent = clamped;
        prefs.edit().putInt(KEY_UI_SCALE, uiScalePercent).apply();
        applyUiScale(host.getMainWebView());
        updateLabels();
    }

    private void resetUiScale() {
        setUiScale(DEFAULT_UI_SCALE);
    }

    private void setTextScale(int value) {
        int clamped = clamp(value, MIN_TEXT_SCALE, MAX_TEXT_SCALE);
        if (clamped == textScalePercent) return;
        textScalePercent = clamped;
        prefs.edit().putInt(KEY_TEXT_SCALE, textScalePercent).apply();
        applyTextScale(host.getMainWebView());
        updateLabels();
    }

    private void resetTextScale() {
        setTextScale(DEFAULT_TEXT_SCALE);
    }

    private void applyTextScale(WebView webView) {
        if (webView == null) return;
        // setTextZoom can trigger a full text re-layout. New WebViews start at 100,
        // so the common 100% case is deliberately a no-op, and unchanged values are
        // never re-applied from onPageFinished/configuration callbacks.
        if (webView.getSettings().getTextZoom() != textScalePercent) {
            webView.getSettings().setTextZoom(textScalePercent);
        }
    }

    private void applyUiScale(WebView webView) {
        if (webView == null) return;
        int scale = uiScalePercent;
        String script =
                "(function(){try{" +
                "var id='mychatgpt-ui-scale';" +
                "var s=document.getElementById(id);" +
                "if(" + scale + "===100){if(s)s.remove();return;}" +
                "if(!s){s=document.createElement('style');s.id=id;" +
                "(document.head||document.documentElement).appendChild(s);}" +
                "s.textContent=':root{zoom:" + scale + "% !important;}';" +
                "}catch(e){}})();";
        webView.evaluateJavascript(script, null);
    }

    private void applyBackdrop() {
        // Same composition strategy used by the Termux Launcher fork: keep the
        // content View at alpha=1 and paint translucent ARGB surfaces behind it.
        int backdropAlpha = Math.round(255f * (backdropPercent / 100f));
        int backdrop = Color.argb(backdropAlpha, 0, 0, 0);

        int base = isDarkMode() ? Color.rgb(13, 13, 13) : Color.WHITE;
        int surfaceAlpha = Math.round(255f * (surfaceOpacityPercent / 100f));
        int surface = Color.argb(
                surfaceAlpha,
                Color.red(base),
                Color.green(base),
                Color.blue(base));

        root.setBackgroundColor(compositeOver(surface, backdrop));
    }

    private boolean isDarkMode() {
        int night = activity.getResources().getConfiguration().uiMode
                & Configuration.UI_MODE_NIGHT_MASK;
        return night == Configuration.UI_MODE_NIGHT_YES;
    }

    private static int compositeOver(int foreground, int background) {
        float fa = Color.alpha(foreground) / 255f;
        float ba = Color.alpha(background) / 255f;
        float oa = fa + ba * (1f - fa);
        if (oa <= 0f) return Color.TRANSPARENT;

        float bgWeight = ba * (1f - fa);
        int r = Math.round((Color.red(foreground) * fa
                + Color.red(background) * bgWeight) / oa);
        int g = Math.round((Color.green(foreground) * fa
                + Color.green(background) * bgWeight) / oa);
        int b = Math.round((Color.blue(foreground) * fa
                + Color.blue(background) * bgWeight) / oa);
        return Color.argb(Math.round(oa * 255f), r, g, b);
    }

    private boolean onMenuTouch(View view, MotionEvent event) {
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                touchDownRawX = event.getRawX();
                touchDownRawY = event.getRawY();
                FrameLayout.LayoutParams params =
                        (FrameLayout.LayoutParams) menuButton.getLayoutParams();
                dragStartLeft = params.leftMargin;
                dragStartTop = params.topMargin;
                dragging = false;
                gestureCancelled = false;
                return true;

            case MotionEvent.ACTION_POINTER_DOWN:
                // Ignore secondary pointers rather than jumping on index swap.
                if (dragging) updateFloatingPlacement();
                dragging = false;
                gestureCancelled = true;
                return true;

            case MotionEvent.ACTION_MOVE:
                if (gestureCancelled) return true;
                float dx = event.getRawX() - touchDownRawX;
                float dy = event.getRawY() - touchDownRawY;
                if (!dragging && dx * dx + dy * dy > touchSlop * touchSlop) {
                    dragging = true;
                    closePanelIfOpen();
                }
                if (dragging) {
                    setHandlePosition(dragStartLeft + Math.round(dx),
                            dragStartTop + Math.round(dy));
                }
                return true;

            case MotionEvent.ACTION_UP:
                if (!gestureCancelled) {
                    if (dragging) {
                        setHandlePosition(
                                dragStartLeft + Math.round(event.getRawX() - touchDownRawX),
                                dragStartTop + Math.round(event.getRawY() - touchDownRawY));
                        saveHandlePosition();
                    } else {
                        view.performClick();
                    }
                }
                dragging = false;
                gestureCancelled = false;
                return true;

            case MotionEvent.ACTION_CANCEL:
                if (dragging) updateFloatingPlacement();
                dragging = false;
                gestureCancelled = false;
                return true;

            default:
                return true;
        }
    }

    private void resetPosition() {
        positionX = 1f;
        positionY = 0f;
        persistPosition();
        closePanelIfOpen();
        updateFloatingPlacement();
    }

    private void saveHandlePosition() {
        if (menuButton == null) return;
        FrameLayout.LayoutParams params =
                (FrameLayout.LayoutParams) menuButton.getLayoutParams();
        int leftRange = maxHandleLeft() - minHandleLeft();
        int topRange = maxHandleTop() - minHandleTop();
        // When resized into a zero-travel viewport, retain saved fractions.
        if (leftRange > 0) {
            positionX = clampFraction(
                    (params.leftMargin - minHandleLeft()) / (float) leftRange);
        }
        if (topRange > 0) {
            positionY = clampFraction(
                    (params.topMargin - minHandleTop()) / (float) topRange);
        }
        persistPosition();
    }

    private void persistPosition() {
        prefs.edit().putFloat(KEY_POSITION_X, positionX)
                .putFloat(KEY_POSITION_Y, positionY).apply();
    }

    private void installInsetTracking() {
        root.addOnLayoutChangeListener(rootLayoutListener);
        root.setOnApplyWindowInsetsListener((view, insets) -> {
            insetLeft = insets.getSystemWindowInsetLeft();
            insetTop = insets.getSystemWindowInsetTop();
            insetRight = insets.getSystemWindowInsetRight();
            insetBottom = insets.getSystemWindowInsetBottom();
            updateFloatingPlacement();
            return insets;
        });
        root.requestApplyInsets();
    }

    private int handleSize() {
        return menuButton != null && menuButton.getWidth() > 0
                ? menuButton.getWidth() : dp(HANDLE_SIZE_DP);
    }

    private int minHandleLeft() {
        return clamp(insetLeft + dp(EDGE_MARGIN_DP), 0,
                Math.max(0, root.getWidth() - handleSize()));
    }

    private int maxHandleLeft() {
        return Math.max(minHandleLeft(),
                root.getWidth() - insetRight - dp(EDGE_MARGIN_DP) - handleSize());
    }

    private int minHandleTop() {
        return clamp(insetTop + dp(EDGE_MARGIN_DP), 0,
                Math.max(0, root.getHeight() - handleSize()));
    }

    private int maxHandleTop() {
        return Math.max(minHandleTop(),
                root.getHeight() - insetBottom - dp(EDGE_MARGIN_DP) - handleSize());
    }

    private void setHandlePosition(int left, int top) {
        if (menuButton == null || root.getWidth() <= 0 || root.getHeight() <= 0) return;
        left = clamp(left, minHandleLeft(), maxHandleLeft());
        top = clamp(top, minHandleTop(), maxHandleTop());
        FrameLayout.LayoutParams params =
                (FrameLayout.LayoutParams) menuButton.getLayoutParams();
        if (params.leftMargin == left && params.topMargin == top) return;
        params.leftMargin = left;
        params.topMargin = top;
        menuButton.setLayoutParams(params);
    }

    private void updateFloatingPlacement() {
        if (menuButton == null || root.getWidth() <= 0 || root.getHeight() <= 0) return;
        int left = minHandleLeft() + Math.round(
                (maxHandleLeft() - minHandleLeft()) * positionX);
        int top = minHandleTop() + Math.round(
                (maxHandleTop() - minHandleTop()) * positionY);
        setHandlePosition(left, top);
        updatePanelPlacement();
    }

    private void updateLabels() {
        if (backdropLabel != null) {
            backdropLabel.setText("Backdrop dim: " + backdropPercent + "%");
        }
        if (surfaceOpacityLabel != null) {
            surfaceOpacityLabel.setText("ChatGPT surface opacity: " + surfaceOpacityPercent + "%");
        }
        if (uiScaleLabel != null) {
            uiScaleLabel.setText("UI scale: " + uiScalePercent + "%");
        }
        if (textScaleLabel != null) {
            textScaleLabel.setText("Text size: " + textScalePercent + "%");
        }
    }

    private TextView makeLabel(String text, float sp) {
        TextView view = new TextView(activity);
        view.setText(text);
        view.setTextColor(Color.WHITE);
        view.setTextSize(sp);
        return view;
    }

    private TextView makeButton(String text) {
        TextView view = makeLabel(text, 11f);
        view.setGravity(Gravity.CENTER);
        view.setPadding(dp(8), 0, dp(8), 0);
        view.setBackground(makeRoundedBackground(
                Color.argb(185, 48, 48, 48), 12));
        return view;
    }

    private GradientDrawable makeRoundedBackground(int color, int radiusDp) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(color);
        drawable.setCornerRadius(dp(radiusDp));
        return drawable;
    }

    private LinearLayout.LayoutParams fullWidthWrap() {
        return new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
    }

    private int dp(int value) {
        float density = activity.getResources().getDisplayMetrics().density;
        return Math.round(value * density);
    }

    private static float clampFraction(float value) {
        if (Float.isNaN(value) || Float.isInfinite(value)) return 0f;
        return Math.max(0f, Math.min(1f, value));
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    private abstract static class SimpleSeekBarListener
            implements SeekBar.OnSeekBarChangeListener {
        @Override
        public void onStartTrackingTouch(SeekBar seekBar) {}

        @Override
        public void onStopTrackingTouch(SeekBar seekBar) {}
    }
}
