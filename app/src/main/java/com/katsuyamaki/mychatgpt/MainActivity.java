package com.katsuyamaki.mychatgpt;

import com.katsuyamaki.mychatgpt.BuildConfig;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.AnimatorSet;
import android.animation.ObjectAnimator;
import android.animation.ValueAnimator;
import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.res.Configuration;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.Canvas;
import android.graphics.drawable.ColorDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.SystemClock;
import android.os.Message;
import android.provider.MediaStore;
import android.util.Log;
import android.view.Display;
import android.view.PixelCopy;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.InputMethodManager;
import android.view.animation.AccelerateDecelerateInterpolator;
import android.view.animation.Animation;
import android.view.animation.AnimationUtils;
import android.view.animation.LinearInterpolator;
import android.webkit.CookieManager;
import android.webkit.ConsoleMessage;
import android.webkit.PermissionRequest;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebStorage;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.Toast;

import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.core.splashscreen.SplashScreen;
import androidx.core.splashscreen.SplashScreenViewProvider;
import androidx.webkit.WebViewCompat;

import com.katsuyamaki.mychatgpt.webview.CrashTracker;
import com.katsuyamaki.mychatgpt.webview.MainWebViewController;
import com.katsuyamaki.mychatgpt.webview.LoadingStateController;
import com.katsuyamaki.mychatgpt.webview.PopupAuthController;
import com.katsuyamaki.mychatgpt.webview.TransferController;
import com.katsuyamaki.mychatgpt.webview.WebBridgeController;
import com.katsuyamaki.mychatgpt.webview.WebViewManagerDialog;
import com.katsuyamaki.mychatgpt.webview.WebViewUtil;
import com.katsuyamaki.mychatgpt.webview.WelcomeDialog;
import com.katsuyamaki.mychatgpt.site.ChatGptSiteContract;
import com.katsuyamaki.mychatgpt.shell.NativeShellController;
import com.katsuyamaki.mychatgpt.notifications.NotificationController;
import com.katsuyamaki.mychatgpt.notifications.NotificationStore;
import com.katsuyamaki.mychatgpt.notifications.SiteNotificationMonitor;
import com.google.android.material.progressindicator.LinearProgressIndicator;

