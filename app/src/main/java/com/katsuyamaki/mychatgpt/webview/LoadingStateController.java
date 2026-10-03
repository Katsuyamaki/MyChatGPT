package com.katsuyamaki.mychatgpt.webview;

import android.animation.AnimatorSet;
import android.animation.ObjectAnimator;
import android.animation.ValueAnimator;
import android.app.Activity;
import android.content.Context;
import android.content.res.Configuration;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.Display;
import android.view.PixelCopy;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.AccelerateDecelerateInterpolator;
import android.view.animation.Animation;
import android.view.animation.AnimationUtils;
import android.view.animation.LinearInterpolator;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.webkit.WebView;

import com.google.android.material.progressindicator.LinearProgressIndicator;
import com.katsuyamaki.mychatgpt.R;
import com.katsuyamaki.mychatgpt.diagnostic.PerformanceProbe;
import com.katsuyamaki.mychatgpt.site.ChatGptSiteContract;

import java.util.Locale;

/**
 * Owns initial-load presentation, real DOM-ready state, optional progress,
 * and the resume snapshot used to mask WebView surface recreation flashes.
 *
 * The Activity remains the lifecycle/view host and forwards relevant events.
 */
public final class LoadingStateController {

    private static final String TAG = "MyChatGPTLoading";
    private static final int INITIAL_OVERLAY_DELAY_MS = 200;
    private static final int RESUME_SNAPSHOT_FADE_DELAY_MS = 300;

    private final Activity activity;
    private final ViewGroup rootLayout;
    private final View loadingOverlay;
    private final ImageView loadingLogo;
    private final LinearProgressIndicator loadingProgressBar;
    private final Handler snapshotHandler = new Handler(Looper.getMainLooper());

    private AnimatorSet loadingLogoAnim;
    private ImageView resumeSnapshot;
    private Bitmap resumeSnapshotBitmap;
    private boolean initialLoadComplete;

    public LoadingStateController(Activity activity, ViewGroup rootLayout) {
        this.activity = activity;
        this.rootLayout = rootLayout;
        this.loadingOverlay = activity.findViewById(R.id.loading_overlay);
        this.loadingLogo = activity.findViewById(R.id.loading_logo);
        this.loadingProgressBar = activity.findViewById(R.id.loading_progress_bar);

        syncLoadingProgressBar();
    }

    /**
     * Called after the Activity has applied its window/system-bar colors.
     * Preserves the inherited display diagnostic, snapshot-overlay geometry,
     * logo tint and initial hardware-layer animation.
     */
    public void initializePresentation() {
        logDisplayMode();
        attachResumeSnapshot();
        updateTheme(activity.getResources().getConfiguration());
        startLoadingLogoAnimation();
    }

    public boolean isInitialLoadComplete() {
        return initialLoadComplete;
    }

    /** Renderer-recovery/offline-retry reset without forcing overlay visible. */
    public void resetInitialLoad() {
        initialLoadComplete = false;
    }

    /**
     * Offline dialog presentation hides the loading overlay while the dialog
     * owns the screen.
     */
    public void hideForOfflineDialog() {
        if (loadingOverlay != null) {
            loadingOverlay.setVisibility(View.GONE);
        }
    }

    /**
     * Retry starts a fresh initial-load presentation immediately, matching the
     * inherited offline-dialog path.
     */
    public void showForRetry() {
        initialLoadComplete = false;
        if (loadingOverlay != null) {
            loadingOverlay.setVisibility(View.VISIBLE);
            startLoadingLogoAnimation();
            syncLoadingProgressBar();
        }
    }

    public void onPageStarted(WebView webView) {
        if (initialLoadComplete
                || loadingOverlay == null
                || loadingOverlay.getVisibility() == View.VISIBLE) {
            return;
        }

        webView.postDelayed(() -> {
            if (!initialLoadComplete
                    && loadingOverlay != null
                    && loadingOverlay.getVisibility() != View.VISIBLE) {
                loadingOverlay.setVisibility(View.VISIBLE);
                if (loadingLogo != null) {
                    loadingLogo.setImageResource(
                            isDarkMode() ? R.drawable.logo_white : R.drawable.logo_black);
                    startLoadingLogoAnimation();
                }
                syncLoadingProgressBar();
            }
        }, INITIAL_OVERLAY_DELAY_MS);
    }

    /**
     * Raw WebView progress is intentionally capped at 90%; the final 10% is
     * completed only when ChatGPT's real-ready signal dismisses the overlay.
     */
    public void onProgressChanged(int newProgress) {
        if (loadingProgressBar != null
                && loadingOverlay != null
                && !initialLoadComplete
                && loadingOverlay.getVisibility() == View.VISIBLE) {
            int clamped = Math.max(0, Math.min(100, newProgress));
            loadingProgressBar.setProgressCompat(clamped * 9 / 10, true);
        }
    }

    /**
     * onPageFinished fallback only. ChatGptSiteContract's page-ready watcher
     * remains the primary readiness signal.
     */
    public void onPageFinished(WebView webView) {
        if (initialLoadComplete) return;
        webView.postDelayed(() -> {
            if (loadingOverlay == null || initialLoadComplete) return;
            PerformanceProbe.mark("loading_ready_fallback");
            hideLoadingOverlayNow();
        }, ChatGptSiteContract.PAGE_FINISHED_READY_FALLBACK_MS);
    }

