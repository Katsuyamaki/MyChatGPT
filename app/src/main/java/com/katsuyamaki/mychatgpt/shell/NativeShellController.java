package com.katsuyamaki.mychatgpt.shell;

import android.app.Activity;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowInsets;
import android.view.WindowManager;
import android.webkit.WebView;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import com.katsuyamaki.mychatgpt.site.ChatGptSiteContract;

/**
 * Native MyChatGPT shell chrome layered around the main WebView.
 *
 * Owns wallpaper passthrough, transparency controls, control placement,
 * app-local UI scale, text zoom and the user-triggered force-reload action.
 */
public final class NativeShellController {

    private static final String PREFS = "mychatgpt_native_shell";
    private static final String KEY_BACKDROP = "backdrop_opacity_percent";
    private static final String KEY_UI_OPACITY = "webview_ui_opacity_percent";
    private static final String KEY_CORNER = "controls_corner";
    private static final String KEY_UI_SCALE = "ui_scale_percent";
    private static final String KEY_TEXT_SCALE = "text_scale_percent";

    private static final int DEFAULT_BACKDROP = 30;
    private static final int DEFAULT_UI_OPACITY = 100;
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

    public interface Host {
        WebView getMainWebView();
        void forceReloadCurrentChat();
        void fastPasteClipboard();
    }

    private final Activity activity;
    private final FrameLayout root;
    private final Host host;
    private final SharedPreferences prefs;

    private LinearLayout cluster;
    private LinearLayout panel;
    private TextView tuneButton;
    private TextView backdropLabel;
    private TextView uiOpacityLabel;
    private TextView uiScaleLabel;
    private TextView textScaleLabel;

