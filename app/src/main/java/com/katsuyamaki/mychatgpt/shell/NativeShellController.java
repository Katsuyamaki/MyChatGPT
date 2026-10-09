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
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowInsets;
import android.view.WindowManager;
import android.webkit.WebView;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.ScrollView;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import com.katsuyamaki.mychatgpt.R;
import com.katsuyamaki.mychatgpt.site.ChatGptSiteContract;
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
    private static final String KEY_CORNER = "controls_corner";
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
    private static final int PAGE_HOME = 0;
    private static final int PAGE_TUNE = 1;
    private static final int PAGE_NOTIFICATIONS = 2;
    private static final int PAGE_SIZE = 30;

    public interface Host {
        WebView getMainWebView();
        void forceReloadCurrentChat();
        NotificationController getNotificationController();
        void openNotification(long id);
        void requestNotificationPermission();
        void sendTestNotification();
        void testSiteCapture();
    }

    private final Activity activity;
    private final FrameLayout root;
    private final Host host;
    private final SharedPreferences prefs;

    private LinearLayout cluster;
    private LinearLayout panel;
    private FrameLayout menuButton;
    private TextView unreadBadge;
    private TextView headerTitle;
    private TextView backButton;
    private ScrollView pageScroll;
    private LinearLayout pageContent;
    private int currentPage = PAGE_HOME;
    private int notificationOffset;
    private TextView backdropLabel;
    private TextView surfaceOpacityLabel;
    private TextView uiScaleLabel;
    private TextView textScaleLabel;

    private int backdropPercent;
    private int surfaceOpacityPercent;
    private int corner;
    private int uiScalePercent;
    private int textScalePercent;

    private int insetLeft;
    private int insetTop;
    private int insetRight;
    private int insetBottom;

    public NativeShellController(Activity activity, FrameLayout root, Host host) {
        this.activity = activity;
        this.root = root;
        this.host = host;
        this.prefs = activity.getSharedPreferences(PREFS, Context.MODE_PRIVATE);

        backdropPercent = clamp(prefs.getInt(KEY_BACKDROP, DEFAULT_BACKDROP), 0, 100);
        surfaceOpacityPercent = clamp(prefs.getInt(KEY_SURFACE_OPACITY, DEFAULT_SURFACE_OPACITY), 0, 100);
        corner = clamp(prefs.getInt(KEY_CORNER, CORNER_TOP_RIGHT), 0, 3);
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
        if (webView == null || webView != host.getMainWebView()) return;
        webView.setBackgroundColor(Color.TRANSPARENT);
        webView.evaluateJavascript(ChatGptSiteContract.WALLPAPER_TRANSPARENCY_JS, null);
        applyUiScale(webView);
    }

    public void onConfigurationChanged() {
        configureWallpaperWindow();
        applyBackdrop();
        updatePanelSize();
        updateClusterPlacement();
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
        if (cluster != null) {
            try {
                root.removeView(cluster);
            } catch (Throwable ignored) {
            }
        }
        cluster = null;
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
        showPage(PAGE_NOTIFICATIONS);
        panel.setVisibility(View.VISIBLE);
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
        cluster = new LinearLayout(activity);
        cluster.setOrientation(LinearLayout.VERTICAL);
        cluster.setGravity(isRightCorner() ? Gravity.END : Gravity.START);
        cluster.setElevation(dp(16));

        // Compact launcher: the application's own icon replaces the TUNE text.
        menuButton = new FrameLayout(activity);
        menuButton.setContentDescription("Open MyChatGPT menu");
        menuButton.setBackground(makeRoundedBackground(
                Color.argb(225, 30, 30, 30), 24));
        menuButton.setLayoutParams(new LinearLayout.LayoutParams(dp(48), dp(48)));
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
            if (panel.getVisibility() == View.VISIBLE) {
                panel.setVisibility(View.GONE);
            } else {
                showPage(PAGE_HOME);
                panel.setVisibility(View.VISIBLE);
            }
        });

        showPage(PAGE_HOME);
        refreshNotifications();
        reorderClusterChildren();
        int insertIndex = Math.min(1, root.getChildCount());
        root.addView(cluster, insertIndex);
        updateClusterPlacement();
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
                : page == PAGE_NOTIFICATIONS ? "Notifications" : "MyChatGPT");
        if (page == PAGE_TUNE) buildTunePage();
        else if (page == PAGE_NOTIFICATIONS) buildNotificationPage();
        else buildHomePage();
        updatePanelSize();
        pageScroll.post(() -> pageScroll.scrollTo(0, 0));
    }

    private void buildHomePage() {
        TextView intro = makeLabel("Choose a section", 12f);
        intro.setPadding(dp(4), 0, 0, dp(10));
        pageContent.addView(intro);

        TextView tune = makeButton("TUNE  ·  Appearance and layout");
        pageContent.addView(tune, fullWidthButton(45));
        tune.setOnClickListener(v -> showPage(PAGE_TUNE));

        NotificationController notifications = host.getNotificationController();
        int unread = notifications == null ? 0 : notifications.unreadCount();
        TextView inbox = makeButton("NOTIFICATIONS  ·  " + unread + " unread");
        LinearLayout.LayoutParams inboxParams = fullWidthButton(45);
        inboxParams.topMargin = dp(8);
        pageContent.addView(inbox, inboxParams);
        inbox.setOnClickListener(v -> {
            notificationOffset = 0;
            showPage(PAGE_NOTIFICATIONS);
        });
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
        TextView move = makeButton("MOVE CORNER");
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
                "UI scale changes the whole page. Text size is independent.", 10f);
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
        move.setOnClickListener(v -> cycleCorner());
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
                        ? "Android alerts on · history always saved"
                        : "Android alerts blocked in system settings";
        TextView state = makeLabel(status, 11f);
        state.setPadding(dp(4), 0, dp(4), dp(9));
        pageContent.addView(state);

        // INPUT, not output: the tests below create local notifications; this
        // Android listener is the source for real official ChatGPT pushes.
        boolean listenerAccess = notifications.hasOfficialPushMirrorAccess();
        String sourceStatus = !listenerAccess
                ? "Official ChatGPT push: ACCESS NEEDED"
                : notifications.isOsListenerConnected()
                    ? "Official ChatGPT push: LISTENING"
                    : "Official ChatGPT push: access granted, connecting";
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

        String watcher = notifications.isSiteMonitorActive()
                ? "Site popup listener: ACTIVE"
                : "Site popup listener: waiting for ChatGPT";
        TextView monitor = makeLabel(watcher
                + "  ·  Real captures: " + notifications.siteCapturesThisSession(), 11f);
        monitor.setTextColor(notifications.isSiteMonitorActive()
                ? 0xFFAFE8D0 : Color.LTGRAY);
        monitor.setPadding(dp(4), 0, dp(4), dp(9));
        pageContent.addView(monitor);

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

        LinearLayout toolbar = new LinearLayout(activity);
        toolbar.setOrientation(LinearLayout.HORIZONTAL);
        toolbar.setGravity(Gravity.CENTER_VERTICAL);
        TextView history = makeLabel("History (" + notifications.totalCount() + ")", 13f);
        history.setTypeface(null, android.graphics.Typeface.BOLD);
        toolbar.addView(history, new LinearLayout.LayoutParams(0, dp(40), 1f));
        TextView clear = makeButton("CLEAR");
        toolbar.addView(clear, new LinearLayout.LayoutParams(dp(65), dp(34)));
        pageContent.addView(toolbar, fullWidthWrap());
        clear.setOnClickListener(v -> new android.app.AlertDialog.Builder(activity)
                .setTitle("Clear notification history?")
                .setMessage("This removes saved notices and posted Android notices. It cannot be undone.")
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Clear", (dialog, which) -> {
                    notifications.clearHistory();
                    notificationOffset = 0;
                    refreshNotifications();
                }).show());

        int total = notifications.totalCount();
        if (total == 0) {
            TextView empty = makeLabel(
                    "No notifications saved yet. Real ChatGPT alerts, website popups and MyChatGPT status messages appear here.",
                    12f);
            empty.setPadding(dp(4), dp(8), dp(4), dp(10));
            pageContent.addView(empty);
            return;
        }
        if (notificationOffset >= total) notificationOffset = Math.max(0, total - PAGE_SIZE);
        List<NotificationStore.Entry> entries =
                notifications.recent(PAGE_SIZE, notificationOffset);
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
            TextView link = makeLabel(
                    item.chatUrl != null ? "Tap to open conversation"
                    : "official-chatgpt".equals(item.source)
                        ? "Android did not provide a chat link"
                        : "No chat link provided", 10f);
            link.setTextColor(0xFFA8D4FF);
            row.addView(link);
            LinearLayout.LayoutParams itemParams = fullWidthWrap();
            itemParams.bottomMargin = dp(7);
            pageContent.addView(row, itemParams);
            row.setOnClickListener(v -> host.openNotification(item.id));
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

    private void updatePanelSize() {
        if (panel == null || pageScroll == null) return;
        int screenWidth = activity.getResources().getDisplayMetrics().widthPixels;
        int screenHeight = activity.getResources().getDisplayMetrics().heightPixels;
        int width = Math.min(dp(310), Math.max(dp(180),
                screenWidth - insetLeft - insetRight - dp(24)));
        panel.setLayoutParams(new LinearLayout.LayoutParams(
                width, ViewGroup.LayoutParams.WRAP_CONTENT));
        int desired = currentPage == PAGE_HOME ? dp(142)
                : currentPage == PAGE_TUNE ? dp(365) : dp(400);
        int available = Math.max(dp(110),
                screenHeight - insetTop - insetBottom - dp(130));
        pageScroll.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, Math.min(desired, available)));
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

    private void cycleCorner() {
        corner = (corner + 1) % 4;
        prefs.edit().putInt(KEY_CORNER, corner).apply();
        cluster.setGravity(isRightCorner() ? Gravity.END : Gravity.START);
        reorderClusterChildren();
        updateClusterPlacement();
    }

    private void reorderClusterChildren() {
        if (cluster == null || panel == null || menuButton == null) return;
        int panelVisibility = panel.getVisibility();
        cluster.removeAllViews();

        boolean bottom = corner == CORNER_BOTTOM_LEFT
                || corner == CORNER_BOTTOM_RIGHT;
        if (bottom) {
            cluster.addView(panel);
            LinearLayout.LayoutParams gap =
                    (LinearLayout.LayoutParams) menuButton.getLayoutParams();
            gap.topMargin = dp(6);
            gap.bottomMargin = 0;
            menuButton.setLayoutParams(gap);
            cluster.addView(menuButton);
        } else {
            cluster.addView(menuButton);
            LinearLayout.LayoutParams panelParams =
                    (LinearLayout.LayoutParams) panel.getLayoutParams();
            panelParams.topMargin = dp(6);
            panelParams.bottomMargin = 0;
            panel.setLayoutParams(panelParams);
            cluster.addView(panel);
        }
        panel.setVisibility(panelVisibility);
    }

    private void installInsetTracking() {
        root.setOnApplyWindowInsetsListener((v, insets) -> {
            insetLeft = insets.getSystemWindowInsetLeft();
            insetTop = insets.getSystemWindowInsetTop();
            insetRight = insets.getSystemWindowInsetRight();
            insetBottom = insets.getSystemWindowInsetBottom();
            updateClusterPlacement();
            return insets;
        });
        root.requestApplyInsets();
    }

    private void updateClusterPlacement() {
        if (cluster == null) return;

        int gravity;
        switch (corner) {
            case CORNER_TOP_LEFT:
                gravity = Gravity.TOP | Gravity.START;
                break;
            case CORNER_BOTTOM_RIGHT:
                gravity = Gravity.BOTTOM | Gravity.END;
                break;
            case CORNER_BOTTOM_LEFT:
                gravity = Gravity.BOTTOM | Gravity.START;
                break;
            case CORNER_TOP_RIGHT:
            default:
                gravity = Gravity.TOP | Gravity.END;
                break;
        }

        FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                gravity);
        int margin = dp(8);
        params.leftMargin = insetLeft + margin;
        params.topMargin = insetTop + margin;
        params.rightMargin = insetRight + margin;
        params.bottomMargin = insetBottom + margin;
        cluster.setLayoutParams(params);
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

    private boolean isRightCorner() {
        return corner == CORNER_TOP_RIGHT || corner == CORNER_BOTTOM_RIGHT;
    }

    private int dp(int value) {
        float density = activity.getResources().getDisplayMetrics().density;
        return Math.round(value * density);
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