    /**
     * Returns true only when this call transitions the initial load to ready.
     * The caller can use that edge to kick deferred transfer/share work.
     */
    public boolean onDomReady() {
        if (activity.isFinishing() || initialLoadComplete) return false;
        PerformanceProbe.mark("loading_ready_dom");
        hideLoadingOverlayNow();
        return true;
    }

    public void onWindowFocusChanged(boolean hasFocus) {
        if (!hasFocus) {
            captureResumeSnapshot();
        } else {
            scheduleSnapshotFadeOut(RESUME_SNAPSHOT_FADE_DELAY_MS);
        }
    }

    public void updateTheme(Configuration configuration) {
        if (loadingLogo == null || configuration == null) return;
        boolean dark = (configuration.uiMode & Configuration.UI_MODE_NIGHT_MASK)
                == Configuration.UI_MODE_NIGHT_YES;
        loadingLogo.setImageResource(
                dark ? R.drawable.logo_white : R.drawable.logo_black);
    }

    public void destroy() {
        snapshotHandler.removeCallbacksAndMessages(null);
        stopLoadingLogoAnimation();

        if (resumeSnapshot != null) {
            resumeSnapshot.setImageBitmap(null);
            try {
                ViewGroup parent = (ViewGroup) resumeSnapshot.getParent();
                if (parent != null) parent.removeView(resumeSnapshot);
            } catch (Throwable ignored) {
            }
            resumeSnapshot = null;
        }

        if (resumeSnapshotBitmap != null) {
            resumeSnapshotBitmap.recycle();
            resumeSnapshotBitmap = null;
        }
    }

    private void hideLoadingOverlayNow() {
        if (loadingOverlay == null || initialLoadComplete) return;
        initialLoadComplete = true;
        PerformanceProbe.mark("loading_overlay_fade_start");

        if (loadingProgressBar != null
                && loadingProgressBar.getVisibility() == View.VISIBLE) {
            loadingProgressBar.setProgressCompat(100, true);
        }

        Animation fadeOut =
                AnimationUtils.loadAnimation(activity, R.anim.fade_out);
        fadeOut.setAnimationListener(new Animation.AnimationListener() {
            @Override
            public void onAnimationStart(Animation animation) {}

            @Override
            public void onAnimationEnd(Animation animation) {
                loadingOverlay.setVisibility(View.GONE);
                stopLoadingLogoAnimation();
                PerformanceProbe.mark("loading_overlay_hidden");
            }

            @Override
            public void onAnimationRepeat(Animation animation) {}
        });
        loadingOverlay.startAnimation(fadeOut);
    }

    private void startLoadingLogoAnimation() {
        if (loadingLogo == null) return;
        stopLoadingLogoAnimation();

        loadingLogo.setLayerType(View.LAYER_TYPE_HARDWARE, null);

        ObjectAnimator spin = ObjectAnimator.ofFloat(
                loadingLogo, View.ROTATION, 0f, 360f);
        spin.setDuration(2000L);
        spin.setInterpolator(new LinearInterpolator());
        spin.setRepeatCount(ValueAnimator.INFINITE);
        spin.setRepeatMode(ValueAnimator.RESTART);

        ObjectAnimator pulse = ObjectAnimator.ofFloat(
                loadingLogo, View.ALPHA, 0.3f, 1f);
        pulse.setDuration(1000L);
        pulse.setInterpolator(new AccelerateDecelerateInterpolator());
        pulse.setRepeatCount(ValueAnimator.INFINITE);
        pulse.setRepeatMode(ValueAnimator.REVERSE);

        loadingLogoAnim = new AnimatorSet();
        loadingLogoAnim.playTogether(spin, pulse);
        loadingLogoAnim.start();
    }

    private void stopLoadingLogoAnimation() {
        if (loadingLogoAnim != null) {
            loadingLogoAnim.cancel();
            loadingLogoAnim = null;
        }
        if (loadingLogo != null) {
            loadingLogo.clearAnimation();
            loadingLogo.setLayerType(View.LAYER_TYPE_NONE, null);
        }
    }

    private void syncLoadingProgressBar() {
        if (loadingProgressBar == null) return;
        boolean show = false;
        try {
            show = activity.getSharedPreferences(
                    "webgpt_prefs", Context.MODE_PRIVATE)
                    .getBoolean("loading_progress", false);
        } catch (Throwable t) {
            Log.e(TAG, "loading-progress pref read failed", t);
        }
        loadingProgressBar.setVisibility(show ? View.VISIBLE : View.GONE);
        if (show) {
            loadingProgressBar.setProgressCompat(0, false);
        }
    }