import java.io.File;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public class MainActivity extends Activity {

    private static final String TAG = "MyChatGPTApp";
    private static final String PREFS_NAME = "webgpt_prefs";

    private static final int REQUEST_MEDIA_PERM = 1004;
    private static final int REQUEST_NOTIFICATION_PERM = 1005;

    WebView webview;
    ViewGroup rootLayout;
    private LoadingStateController loadingStateController;
    private MainWebViewController mainWebViewController;
    private PopupAuthController popupAuthController;
    private TransferController transferController;
    private NativeShellController nativeShellController;
    private NotificationController notificationController;
    private boolean testAfterNotificationPermission;

    // Pending WebView permission request (camera/mic) while the OS dialog is up
    private PermissionRequest pendingWebPermissionRequest;

    // Guard so the offline dialog is not shown twice for one failure
    private boolean offlineDialogShowing;

    @SuppressLint("SetJavaScriptEnabled")
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // ─── Android 12+ splash screen (v6.27) ──────────────────────────
        // MainActivity launches with Theme.AppSplash (see the manifest).
        // The call below swaps the real AppTheme back in before any view
        // work happens. On Android < 12 that is the ONLY effect: the launch
        // window's background/bars are pinned to the same tokens AppTheme
        // uses, so older devices go straight into the loading screen with
        // no flash. On Android 12+ the system additionally shows its splash
        // screen first — a plain frame of the loading-screen background
        // color (day/night aware; the splash icon is a transparent drawable,
        // i.e. deliberately nothing) — and the exit listener below takes
        // over the hand-off: WITHOUT a listener some OEMs play their default
        // exit animation, which on several test devices read as "the icon
        // expands until it fills the screen, then the app fades in". With
        // the listener the splash view crossfades over 300 ms into the
        // app's loading screen, which is painted the exact same background
        // color — so the only visible change is the loading logo (and later
        // the spinner) fading in. No icon zoom, no color flashing, same on
        // every device and OEM.
        SplashScreen splashScreen = SplashScreen.installSplashScreen(this);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            splashScreen.setOnExitAnimationListener(
                    new SplashScreen.OnExitAnimationListener() {
                @Override
                public void onSplashScreenExit(SplashScreenViewProvider provider) {
                    ObjectAnimator fade = ObjectAnimator.ofFloat(
                            provider.getView(), View.ALPHA, 1f, 0f);
                    fade.setDuration(300L);
                    fade.addListener(new AnimatorListenerAdapter() {
                        @Override
                        public void onAnimationEnd(Animator animation) {
                            // The system expects the app to remove the view
                            // itself once its custom exit animation ends.
                            provider.remove();
                        }
                    });
                    fade.start();
                }
            });
        }

        // ─── Foreign-task relay (text-share task bug, rounds 18-19) ────────
        // On some devices/OEMs a TEXT share launches the target INSIDE the
        // sender's task (so back returns to the sender, recents keeps the
        // SENDER's identity, and the real MyChatGPT task is ignored — a fresh
        // instance every time). Detection: we are not the root of our task.
        //
        // Round 18 tried to fix this by re-launching ourselves with NEW_TASK
        // directly from onCreate — but at that point this instance has NO
        // window and is not resumed, so OEM task managers created the MyChatGPT
        // task WITHOUT ever bringing it to the front (round 19 symptom: text
        // copied + "paste it" toast, but MyChatGPT never opened).
        //
        // Corrected approach: hand the share to a momentary transparent
        // trampoline (ShareRelayActivity) started in THIS task — a plain
        // same-task start the system always honors — and finish. Once the
        // trampoline is genuinely resumed (our process foreground, window
        // attached), IT forwards the share to MainActivity with NEW_TASK —
        // a foreground start, which is guaranteed to bring MyChatGPT's own task
        // to the front on every Android version and OEM.
        //
        // Files are deliberately excluded: their share intents can carry URI
        // grants that a re-launch would drop.
        if (isFinishing()) return;
        Intent bootIntent = getIntent();
        if (bootIntent != null
                && !bootIntent.getBooleanExtra(
                        ShareRelayActivity.EXTRA_RELAUNCHED, false)
                && !isTaskRoot()
                && !NotificationController.ACTION_OPEN.equals(bootIntent.getAction())
                && bootIntent.getParcelableExtra(Intent.EXTRA_STREAM) == null) {
            try {
                Intent relay = new Intent(this, ShareRelayActivity.class);
                relay.putExtra(ShareRelayActivity.EXTRA_SHARE_INTENT, bootIntent);
                startActivity(relay);  // same task: no flags, no cross-task start
                finish();
                return;
            } catch (Throwable t) {
                // Never expected. Fall through and boot normally in this
                // task (round-17 behavior: visible, functional, wrong task)
                // rather than leaving the user with nothing at all.
                Log.e(TAG, "share relay start failed; booting in place", t);
            }
        }

        // ─── WebView switcher pre-launch checks ────────────────────────────
        // (1) If the current WebView is too old to support DOCUMENT_START_SCRIPT,
        //     force-open the WebView Manager picker non-cancelable. The user
        //     must pick or install a newer one before they can use the app.
        // (2) If the previous N launches all crashed, assume the chosen WebView
        //     is broken on this device — bounce the user to Settings to pick
        //     a different one.
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            if (!WebViewUtil.isSupported()) {
                final WebViewManagerDialog[] dlg = new WebViewManagerDialog[1];
                dlg[0] = new WebViewManagerDialog(this,
                        new DialogInterface.OnDismissListener() {
                            @Override
                            public void onDismiss(DialogInterface d) {
                                if (dlg[0] != null && dlg[0].changedWebView()) {
                                    // Force restart to load the new WebView
                                    Intent i = getPackageManager()
                                            .getLaunchIntentForPackage(getPackageName());
                                    if (i != null) {
                                        i.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP
                                                | Intent.FLAG_ACTIVITY_NEW_TASK
                                                | Intent.FLAG_ACTIVITY_CLEAR_TASK);
                                        startActivity(i);
                                    }
                                    finishAffinity();
                                    android.os.Process.killProcess(android.os.Process.myPid());
                                } else {
                                    finish();
                                }
                            }
                        });
                dlg[0].setCancelable(false);
                dlg[0].show();
                return;
            }

            if (CrashTracker.hasCrashes()) {
                Log.w(TAG, "Crash threshold reached; bouncing to WebView Manager");
                Toast.makeText(this, R.string.webview_pick_another, Toast.LENGTH_LONG).show();
                CrashTracker.reset();
                Intent i = new Intent(this, SettingsActivity.class);
                i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                startActivity(i);
                finish();
                return;
            }
        }
        // ─── End WebView switcher pre-launch checks ────────────────────────

        setContentView(R.layout.activity_main);

        webview = findViewById(R.id.activity_main_webview);
        rootLayout = (ViewGroup) webview.getParent();
        notificationController = new NotificationController(this);
        mainWebViewController = new MainWebViewController(
                this,
                rootLayout,
                ChatGptSiteContract.newRequestHeaders(),
                ChatGptSiteContract.MOBILE_USER_AGENT,
                ChatGptSiteContract::isAllowedHost);
        transferController = new TransferController(
                this,
                new TransferController.Host() {
                    @Override
                    public WebView getMainWebView() {
                        return webview;
                    }

                    @Override
                    public boolean isInitialLoadComplete() {
                        return loadingStateController != null
                                && loadingStateController.isInitialLoadComplete();
                    }
                });
        popupAuthController = new PopupAuthController(
                this,
                rootLayout,
                mainWebViewController,
                new PopupAuthController.Host() {
                    @Override
                    public WebView getMainWebView() {
                        return webview;
                    }

                    @Override
                    public int getWebViewBackgroundColor() {
                        return isDarkMode() ? 0xFF0D0D0D : 0xFFFFFFFF;
                    }

                    @Override
                    public Object createJavascriptBridge(WebView popup) {
                        return createWebBridge(popup);
                    }

                    @Override
                    public void injectPageOverrides(WebView popup) {
                        injectAllOverrides(popup);
                    }

                    @Override
                    public void handleWebPermissionRequest(PermissionRequest request) {
                        MainActivity.this.handleWebPermissionRequest(request);
                    }

                    @Override
                    public boolean openFileChooser(ValueCallback<Uri[]> callback) {
                        return transferController.openFileChooser(callback);
                    }
                });
        loadingStateController =
                new LoadingStateController(this, rootLayout);
        nativeShellController = new NativeShellController(
                this,
                (FrameLayout) rootLayout,
                new NativeShellController.Host() {
                    @Override
                    public WebView getMainWebView() {
                        return webview;
                    }

                    @Override
                    public void forceReloadCurrentChat() {
                        if (webview == null || mainWebViewController == null) return;
                        String current = webview.getUrl();
                        if (!ChatGptSiteContract.isChatGptWebUrl(current)) {
                            current = ChatGptSiteContract.MAIN_URL;
                        }
                        loadingStateController.resetInitialLoad();
                        mainWebViewController.forceReloadCurrent(webview, current);
                    }

                    @Override
                    public NotificationController getNotificationController() {
                        return notificationController;
                    }

                    @Override
                    public void openNotification(long id) {
                        MainActivity.this.openNotification(id);
                    }

                    @Override
                    public void requestNotificationPermission() {
                        MainActivity.this.requestAndroidNotificationPermission(false);
                    }

                    @Override
                    public void sendTestNotification() {
                        MainActivity.this.sendTestNotification();
                    }
                });
        loadingStateController.initializePresentation();

        // Wire the WebView up (initial setup; recreated in place if the
        // renderer ever dies — see onRenderProcessGone).
        setupMainWebView(webview);

        // Best-effort sweep of stale one-shot files (camera captures, shared
        // copies) so the cache directory cannot grow without bound.
        sweepCacheDir();

        // First-launch welcome dialog — tells the user about the hidden
        // Settings menu (where the WebView switcher lives). Shows only once
        // per install; dismissed with "Understood".
        WelcomeDialog.showIfNeeded(this);

        // ─── State restoration (activity destroyed / process restarted) ───
        // DuckAssist 0.4.2 pattern: when the system hands back a saved
        // state (activity recreated after a config change we could not
        // intercept, or process killed in the background), restore the
        // WebView's back/forward list and resume the EXACT page — the open
        // conversation included — instead of cold-booting the homepage
        // (the "app relaunches and loses my chat" symptom).
        Intent launchIntent = getIntent();
        boolean launchedFromNotification = isNotificationIntent(launchIntent);
        String chatFromNotification = launchedFromNotification
                ? consumeNotificationIntent(launchIntent) : null;
        boolean restoredFromState =
                mainWebViewController.restoreNavigationState(webview, savedInstanceState);
        if (!restoredFromState) {
            mainWebViewController.loadUrl(webview,
                    chatFromNotification != null
                            ? chatFromNotification : ChatGptSiteContract.MAIN_URL);
        } else if (chatFromNotification != null) {
            mainWebViewController.loadUrl(webview, chatFromNotification);
        }
        if (launchedFromNotification && chatFromNotification == null) {
            nativeShellController.showNotificationsPage();
        }

        // Process share-from-outside intent AFTER the initial load has been
        // triggered, so shared text no longer causes a second duplicate
        // navigation (and no longer resets an open conversation when the
        // activity is already running via onNewIntent).
        // SKIPPED when restored from state: the launch intent is stale (it
        // is the intent that originally created this task — re-processing
        // it after a rotation/process restart would re-copy an old share
        // to the clipboard every time).
        if (!restoredFromState && !launchedFromNotification
                && launchIntent != null) {
            transferController.handleShareIntent(launchIntent);
        }
    }

    private boolean isDarkMode() {
        return (getResources().getConfiguration().uiMode
                & Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES;
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        if (isNotificationIntent(intent)) {
            String chatUrl = consumeNotificationIntent(intent);
            if (chatUrl != null) {
                nativeShellController.closePanelIfOpen();
                mainWebViewController.loadUrl(webview, chatUrl);
            } else {
                nativeShellController.showNotificationsPage();
            }
        } else {
            transferController.handleShareIntent(intent);
        }
    }

    private boolean isNotificationIntent(Intent intent) {
        return intent != null
                && NotificationController.ACTION_OPEN.equals(intent.getAction());
    }

    /** The intent contains only a local record ID; the destination is from our DB. */
    private String consumeNotificationIntent(Intent intent) {
        if (notificationController == null || intent == null) return null;
        long id = intent.getLongExtra(NotificationController.EXTRA_ID, -1L);
        NotificationStore.Entry item = notificationController.find(id);
        if (item == null) return null;
        notificationController.markRead(id);
        if (nativeShellController != null) nativeShellController.refreshNotifications();
        return item.chatUrl;
    }

    private void openNotification(long id) {
        if (notificationController == null) return;
        NotificationStore.Entry item = notificationController.find(id);
        if (item == null) return;
        notificationController.markRead(id);
        if (nativeShellController != null) nativeShellController.refreshNotifications();
        String chatUrl = NotificationController.safeChatUrl(item.chatUrl);
        if (chatUrl != null && mainWebViewController != null && webview != null) {
            nativeShellController.closePanelIfOpen();
            mainWebViewController.loadUrl(webview, chatUrl);
        } else {
            Toast.makeText(this, "This notice has no linked conversation",
                    Toast.LENGTH_SHORT).show();
        }
    }

    private void requestAndroidNotificationPermission(boolean forTest) {
        if (notificationController == null) return;
        if (notificationController.needsPermission()) {
            if (forTest) testAfterNotificationPermission = true;
            ActivityCompat.requestPermissions(this,
                    new String[]{android.Manifest.permission.POST_NOTIFICATIONS},
                    REQUEST_NOTIFICATION_PERM);
        } else if (forTest) {
            saveTestNotification();
        }
    }

    private void sendTestNotification() {
        if (notificationController == null) return;
        notificationController.setAndroidEnabled(true);
        requestAndroidNotificationPermission(true);
        if (nativeShellController != null) nativeShellController.refreshNotifications();
    }

    private void saveTestNotification() {
        if (notificationController == null) return;
        notificationController.recordTestNotification(
                webview == null ? null : webview.getUrl());
        if (nativeShellController != null) nativeShellController.refreshNotifications();
    }

    @Override
    protected void onStop() {
        super.onStop();
        if (transferController != null) {
            transferController.onStop();
        }
    }

    private WebBridgeController createWebBridge(WebView hostWebView) {
        return new WebBridgeController(
                this,
                hostWebView,
                transferController,
                new WebBridgeController.Host() {
                    @Override
                    public WebView getMainWebView() {
                        return webview;
                    }

                    @Override
                    public void onPageReady() {
                        if (loadingStateController != null
                                && loadingStateController.onDomReady()) {
                            transferController.kickPendingSharePipelines();
                        }
                    }

                    @Override
                    public void onSiteNotification(String text, String candidateUrl) {
                        if (notificationController != null) {
                            long id = notificationController.recordSiteNotification(
                                    text, candidateUrl);
                            if (id > 0 && nativeShellController != null) {
                                nativeShellController.refreshNotifications();
                            }
                        }
                    }
                });
    }

    /** Wire up the main WebView (the initial instance from the layout, or a
     *  fresh one after a renderer crash). */
    private void setupMainWebView(WebView mainWebView) {
        mainWebViewController.configureMainWebView(
                mainWebView,
                Color.TRANSPARENT,
                createWebBridge(mainWebView),
                "AndroidBridge");
        if (nativeShellController != null) {
            nativeShellController.applyToMainWebView(mainWebView);
        }
        setupClients(mainWebView);
        transferController.setupDownloads(mainWebView);
        setupImageContextMenu(mainWebView);
        installDocumentStartOverrides(mainWebView);
    }

    /**
     * Inject the page overrides at DOCUMENT START in EVERY frame (main frame
     * AND iframes). This is the critical piece for the site's export/share
     * features: those run inside iframes, and evaluateJavascript() at
     * onPageFinished only ever touches the main frame — which is why blob
     * downloads and the share button kept failing while the page itself
     * worked. addDocumentStartJavaScript runs before any site script, in
     * every frame, on every navigation.
     */
    private void installDocumentStartOverrides(WebView webView) {
        try {
            if (WebViewUtil.isSupported()) {
                WebViewCompat.addDocumentStartJavaScript(
                        webView, ChatGptSiteContract.PAGE_OVERRIDES_JS, java.util.Collections.singleton("*"));
                WebViewCompat.addDocumentStartJavaScript(
                        webView,
                        ChatGptSiteContract.WALLPAPER_TRANSPARENCY_JS,
                        java.util.Collections.singleton("*"));
                WebViewCompat.addDocumentStartJavaScript(
                        webView,
                        ChatGptSiteContract.LARGE_PASTE_ACCELERATOR_JS,
                        java.util.Collections.singleton("*"));
                // Registered AFTER ChatGptSiteContract.PAGE_OVERRIDES_JS on purpose: the ready
                // watcher's settle fallback reads window.__webgptLoad, which
                // the overrides script installs — document-start scripts run
                // in registration order.
                WebViewCompat.addDocumentStartJavaScript(
                        webView, ChatGptSiteContract.PAGE_READY_WATCHER_JS, java.util.Collections.singleton("*"));
                // Focus guard: see ChatGptSiteContract.FOCUS_GUARD_JS above. Registered last so
                // any page-script .focus() attempts can be intercepted from
                // the very first script execution.
                WebViewCompat.addDocumentStartJavaScript(
                        webView, ChatGptSiteContract.FOCUS_GUARD_JS, java.util.Collections.singleton("*"));
                // setupMainWebView is called only for the primary WebView.
                // Do not compare to the field: during renderer recovery the new
                // WebView is configured before webview is reassigned.
                WebViewCompat.addDocumentStartJavaScript(
                        webView, SiteNotificationMonitor.SCRIPT,
                        java.util.Collections.singleton("*"));
            }
        } catch (Throwable t) {
            Log.e(TAG, "addDocumentStartJavaScript failed", t);
        }
    }

    private void setupClients(final WebView webView) {
        webView.setWebChromeClient(new WebChromeClient() {
            @Override
            public boolean onConsoleMessage(ConsoleMessage cm) {
                // Do not mirror the site's console (it can contain chat
                // fragments) into logcat on release builds.
                if (BuildConfig.EXPERIMENTAL) {
                    Log.d(TAG, cm.message() + " -- line " + cm.lineNumber() + " of " + cm.sourceId());
                }
                return true;
            }

            @Override
            public void onProgressChanged(WebView view, int newProgress) {
                loadingStateController.onProgressChanged(newProgress);
            }

            @Override
            public void onPermissionRequest(final PermissionRequest request) {
                handleWebPermissionRequest(request);
            }

            @Override
            public boolean onShowFileChooser(WebView w,
                                             ValueCallback<Uri[]> callback,
                                             FileChooserParams fileChooserParams) {
                // Shared implementation (was previously duplicated in the
                // popup client, and the two copies had drifted apart).
                return transferController.openFileChooser(callback);
            }

            @Override
            public boolean onCreateWindow(WebView view, boolean isDialog,
                                          boolean isUserGesture, Message resultMsg) {
                // The window.open JS override handles external links (X, Reddit, LinkedIn)
                // BEFORE they reach onCreateWindow. Internal popups (share menu, OAuth)
                // go through createPopup which creates a proper popup WebView.
                return popupAuthController.createPopup(resultMsg);
            }
        });

        webView.setWebViewClient(new WebViewClient() {
            @SuppressWarnings("deprecation")
            @Override
            public boolean shouldOverrideUrlLoading(WebView v, String url) {
                // Legacy callback (API < 24, fires for all frames): original
                // permissive behavior — null-host URLs must load, or blob:/
                // data: iframe apps (investigation panel) break.
                return mainWebViewController.shouldOverrideFrame(url);
            }

            @Override
            public boolean shouldOverrideUrlLoading(WebView v,
                                                    android.webkit.WebResourceRequest request) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N
                        && !request.isForMainFrame()) {
                    return mainWebViewController.shouldOverrideFrame(request.getUrl().toString());
                }
                return mainWebViewController.shouldOverrideMainFrame(request.getUrl().toString());
            }

            @SuppressWarnings("deprecation")
            @Override
            public void onReceivedError(WebView view, int errorCode,
                                        String description, String failingUrl) {
                // Legacy callback (API < 23) fires for the main frame only.
                if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
                    mainWebViewController.onMainFrameLoadSettled(view);
                    showOfflineDialog();
                }
            }

            @Override
            public void onReceivedError(WebView view,
                                        android.webkit.WebResourceRequest request,
                                        android.webkit.WebResourceError error) {
                // API 23+: react to main-frame failures only, so a failed
                // subresource on the SPA does not trigger the dialog.
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M
                        && request.isForMainFrame()) {
                    mainWebViewController.onMainFrameLoadSettled(view);
                    showOfflineDialog();
                }
            }

            @Override
            public boolean onRenderProcessGone(WebView view,
                                               android.webkit.RenderProcessGoneDetail detail) {
                // Renderer death (OOM / driver crash): rebuild the WebView in
                // place instead of letting the whole process die.
                boolean wasMain = (view == webview);
                // didCrash()==true → renderer crashed; false → OS killed it
                // for memory pressure (the more common case on resume from
                // task manager).
                boolean didCrash = (detail != null) && detail.didCrash();
                String reasonStr = didCrash ? "CRASH" : "OOM_KILL";
                Log.e(TAG, "WebView renderer gone; recreating WebView (main=" + wasMain + ", reason=" + reasonStr + ")");
                if (wasMain) {
                    loadingStateController.resetInitialLoad();
                    // Don't immediately show the loading overlay — let
                    // onPageStarted's 200ms-delayed show handle it. If the
                    // page reloads from cache within that window (typical on
                    // resume after Android killed the renderer in the
                    // background), the overlay never shows at all and the
                    // user just sees a brief black WebView instead of the
                    // full loading-screen flash. Fixes the "brief black
                    // screen on resume from task manager" report (present
                    // since the official v6.24 release).
                    webview = mainWebViewController.recreateMainWebView(
                            webview, MainActivity.this::setupMainWebView);
                    mainWebViewController.loadUrl(webview, ChatGptSiteContract.MAIN_URL);
                } else {
                    if (popupAuthController != null) popupAuthController.removePopup(view);
                }
                return true;
            }

            @Override
            public void onPageStarted(WebView v, String url, Bitmap favicon) {
                super.onPageStarted(v, url, favicon);
                loadingStateController.onPageStarted(v);
            }

            @Override
            public void onPageFinished(WebView v, String url) {
                super.onPageFinished(v, url);
                // Page loaded successfully → mark this launch as non-crashing.
                CrashTracker.reset();
                CookieManager.getInstance().flush();
                injectAllOverrides(v);
                if (v == webview) {
                    mainWebViewController.onMainFrameLoadSettled(v);
                    if (nativeShellController != null) {
                        nativeShellController.onPageFinished(v);
                    }
                }
                transferController.kickPendingSharePipelines();
                loadingStateController.onPageFinished(v);
            }

        });
    }

    /** onPageFinished fallback (and re-injection after SPA navigations). */
    private void injectAllOverrides(WebView v) {
        v.evaluateJavascript(ChatGptSiteContract.PAGE_OVERRIDES_JS, null);
        // Ready watcher fallback for WebViews without DOCUMENT_START_SCRIPT
        // support: starts late (page already rendered) but then usually finds
        // the markers on its very first tick. MAIN WebView only — the watcher
        // must never run in OAuth/share popups.
        if (v == webview) {
            v.evaluateJavascript(ChatGptSiteContract.PAGE_READY_WATCHER_JS, null);
            v.evaluateJavascript(SiteNotificationMonitor.SCRIPT, null);
        }
    }

    /**
     * Long-press context menu for images.
     * Shows a Material AlertDialog (same style as the WebView Manager) with
     * "Share image" and "Download image" options.
     */
    private void setupImageContextMenu(WebView webView) {
        webView.setOnLongClickListener(v -> {
            WebView.HitTestResult result = webView.getHitTestResult();
            if (result != null && result.getType() == WebView.HitTestResult.IMAGE_TYPE
                    && result.getExtra() != null) {
                showImageDialog(result.getExtra());
                return true;
            }
            return false;
        });
    }

    /**
     * Show a Material AlertDialog (same style as the WebView Manager) with
     * image action options. Uses a custom layout with icon + title + subtitle
     * rows for a richer Material 3 appearance.
     */
    private void showImageDialog(final String imageUrl) {
        View view = getLayoutInflater().inflate(R.layout.dialog_image_actions, null);
        View shareBtn = view.findViewById(R.id.action_share);
        View downloadBtn = view.findViewById(R.id.action_download);

        final com.google.android.material.dialog.MaterialAlertDialogBuilder builder =
                new com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
                        .setTitle(R.string.image_actions_title)
                        .setView(view);

        final androidx.appcompat.app.AlertDialog dialog = builder.create();

        if (shareBtn != null) {
            shareBtn.setOnClickListener(v -> {
                dialog.dismiss();
                Toast.makeText(this, "Loading image to share...", Toast.LENGTH_SHORT).show();
                transferController.downloadAndShareImageFile(imageUrl);
            });
        }
        if (downloadBtn != null) {
            downloadBtn.setOnClickListener(v -> {
                dialog.dismiss();
                Toast.makeText(this, "Downloading image...", Toast.LENGTH_SHORT).show();
                transferController.downloadImageToDownloads(imageUrl);
            });
        }
        dialog.show();
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (transferController != null) {
            transferController.onActivityResult(requestCode, resultCode, data);
        }
    }

    // ─── Permission / navigation / chooser helpers ─────────────────────

    private boolean hasPermission(String perm) {
        return ContextCompat.checkSelfPermission(this, perm)
                == android.content.pm.PackageManager.PERMISSION_GRANTED;
    }

    /**
     * Handles a WebView permission request (camera/mic):
     *  - only for allowlisted origins,
     *  - requests the missing OS permission at runtime,
     *  - grants only what the user actually approved.
     */
    private void handleWebPermissionRequest(final PermissionRequest request) {
        runOnUiThread(() -> {
            if (isFinishing() || isDestroyed()) return;
            try {
                Uri origin = request.getOrigin();
                if (origin == null || !ChatGptSiteContract.isAllowedHost(origin.getHost())) {
                    try {
                        request.deny();
                    } catch (Throwable ignored) {
                    }
                    return;
                }
                List<String> toRequest = new ArrayList<>();
                for (String r : request.getResources()) {
                    if (PermissionRequest.RESOURCE_VIDEO_CAPTURE.equals(r)
                            && !hasPermission(android.Manifest.permission.CAMERA)) {
                        toRequest.add(android.Manifest.permission.CAMERA);
                    }
                    if (PermissionRequest.RESOURCE_AUDIO_CAPTURE.equals(r)
                            && !hasPermission(android.Manifest.permission.RECORD_AUDIO)) {
                        toRequest.add(android.Manifest.permission.RECORD_AUDIO);
                    }
                }
                if (toRequest.isEmpty()) {
                    grantWebPermissionRequest(request);
                } else {
                    if (pendingWebPermissionRequest != null) {
                        try {
                            pendingWebPermissionRequest.deny();
                        } catch (Throwable ignored) {
                        }
                    }
                    pendingWebPermissionRequest = request;
                    ActivityCompat.requestPermissions(MainActivity.this,
                            toRequest.toArray(new String[0]), REQUEST_MEDIA_PERM);
                }
            } catch (Throwable t) {
                Log.e(TAG, "onPermissionRequest failed", t);
            }
        });
    }

    private void grantWebPermissionRequest(PermissionRequest request) {
        List<String> granted = new ArrayList<>();
        for (String r : request.getResources()) {
            if (PermissionRequest.RESOURCE_VIDEO_CAPTURE.equals(r)
                    && hasPermission(android.Manifest.permission.CAMERA)) {
                granted.add(r);
            }
            if (PermissionRequest.RESOURCE_AUDIO_CAPTURE.equals(r)
                    && hasPermission(android.Manifest.permission.RECORD_AUDIO)) {
                granted.add(r);
            }
        }
        try {
            if (granted.isEmpty()) {
                request.deny();
            } else {
                request.grant(granted.toArray(new String[0]));
            }
        } catch (Throwable t) {
            Log.e(TAG, "grantWebPermissionRequest failed", t);
        }
    }

    /** Offline dialog with retry (main-frame load failures). */
    private void showOfflineDialog() {
        runOnUiThread(() -> {
            if (isFinishing() || isDestroyed() || offlineDialogShowing) return;
            offlineDialogShowing = true;
            loadingStateController.hideForOfflineDialog();
            new com.google.android.material.dialog.MaterialAlertDialogBuilder(MainActivity.this)
                    .setTitle("Connection problem")
                    .setMessage("Couldn't load chatgpt.com. Check your internet connection and try again.")
                    .setCancelable(false)
                    .setPositiveButton("Retry", (d, w) -> {
                        offlineDialogShowing = false;
                        loadingStateController.showForRetry();
                        mainWebViewController.loadUrl(webview, ChatGptSiteContract.MAIN_URL);
                    })
                    .setNegativeButton("Close app", (d, w) -> {
                        offlineDialogShowing = false;
                        // Actually close the app (whole task), not just the dialog.
                        finishAffinity();
                    })
                    .show();
        });
    }

    /** Delete one-shot cache files older than 48 hours. */
    private void sweepCacheDir() {
        new Thread(() -> {
            try {
                File[] files = getCacheDir().listFiles();
                if (files == null) return;
                long cutoff = System.currentTimeMillis() - 48L * 60 * 60 * 1000;
                for (File f : files) {
                    String n = f.getName();
                    if ((n.startsWith("camera_capture_") || n.startsWith("shared_image_")
                            || n.startsWith("shared_file_")) && f.lastModified() < cutoff) {
                        //noinspection ResultOfMethodCallIgnored
                        f.delete();
                    }
                }
            } catch (Throwable ignored) {
            }
        }).start();
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions,
                                           int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQUEST_NOTIFICATION_PERM) {
            if (testAfterNotificationPermission) {
                testAfterNotificationPermission = false;
                saveTestNotification();
            }
            if (nativeShellController != null) nativeShellController.refreshNotifications();
            return;
        }
        if (transferController != null
                && transferController.onRequestPermissionsResult(requestCode, grantResults)) {
            return;
        } else if (requestCode == REQUEST_MEDIA_PERM) {
            PermissionRequest req = pendingWebPermissionRequest;
            pendingWebPermissionRequest = null;
            if (req != null) {
                grantWebPermissionRequest(req);
            }
        }
    }

    @Override
    protected void onStart() {
        super.onStart();
    }

    @Override
    protected void onPause() {
        super.onPause();
        // Reaching PAUSE proves this launch was real and interactive — any
        // process death while backgrounded afterwards is NORMAL lifecycle
        // (OEM task managers kill background processes aggressively,
        // floating/picture-in-picture windows especially), not a WebView
        // crash. Reset the counter HERE (in addition to onPageFinished and
        // onDestroy) so background kills stop accumulating toward the
        // WebView-picker bounce — the "app closes itself right after I
        // open it" symptom.
        // A genuine crash-loop (broken WebView provider) still trips the
        // bounce: those launches die while foregrounded, BEFORE the first
        // pause and before the first page load.
        CrashTracker.reset();
        // Pause JS timers/layout for all our WebViews while backgrounded —
        // previously a streaming chat kept running (and draining battery) in
        // the background.
        if (mainWebViewController != null) mainWebViewController.pause(webview);
        if (popupAuthController != null) popupAuthController.pauseAll();
        CookieManager.getInstance().flush();
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);
        // Persist the WebView navigation state whenever the system asks us
        // to (configuration-driven recreation, memory-pressure activity
        // destroy, process death). onCreate's restore path uses it to
        // resume the open conversation instead of the homepage.
        if (mainWebViewController != null) {
            mainWebViewController.saveNavigationState(webview, outState);
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (mainWebViewController != null) mainWebViewController.resume(webview);
        if (popupAuthController != null) popupAuthController.resumeAll();
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (loadingStateController != null) {
            loadingStateController.onWindowFocusChanged(hasFocus);
        }
    }

    @Override
    public void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        if (loadingStateController != null) {
            loadingStateController.updateTheme(newConfig);
        }
        if (nativeShellController != null) {
            nativeShellController.onConfigurationChanged();
        }
    }

    @Override
    public void onBackPressed() {
        if (nativeShellController != null && nativeShellController.closePanelIfOpen()) {
            return;
        }
        if (popupAuthController != null && popupAuthController.closeTopPopup()) {
            return;
        }
        if (mainWebViewController == null
                || !mainWebViewController.goBackIfPossible(webview)) {
            super.onBackPressed();
        }
    }

    @Override
    protected void onDestroy() {
        // A clean destroy is not a crash — reset the counter so abandoned
        // launches (no signal, swipe-away) no longer count toward the
        // "pick another WebView" bounce.
        CrashTracker.reset();
        if (popupAuthController != null) {
            popupAuthController.destroyAll();
        }
        if (transferController != null) {
            transferController.destroy();
        }
        if (nativeShellController != null) {
            nativeShellController.destroy();
        }
        if (notificationController != null) {
            notificationController.close();
        }
        if (loadingStateController != null) {
            loadingStateController.destroy();
        }
        if (mainWebViewController != null) {
            mainWebViewController.destroyMainWebView(webview);
        } else if (webview != null) {
            try {
                webview.destroy();
            } catch (Throwable t) {
                Log.e(TAG, "fallback WebView destroy failed", t);
            }
        }
        webview = null;
        super.onDestroy();
    }


}
