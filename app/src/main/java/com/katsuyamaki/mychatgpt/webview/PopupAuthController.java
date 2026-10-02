package com.katsuyamaki.mychatgpt.webview;

import android.app.Activity;
import android.net.Uri;
import android.os.Build;
import android.os.Message;
import android.util.Log;
import android.view.ViewGroup;
import android.webkit.ConsoleMessage;
import android.webkit.CookieManager;
import android.webkit.PermissionRequest;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import androidx.webkit.WebViewCompat;

import com.katsuyamaki.mychatgpt.BuildConfig;
import com.katsuyamaki.mychatgpt.site.ChatGptSiteContract;

import java.util.ArrayList;
import java.util.List;

/**
 * Owns popup WebViews used for OAuth and site-created secondary windows.
 *
 * The Activity still owns Android permission/file-chooser decisions and the
 * JavaScript bridge implementation. This controller owns popup lifetime,
 * popup settings/navigation, OAuth return-to-main routing and safe teardown.
 */
public final class PopupAuthController {

    private static final String TAG = "MyChatGPTPopup";

    public interface Host {
        WebView getMainWebView();
        int getWebViewBackgroundColor();
        Object createJavascriptBridge(WebView popup);
        void injectPageOverrides(WebView popup);
        void handleWebPermissionRequest(PermissionRequest request);
        boolean openFileChooser(ValueCallback<Uri[]> callback);
    }

    private final Activity activity;
    private final ViewGroup rootLayout;
    private final MainWebViewController mainWebViewController;
    private final Host host;
    private final List<WebView> popupViews = new ArrayList<>();

    public PopupAuthController(Activity activity,
                               ViewGroup rootLayout,
                               MainWebViewController mainWebViewController,
                               Host host) {
        this.activity = activity;
        this.rootLayout = rootLayout;
        this.mainWebViewController = mainWebViewController;
        this.host = host;
    }

    public boolean createPopup(Message resultMsg) {
        final WebView popup = new WebView(activity);
        popup.setLayoutParams(new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT));
        popup.setBackgroundColor(host.getWebViewBackgroundColor());

        WebSettings ps = popup.getSettings();
        ps.setJavaScriptEnabled(true);
        ps.setDomStorageEnabled(true);
        ps.setDatabaseEnabled(true);
        ps.setSupportMultipleWindows(true);
        ps.setJavaScriptCanOpenWindowsAutomatically(true);

        WebView main = host.getMainWebView();
        if (main != null) {
            ps.setUserAgentString(main.getSettings().getUserAgentString());
        }

        ps.setCacheMode(WebSettings.LOAD_DEFAULT);
        ps.setAllowFileAccess(false);
        ps.setAllowContentAccess(false);

        CookieManager.getInstance().setAcceptThirdPartyCookies(popup, true);
        popup.addJavascriptInterface(host.createJavascriptBridge(popup), "AndroidBridge");

        try {
            if (WebViewUtil.isSupported()) {
                WebViewCompat.addDocumentStartJavaScript(
                        popup,
                        ChatGptSiteContract.PAGE_OVERRIDES_JS,
                        java.util.Collections.singleton("*"));
            }
        } catch (Throwable t) {
            Log.e(TAG, "popup addDocumentStartJavaScript failed", t);
        }

        popup.setWebViewClient(new WebViewClient() {
            @SuppressWarnings("deprecation")
            @Override
            public boolean shouldOverrideUrlLoading(WebView v, String url) {
                boolean result = mainWebViewController.shouldOverrideFrame(url);
                if (result) maybeRemovePopupForExternalLink(popup, url);
                return result;
            }

            @Override
            public boolean shouldOverrideUrlLoading(
                    WebView v, android.webkit.WebResourceRequest request) {
                String url = request.getUrl().toString();
                boolean isMainFrame = (Build.VERSION.SDK_INT < Build.VERSION_CODES.N)
                        || request.isForMainFrame();
                boolean result = isMainFrame
                        ? mainWebViewController.shouldOverrideMainFrame(url)
                        : mainWebViewController.shouldOverrideFrame(url);
                if (result && isMainFrame) {
                    maybeRemovePopupForExternalLink(popup, url);
                }
                return result;
            }

            @Override
            public boolean onRenderProcessGone(
                    WebView view,
                    android.webkit.RenderProcessGoneDetail detail) {
                removePopup(popup, false);
                return true;
            }

            @Override
            public void onPageFinished(WebView v, String url) {
                super.onPageFinished(v, url);
                CookieManager.getInstance().flush();
                host.injectPageOverrides(v);

                try {
                    Uri uri = Uri.parse(url);
                    String popupHost = uri.getHost();
                    if (popupHost != null
                            && ChatGptSiteContract.isChatGptHost(popupHost)
                            && !url.contains("/auth/")) {
                        WebView currentMain = host.getMainWebView();
                        if (currentMain != null) {
                            mainWebViewController.loadUrl(currentMain, url);
                        }
                        removePopup(popup, false);
                    }
                } catch (Throwable t) {
                    Log.e(TAG, "popup completion routing failed", t);
                }
            }
        });

        popup.setWebChromeClient(new WebChromeClient() {
            @Override
            public void onCloseWindow(WebView window) {
                removePopup(popup, false);
            }

            @Override
            public boolean onConsoleMessage(ConsoleMessage cm) {
                if (BuildConfig.EXPERIMENTAL) {
                    Log.d(TAG, "[popup] " + cm.message());
                }
                return true;
            }

            @Override
            public void onPermissionRequest(final PermissionRequest request) {
                host.handleWebPermissionRequest(request);
            }

            @Override
            public boolean onShowFileChooser(
                    WebView w,
                    ValueCallback<Uri[]> callback,
                    FileChooserParams fileChooserParams) {
                return host.openFileChooser(callback);
            }
        });

        rootLayout.addView(popup);
        popupViews.add(popup);

        WebView.WebViewTransport transport = (WebView.WebViewTransport) resultMsg.obj;
        transport.setWebView(popup);
        resultMsg.sendToTarget();
        return true;
    }

    public boolean closeTopPopup() {
        if (popupViews.isEmpty()) return false;
        WebView top = popupViews.get(popupViews.size() - 1);
        removePopup(top, false);
        return true;
    }

    public void pauseAll() {
        for (WebView popup : new ArrayList<>(popupViews)) {
            try {
                popup.onPause();
            } catch (Throwable ignored) {
            }
        }
    }

    public void resumeAll() {
        for (WebView popup : new ArrayList<>(popupViews)) {
            try {
                popup.onResume();
            } catch (Throwable ignored) {
            }
        }
    }

    public void destroyAll() {
        for (WebView popup : new ArrayList<>(popupViews)) {
            removePopup(popup, true);
        }
    }

    private void maybeRemovePopupForExternalLink(WebView popup, String url) {
        if (url == null) return;
        try {
            Uri uri = Uri.parse(url);
            String popupHost = uri.getHost();
            if (popupHost != null && !ChatGptSiteContract.isAllowedHost(popupHost)) {
                removePopup(popup, false);
            }
        } catch (Exception ignored) {
        }
    }

    private void removePopup(WebView popup, boolean immediate) {
        try {
            rootLayout.removeView(popup);
            popupViews.remove(popup);
            if (immediate) {
                popup.destroy();
            } else {
                rootLayout.post(() -> {
                    try {
                        popup.destroy();
                    } catch (Throwable t) {
                        Log.e(TAG, "deferred popup destroy failed", t);
                    }
                });
            }
        } catch (Exception e) {
            Log.e(TAG, "Error removing popup", e);
        }
    }
}