    private void logDisplayMode() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return;
        try {
            Display display = activity.getWindowManager().getDefaultDisplay();
            Display.Mode current = display.getMode();
            StringBuilder modes = new StringBuilder();
            for (Display.Mode mode : display.getSupportedModes()) {
                modes.append(String.format(
                        Locale.US,
                        "%dx%d@%.0f ",
                        mode.getPhysicalWidth(),
                        mode.getPhysicalHeight(),
                        mode.getRefreshRate()));
            }
            Log.d(TAG, String.format(
                    Locale.US,
                    "display: current mode %dx%d@%.0fHz, supported: %s",
                    current.getPhysicalWidth(),
                    current.getPhysicalHeight(),
                    current.getRefreshRate(),
                    modes.toString().trim()));
        } catch (Throwable t) {
            Log.e(TAG, "display mode query failed", t);
        }
    }

    private void attachResumeSnapshot() {
        resumeSnapshot = new ImageView(activity);
        resumeSnapshot.setLayoutParams(new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT));
        resumeSnapshot.setVisibility(View.GONE);
        try {
            ((ViewGroup) activity.getWindow().getDecorView())
                    .addView(resumeSnapshot);
        } catch (Throwable t) {
            Log.e(TAG, "snapshot overlay attach failed", t);
            resumeSnapshot = null;
        }
    }

    /**
     * Capture the full window before backgrounding so the previous frame can
     * mask the brief WebView surface-recreation flash on resume.
     */
    private void captureResumeSnapshot() {
        if (resumeSnapshot == null) return;

        View decor = activity.getWindow() != null
                ? activity.getWindow().getDecorView() : null;
        if (decor == null || decor.getWidth() <= 0 || decor.getHeight() <= 0) {
            return;
        }

        final int width = decor.getWidth();
        final int height = decor.getHeight();
        final int previousVisibility = resumeSnapshot.getVisibility();
        PerformanceProbe.mark(
                "resume_snapshot_capture_start",
                "size=" + width + "x" + height);
        resumeSnapshot.setVisibility(View.GONE);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            try {
                final Bitmap bitmap = Bitmap.createBitmap(
                        width, height, Bitmap.Config.ARGB_8888);
                PixelCopy.request(
                        activity.getWindow(),
                        bitmap,
                        result -> {
                            if (result == PixelCopy.SUCCESS) {
                                activity.runOnUiThread(() -> {
                                    replaceSnapshotBitmap(bitmap);
                                    resumeSnapshot.setVisibility(View.VISIBLE);
                                    PerformanceProbe.mark("resume_snapshot_visible");
                                    if (activity.hasWindowFocus()) {
                                        scheduleSnapshotFadeOut(
                                                RESUME_SNAPSHOT_FADE_DELAY_MS);
                                    }
                                });
                            } else {
                                PerformanceProbe.mark(
                                        "resume_snapshot_capture_failed",
                                        "code=" + result);
                                Log.w(TAG,
                                        "snapshot PixelCopy failed code=" + result);
                                activity.runOnUiThread(() -> {
                                    if (resumeSnapshot != null) {
                                        resumeSnapshot.setVisibility(
                                                previousVisibility);
                                    }
                                });
                            }
                        },
                        snapshotHandler);
            } catch (Throwable t) {
                Log.e(TAG, "PixelCopy threw", t);
                if (resumeSnapshot != null) {
                    resumeSnapshot.setVisibility(previousVisibility);
                }
            }
            return;
        }

        try {
            Bitmap bitmap = Bitmap.createBitmap(
                    width, height, Bitmap.Config.ARGB_8888);
            Canvas canvas = new Canvas(bitmap);
            decor.draw(canvas);
            replaceSnapshotBitmap(bitmap);
            resumeSnapshot.setVisibility(View.VISIBLE);
        } catch (Throwable t) {
            Log.e(TAG, "snapshot draw threw", t);
            if (resumeSnapshot != null) {
                resumeSnapshot.setVisibility(previousVisibility);
            }
        }
    }

    private void replaceSnapshotBitmap(Bitmap bitmap) {
        if (resumeSnapshotBitmap != null) {
            resumeSnapshotBitmap.recycle();
        }
        resumeSnapshotBitmap = bitmap;
        if (resumeSnapshot != null) {
            resumeSnapshot.setImageBitmap(bitmap);
        }
    }

    private void scheduleSnapshotFadeOut(int delayMs) {
        if (resumeSnapshot == null
                || resumeSnapshot.getVisibility() != View.VISIBLE) {
            return;
        }

        snapshotHandler.removeCallbacksAndMessages(null);
        snapshotHandler.postDelayed(() ->
                activity.runOnUiThread(() -> {
                    if (resumeSnapshot != null
                            && resumeSnapshot.getVisibility() == View.VISIBLE) {
                        resumeSnapshot.setVisibility(View.GONE);
                        resumeSnapshot.setImageBitmap(null);
                        if (resumeSnapshotBitmap != null) {
                            resumeSnapshotBitmap.recycle();
                            resumeSnapshotBitmap = null;
                        }
                        PerformanceProbe.mark("resume_snapshot_hidden");
                    }
                }), delayMs);
    }

    private boolean isDarkMode() {
        int nightModeFlags =
                activity.getResources().getConfiguration().uiMode
                        & Configuration.UI_MODE_NIGHT_MASK;
        return nightModeFlags == Configuration.UI_MODE_NIGHT_YES;
    }
}
