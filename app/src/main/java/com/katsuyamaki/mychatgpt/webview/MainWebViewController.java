package com.katsuyamaki.mychatgpt.webview;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.util.Log;
import android.view.ViewGroup;
import android.webkit.CookieManager;
import android.webkit.WebBackForwardList;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.widget.Toast;

import com.katsuyamaki.mychatgpt.BuildConfig;

import java.util.Locale;
import java.util.Map;

/**
 * Owns generic lifecycle and navigation mechanics for the main WebView.
 *
 * ChatGPT-specific hosts, user-agent values and request headers are injected
 * by the Activity/site contract. File transfer, popup auth, loading UI and DOM
 * integration deliberately remain outside this controller.
 */
public final class MainWebViewController {

    private static final String TAG = "MyChatGPTWebView";

    public interface HostPolicy {
        boolean isAllowedHost(String host);
    }

    public interface WebViewInitializer {
        void initialize(WebView webView);
    }

    private final Activity activity;
    private final ViewGroup rootLayout;
    private final Map<String, String> requestHeaders;
    private final String userAgent;
    private final HostPolicy hostPolicy;
    private WebView forceReloadTarget;

    public MainWebViewController(Activity activity,
                                 ViewGroup rootLayout,
                                 Map<String, String> requestHeaders,
                                 String userAgent,
                                 HostPolicy hostPolicy) {
        this.activity = activity;
        this.rootLayout = rootLayout;
        this.requestHeaders = requestHeaders;
        this.userAgent = userAgent;
        this.hostPolicy = hostPolicy;
    }

    /**
     * Apply generic main-WebView settings. Site-specific values are supplied
     * by the caller rather than owned here.
     */
    public void configureMainWebView(WebView webView,
                                     int backgroundColor,
                                     Object javaScriptBridge,
                                     String bridgeName) {
        WebSettings settings = webView.getSettings();

        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setDatabaseEnabled(true);
        settings.setBlockNetworkImage(false);

        settings.setAllowFileAccess(false);
        settings.setAllowContentAccess(false);
        settings.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            settings.setSafeBrowsingEnabled(true);
        }

        settings.setCacheMode(WebSettings.LOAD_DEFAULT);
        settings.setBlockNetworkLoads(false);
        settings.setMediaPlaybackRequiresUserGesture(false);

        settings.setSupportMultipleWindows(true);
        settings.setJavaScriptCanOpenWindowsAutomatically(true);
        settings.setUserAgentString(userAgent);

        settings.setUseWideViewPort(true);
        settings.setLoadWithOverviewMode(true);
        settings.setBuiltInZoomControls(true);
        settings.setSupportZoom(true);
        settings.setDisplayZoomControls(false);

        webView.requestFocusFromTouch();
        webView.setBackgroundColor(backgroundColor);

        // Main page blocks third-party cookies. OAuth popups deliberately
        // enable them separately in MainActivity.
        CookieManager.getInstance().setAcceptThirdPartyCookies(webView, false);

        if (BuildConfig.EXPERIMENTAL
                && Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT) {
            WebView.setWebContentsDebuggingEnabled(true);
        }