    private int backdropPercent;
    private int uiOpacityPercent;
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
        uiOpacityPercent = clamp(prefs.getInt(KEY_UI_OPACITY, DEFAULT_UI_OPACITY), 0, 100);
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
        webView.setAlpha(uiOpacityPercent / 100f);
        webView.getSettings().setTextZoom(textScalePercent);
        applyUiScale(webView);
    }

    public void onPageFinished(WebView webView) {
        if (webView == null || webView != host.getMainWebView()) return;
        webView.setBackgroundColor(Color.TRANSPARENT);
        webView.evaluateJavascript(ChatGptSiteContract.WALLPAPER_TRANSPARENCY_JS, null);
        applyUiScale(webView);
        webView.getSettings().setTextZoom(textScalePercent);
        webView.setAlpha(uiOpacityPercent / 100f);
    }

    public void onConfigurationChanged() {
        configureWallpaperWindow();
        applyBackdrop();
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
        tuneButton = null;
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

        tuneButton = makeButton("TUNE");
        LinearLayout.LayoutParams tuneParams =
                new LinearLayout.LayoutParams(dp(68), dp(36));
        tuneButton.setLayoutParams(tuneParams);

        panel = new LinearLayout(activity);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setPadding(dp(12), dp(10), dp(12), dp(10));
        panel.setBackground(makeRoundedBackground(
                Color.argb(232, 24, 24, 24), 16));
        panel.setVisibility(View.GONE);
        int availableWidth = activity.getResources().getDisplayMetrics().widthPixels - dp(24);
        int panelWidth = Math.min(dp(300), Math.max(dp(220), availableWidth));
        panel.setLayoutParams(new LinearLayout.LayoutParams(
                panelWidth, ViewGroup.LayoutParams.WRAP_CONTENT));

        TextView title = makeLabel("MyChatGPT controls", 14f);
        panel.addView(title);

        backdropLabel = makeLabel("", 12f);
        backdropLabel.setPadding(0, dp(6), 0, 0);
        panel.addView(backdropLabel);
        SeekBar backdropSlider = new SeekBar(activity);
        backdropSlider.setMax(100);
        backdropSlider.setProgress(backdropPercent);
        panel.addView(backdropSlider, fullWidthWrap());

        uiOpacityLabel = makeLabel("", 12f);
        panel.addView(uiOpacityLabel);
        SeekBar opacitySlider = new SeekBar(activity);
        opacitySlider.setMax(100);
        opacitySlider.setProgress(uiOpacityPercent);
        panel.addView(opacitySlider, fullWidthWrap());

        uiScaleLabel = makeLabel("", 12f);
        panel.addView(uiScaleLabel);
        panel.addView(makeStepRow(
                () -> setUiScale(uiScalePercent - UI_SCALE_STEP),
                this::resetUiScale,
                () -> setUiScale(uiScalePercent + UI_SCALE_STEP)));

        textScaleLabel = makeLabel("", 12f);
        panel.addView(textScaleLabel);
        panel.addView(makeStepRow(
                () -> setTextScale(textScalePercent - TEXT_SCALE_STEP),
                this::resetTextScale,
                () -> setTextScale(textScalePercent + TEXT_SCALE_STEP)));

        LinearLayout actions = new LinearLayout(activity);
        actions.setOrientation(LinearLayout.HORIZONTAL);
        actions.setPadding(0, dp(6), 0, 0);

        TextView move = makeButton("MOVE CORNER");
        TextView reload = makeButton("FORCE RELOAD");
        LinearLayout.LayoutParams half = new LinearLayout.LayoutParams(
                0, dp(38), 1f);
        actions.addView(move, half);
        LinearLayout.LayoutParams halfRight = new LinearLayout.LayoutParams(
                0, dp(38), 1f);
        halfRight.leftMargin = dp(6);
        actions.addView(reload, halfRight);
        panel.addView(actions, fullWidthWrap());

        TextView fastPaste = makeButton("FAST PASTE (DIAG)");
        LinearLayout.LayoutParams fastPasteParams =
                new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, dp(38));
        fastPasteParams.topMargin = dp(6);
        panel.addView(fastPaste, fastPasteParams);

        TextView resetTransparency = makeButton("RESET TRANSPARENCY");
        LinearLayout.LayoutParams resetParams =
                new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, dp(38));
        resetParams.topMargin = dp(6);
        panel.addView(resetTransparency, resetParams);

        TextView hint = makeLabel(
                "UI scale changes the whole page. Text size is independent.",
                10f);
        hint.setTextColor(Color.LTGRAY);
        hint.setPadding(0, dp(6), 0, 0);
        panel.addView(hint);

        tuneButton.setOnClickListener(v ->
                panel.setVisibility(
                        panel.getVisibility() == View.VISIBLE
                                ? View.GONE : View.VISIBLE));

        backdropSlider.setOnSeekBarChangeListener(new SimpleSeekBarListener() {
            @Override
            public void onProgressChanged(
                    SeekBar seekBar, int progress, boolean fromUser) {
                if (!fromUser) return;
                backdropPercent = progress;
                prefs.edit().putInt(KEY_BACKDROP, backdropPercent).apply();
                applyBackdrop();
                updateLabels();
            }
        });

        opacitySlider.setOnSeekBarChangeListener(new SimpleSeekBarListener() {
            @Override
            public void onProgressChanged(
                    SeekBar seekBar, int progress, boolean fromUser) {
                if (!fromUser) return;
                uiOpacityPercent = progress;
                prefs.edit().putInt(KEY_UI_OPACITY, uiOpacityPercent).apply();
                applyUiOpacity();
                updateLabels();
            }
        });

        move.setOnClickListener(v -> cycleCorner());

        reload.setOnClickListener(v -> {
            panel.setVisibility(View.GONE);
            Toast.makeText(activity, "Refreshing current chat…", Toast.LENGTH_SHORT).show();
            host.forceReloadCurrentChat();
        });

        fastPaste.setOnClickListener(v -> {
            panel.setVisibility(View.GONE);
            host.fastPasteClipboard();
        });

        resetTransparency.setOnClickListener(v -> {
            backdropPercent = DEFAULT_BACKDROP;
            uiOpacityPercent = DEFAULT_UI_OPACITY;
            prefs.edit()
                    .putInt(KEY_BACKDROP, backdropPercent)
                    .putInt(KEY_UI_OPACITY, uiOpacityPercent)
                    .apply();
            backdropSlider.setProgress(backdropPercent);
            opacitySlider.setProgress(uiOpacityPercent);
            applyBackdrop();
            applyUiOpacity();
            updateLabels();
        });

        updateLabels();
        reorderClusterChildren();

        int insertIndex = Math.min(1, root.getChildCount());
        root.addView(cluster, insertIndex);
        updateClusterPlacement();
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
        WebView webView = host.getMainWebView();
        if (webView != null) {
            webView.getSettings().setTextZoom(textScalePercent);
        }
        updateLabels();
    }

    private void resetTextScale() {
        setTextScale(DEFAULT_TEXT_SCALE);
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
        int alpha = Math.round(255f * (backdropPercent / 100f));
        root.setBackgroundColor(Color.argb(alpha, 0, 0, 0));
    }

    private void applyUiOpacity() {
        WebView webView = host.getMainWebView();
        if (webView != null) {
            webView.setVisibility(View.VISIBLE);
            webView.setAlpha(uiOpacityPercent / 100f);
        }
    }

    private void cycleCorner() {
        corner = (corner + 1) % 4;
        prefs.edit().putInt(KEY_CORNER, corner).apply();
        cluster.setGravity(isRightCorner() ? Gravity.END : Gravity.START);
        reorderClusterChildren();
        updateClusterPlacement();
    }

    private void reorderClusterChildren() {
        if (cluster == null || panel == null || tuneButton == null) return;
        int panelVisibility = panel.getVisibility();
        cluster.removeAllViews();

        boolean bottom = corner == CORNER_BOTTOM_LEFT
                || corner == CORNER_BOTTOM_RIGHT;
        if (bottom) {
            cluster.addView(panel);
            LinearLayout.LayoutParams gap =
                    (LinearLayout.LayoutParams) tuneButton.getLayoutParams();
            gap.topMargin = dp(6);
            gap.bottomMargin = 0;
            tuneButton.setLayoutParams(gap);
            cluster.addView(tuneButton);
        } else {
            cluster.addView(tuneButton);
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
        if (uiOpacityLabel != null) {
            uiOpacityLabel.setText("ChatGPT UI opacity: " + uiOpacityPercent + "%");
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
