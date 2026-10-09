package com.katsuyamaki.mychatgpt.webview;

import android.app.Activity;
import android.content.Context;
import android.util.Log;
import android.webkit.JavascriptInterface;
import android.webkit.WebView;

import com.katsuyamaki.mychatgpt.BuildConfig;
import com.katsuyamaki.mychatgpt.site.ChatGptSiteContract;

/**
 * Narrow JavaScript-to-native bridge for one WebView.
 *
 * Origin-sensitive callbacks stay gated against the site contract. Transfer
 * callbacks delegate to TransferController; page-ready delegates to the
 * Activity-owned loading state through Host.
 */
public final class WebBridgeController {

    private static final String TAG = "MyChatGPTBridge";

    public interface Host {
        WebView getMainWebView();
        void onPageReady();
    }

    private final Activity activity;
    private final WebView hostWebView;
    private final TransferController transferController;
    private final Host host;

    public WebBridgeController(Activity activity,
                               WebView hostWebView,
                               TransferController transferController,
                               Host host) {
        this.activity = activity;
        this.hostWebView = hostWebView;
        this.transferController = transferController;
        this.host = host;
    }

    /**
     * Popup blob/about:blank pages inherit trust only from the allowlisted
     * main WebView that created them, matching the inherited bridge policy.
     */
    private boolean hostAllowed() {
        try {
            if (ChatGptSiteContract.isAllowedWebUrl(hostWebView.getUrl())) {
                return true;
            }

            WebView main = host.getMainWebView();
            if (main != null
                    && main != hostWebView
                    && ChatGptSiteContract.isAllowedWebUrl(main.getUrl())) {
                return true;
            }

            debugLog("bridge blocked: " + hostWebView.getUrl());
            return false;
        } catch (Throwable t) {
            return false;
        }
    }

    @JavascriptInterface
    public void pageReady() {
        activity.runOnUiThread(() -> {
            if (!hostAllowed()) return;
            host.onPageReady();
        });
    }

    /**
     * navigator.share text fallback. The site provides its own copied UI, so
     * this intentionally writes the clipboard without an additional toast.
     */
    @JavascriptInterface
    public void copyToClipboard(final String text) {
        if (!hostAllowed()) return;
        activity.runOnUiThread(() -> {
            try {
                android.content.ClipboardManager clipboard =
                        (android.content.ClipboardManager)
                                activity.getSystemService(Context.CLIPBOARD_SERVICE);
                if (clipboard != null) {
                    android.content.ClipData clip =
                            android.content.ClipData.newPlainText("MyChatGPT", text);
                    clipboard.setPrimaryClip(clip);
                    Log.i(TAG, "Text copied to clipboard via JS override");
                }
            } catch (Exception e) {
                Log.e(TAG, "copyToClipboard (JS) failed", e);
            }
        });
    }

    /**
     * Intentionally ungated: the system share sheet is the user-consent UI,
     * and the ChatGPT share popup can itself be blob:/about:blank.
     */
    @JavascriptInterface
    public void shareText(final String text, final String url) {
        debugLog("shareText invoked");
        activity.runOnUiThread(
                () -> transferController.shareTextNative(text, url));
    }

    /** Same consent model as shareText for file shares. */
    @JavascriptInterface
    public void shareFile(final String title,
                          final String dataUrl,
                          final String fileName,
                          final String mime) {
        debugLog("shareFile invoked: " + fileName);
        activity.runOnUiThread(
                () -> transferController.shareFileNative(
                        title, dataUrl, fileName, mime));
    }

    @JavascriptInterface
    public void onBlobResult(final String dataUrl) {
        if (!hostAllowed()) return;
        activity.runOnUiThread(
                () -> transferController.handleBlobResult(dataUrl));
    }

    /** Preserves inherited ungated failure callback behavior. */
    @JavascriptInterface
    public void onBlobFailed() {
        activity.runOnUiThread(transferController::handleBlobFailed);
    }

    @JavascriptInterface
    public void onBlobDownload(final String name, final String dataUrl) {
        if (!hostAllowed()) return;
        activity.runOnUiThread(
                () -> transferController.handleBlobDownload(name, dataUrl));
    }

    /**
     * Synchronous bridge callback; TransferController owns ordered chunk
     * accumulation and final save.
     */
    @JavascriptInterface
    public void onBlobChunk(final String name,
                            final String mime,
                            final int index,
                            final int total,
                            final String data) {
        transferController.onBlobChunk(name, mime, index, total, data);
    }

    @JavascriptInterface
    public void debugLog(final String msg) {
        if (BuildConfig.EXPERIMENTAL) {
            Log.d(TAG, "bridge: " + msg);
        }
    }

    @JavascriptInterface
    public void onFileDropResult(final boolean ok, final String detail) {
        activity.runOnUiThread(
                () -> transferController.handleFileDropResult(ok, detail));
    }
}