        webView.addJavascriptInterface(javaScriptBridge, bridgeName);
    }

    public void loadUrl(WebView webView, String url) {
        webView.loadUrl(url, requestHeaders);
    }

    /**
     * Strong user-requested refresh for stale SPA state. The caller supplies
     * an already-validated ChatGPT URL. Use LOAD_NO_CACHE for this navigation,
     * then restore the normal inherited cache policy on completion or timeout.
     */
    public void forceReloadCurrent(WebView webView, String url) {
        if (webView == null || url == null) return;
        try {
            webView.stopLoading();
            webView.getSettings().setCacheMode(WebSettings.LOAD_NO_CACHE);
            forceReloadTarget = webView;
            loadUrl(webView, url);
            webView.postDelayed(() -> restoreDefaultCachePolicy(webView), 15000L);
        } catch (Throwable t) {
            Log.e(TAG, "forceReloadCurrent failed", t);
            restoreDefaultCachePolicy(webView);
        }
    }

    /** Call when a main-frame load finishes or fails. */
    public void onMainFrameLoadSettled(WebView webView) {
        if (forceReloadTarget == webView) {
            restoreDefaultCachePolicy(webView);
        }
    }

    private void restoreDefaultCachePolicy(WebView webView) {
        if (webView == null) return;
        try {
            if (forceReloadTarget == webView) {
                webView.getSettings().setCacheMode(WebSettings.LOAD_DEFAULT);
                forceReloadTarget = null;
            }
        } catch (Throwable t) {
            Log.e(TAG, "restore cache policy failed", t);
            if (forceReloadTarget == webView) forceReloadTarget = null;
        }
    }

    /**
     * Restore back/forward state and explicitly reload the current http(s)
     * URL with the request-header policy, matching the inherited behavior.
     */
    public boolean restoreNavigationState(WebView webView, Bundle savedInstanceState) {
        if (savedInstanceState == null) return false;
        try {
            WebBackForwardList nav = webView.restoreState(savedInstanceState);
            if (nav != null && nav.getSize() > 0
                    && nav.getCurrentItem() != null
                    && nav.getCurrentItem().getUrl() != null) {
                String currentUrl = nav.getCurrentItem().getUrl();
                if (currentUrl.startsWith("http://") || currentUrl.startsWith("https://")) {
                    loadUrl(webView, currentUrl);
                }
                return true;
            }
        } catch (Throwable t) {
            Log.e(TAG, "WebView state restore failed", t);
        }
        return false;
    }

    public void saveNavigationState(WebView webView, Bundle outState) {
        if (webView == null || outState == null) return;
        try {
            webView.saveState(outState);
        } catch (Throwable t) {
            Log.e(TAG, "WebView saveState failed", t);
        }
    }

    /**
     * Renderer-recovery path: detach immediately, destroy after the current
     * WebView callback unwinds, then create and initialize a fresh main view.
     */
    public WebView recreateMainWebView(WebView dead, WebViewInitializer initializer) {
        try {
            rootLayout.removeView(dead);
            rootLayout.post(() -> {
                try {
                    dead.destroy();
                } catch (Throwable t) {
                    Log.e(TAG, "deferred main WebView destroy failed", t);
                }
            });
        } catch (Throwable t) {
            Log.e(TAG, "Error detaching dead WebView", t);
        }

        WebView fresh = new WebView(activity);
        fresh.setLayoutParams(new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT));
        rootLayout.addView(fresh, 0);
        initializer.initialize(fresh);
        return fresh;
    }

    /**
     * Main-frame navigation policy. Returns true when the WebView should not
     * continue the navigation because it was blocked or sent externally.
     */
    public boolean shouldOverrideMainFrame(String url) {
        if (url == null) return false;

        Uri uri;
        try {
            uri = Uri.parse(url);
        } catch (Exception e) {
            return false;
        }

        String scheme = uri.getScheme();
        boolean isWeb = "http".equalsIgnoreCase(scheme)
                || "https".equalsIgnoreCase(scheme);

        if (isWeb) {
            String host = uri.getHost();
            if (host != null && !hostPolicy.isAllowedHost(host)) {
                openUrlInBrowser(url);
                return true;
            }
            return false;
        }

        String normalized = scheme == null ? "" : scheme.toLowerCase(Locale.ROOT);
        if (normalized.equals("mailto") || normalized.equals("tel")
                || normalized.equals("sms") || normalized.equals("geo")
                || normalized.equals("intent") || normalized.equals("market")) {
            openUrlInBrowser(url);
            return true;
        }

        if (normalized.equals("file") || normalized.equals("content")) {
            return true;
        }

        // blob:, data:, about:, javascript:, schemeless -> load in WebView.
        return false;
    }

    /**
     * Subframe policy preserves the inherited permissive null-host behavior
     * required by ChatGPT iframe apps, while external web hosts are routed out.
     */
    public boolean shouldOverrideFrame(String url) {
        if (url == null) return false;
        Uri uri;
        try {
            uri = Uri.parse(url);
        } catch (Exception e) {
            return false;
        }
        String host = uri.getHost();
        if (host != null && !hostPolicy.isAllowedHost(host)) {
            openUrlInBrowser(url);
            return true;
        }
        return false;
    }

    public void pause(WebView webView) {
        if (webView != null) webView.onPause();
    }

    public void resume(WebView webView) {
        if (webView != null) webView.onResume();
    }

    public boolean goBackIfPossible(WebView webView) {
        if (webView != null && webView.canGoBack()) {
            webView.goBack();
            return true;
        }
        return false;
    }

    public void destroyMainWebView(WebView webView) {
        if (webView == null) return;
        if (forceReloadTarget == webView) forceReloadTarget = null;
        try {
            rootLayout.removeView(webView);
            webView.removeAllViews();
            webView.destroy();
        } catch (Exception e) {
            Log.e(TAG, "Error destroying WebView", e);
        }
    }

    private void openUrlInBrowser(String url) {
        try {
            Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse(url));
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            activity.startActivity(intent);
        } catch (Exception e) {
            Log.e(TAG, "openUrlInBrowser failed", e);
            com.katsuyamaki.mychatgpt.notifications.AppToast.makeText(activity, "Cannot open URL", Toast.LENGTH_SHORT).show();
        }
    }
}
