package com.katsuyamaki.mychatgpt.webview;

import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.os.SystemClock;
import android.util.Log;
import android.webkit.WebView;
import android.widget.Toast;

import com.katsuyamaki.mychatgpt.site.ChatGptSiteContract;

import org.json.JSONTokener;

/**
 * Diagnostic alternative to Android/WebView's normal clipboard paste path.
 *
 * Reads plain clipboard text natively, sends it to the existing ChatGPT
 * composer in bounded JavaScript chunks, then performs one editor insertion.
 * Clipboard contents are never written to logcat; only lengths/timings are.
 */
public final class ClipboardPasteController {

    private static final String TAG = "MyChatGPTPaste";
    private static final int CHUNK_CHARS = 32768;
    private static final int MAX_CHARS = 1_000_000;

    public interface Host {
        WebView getMainWebView();
    }

    private final Activity activity;
    private final Host host;
    private final TransferController transferController;

    public ClipboardPasteController(
            Activity activity,
            Host host,
            TransferController transferController) {
        this.activity = activity;
        this.host = host;
        this.transferController = transferController;
    }

    public void pastePrimaryClipboard() {
        final WebView webView = host.getMainWebView();
        if (webView == null || activity.isFinishing()) {
            toast("Fast Paste: WebView unavailable");
            return;
        }

        if (!ChatGptSiteContract.isChatGptWebUrl(webView.getUrl())) {
            toast("Fast Paste: open a ChatGPT chat first");
            return;
        }

        ClipboardPayload payload = readPrimaryClipboard("Fast Paste");
        if (payload == null) return;

        final String text = payload.text;
        final long start = SystemClock.elapsedRealtime();
        Log.i(TAG, "start chars=" + text.length()
                + " readMs=" + payload.readMs
                + " chunks=" + ((text.length() + CHUNK_CHARS - 1) / CHUNK_CHARS));

        webView.evaluateJavascript(
                ChatGptSiteContract.FAST_PASTE_RESET_JS,
                ignored -> appendNext(webView, text, 0, start));
    }

    public void attachPrimaryClipboardAsText() {
        final WebView webView = host.getMainWebView();
        if (webView == null || activity.isFinishing()) {
            toast("Clipboard TXT: WebView unavailable");
            return;
        }

        if (!ChatGptSiteContract.isChatGptWebUrl(webView.getUrl())) {
            toast("Clipboard TXT: open a ChatGPT chat first");
            return;
        }

        ClipboardPayload payload = readPrimaryClipboard("Clipboard TXT");
        if (payload == null) return;

        Log.i(TAG, "attach-txt start chars=" + payload.text.length()
                + " readMs=" + payload.readMs);
        transferController.attachClipboardText(payload.text);
    }

    private ClipboardPayload readPrimaryClipboard(String label) {
        final long readStart = SystemClock.elapsedRealtime();
        final String text;
        try {
            ClipboardManager clipboard = (ClipboardManager)
                    activity.getSystemService(Context.CLIPBOARD_SERVICE);
            if (clipboard == null || !clipboard.hasPrimaryClip()) {
                toast(label + ": clipboard is empty");
                return null;
            }

            ClipData clip = clipboard.getPrimaryClip();
            if (clip == null || clip.getItemCount() == 0) {
                toast(label + ": clipboard is empty");
                return null;
            }

            CharSequence value = clip.getItemAt(0).getText();
            if (value == null) {
                value = clip.getItemAt(0).coerceToText(activity);
            }
            text = value == null ? "" : value.toString();
        } catch (Throwable t) {
            Log.e(TAG, "clipboard read failed", t);
            toast(label + ": clipboard read failed");
            return null;
        }

        long readMs = SystemClock.elapsedRealtime() - readStart;
        if (text.isEmpty()) {
            toast(label + ": clipboard has no text");
            return null;
        }
        if (text.length() > MAX_CHARS) {
            toast(label + ": clipboard exceeds "
                    + MAX_CHARS + " characters");
            Log.w(TAG, "refused chars=" + text.length()
                    + " readMs=" + readMs);
            return null;
        }

        return new ClipboardPayload(text, readMs);
    }

    private static final class ClipboardPayload {
        final String text;
        final long readMs;

        ClipboardPayload(String text, long readMs) {
            this.text = text;
            this.readMs = readMs;
        }
    }

    private void appendNext(
            WebView webView,
            String text,
            int offset,
            long startMs) {
        if (activity.isFinishing() || webView != host.getMainWebView()) {
            Log.w(TAG, "aborted: WebView changed");
            return;
        }

        if (offset >= text.length()) {
            commit(webView, text.length(), startMs);
            return;
        }

        int end = Math.min(text.length(), offset + CHUNK_CHARS);
        String chunk = text.substring(offset, end);
        webView.evaluateJavascript(
                ChatGptSiteContract.buildFastPasteAppendJs(chunk),
                ignored -> appendNext(webView, text, end, startMs));
    }

    private void commit(WebView webView, int chars, long startMs) {
        webView.evaluateJavascript(
                ChatGptSiteContract.FAST_PASTE_COMMIT_JS,
                result -> {
                    long totalMs = SystemClock.elapsedRealtime() - startMs;
                    String decoded = decodeResult(result);
                    Log.i(TAG, "complete chars=" + chars
                            + " totalMs=" + totalMs
                            + " result=" + decoded);

                    if (decoded.startsWith("ok|")) {
                        String[] parts = decoded.split("\\|", -1);
                        String jsMs = parts.length > 2 ? parts[2] : "?";
                        String mode = parts.length > 3 ? parts[3] : "?";
                        toast("Fast Paste: " + chars + " chars, "
                                + totalMs + " ms total (JS "
                                + jsMs + " ms, " + mode + ")");
                    } else {
                        toast("Fast Paste failed: " + decoded);
                    }
                });
    }

    private static String decodeResult(String raw) {
        if (raw == null) return "null";
        try {
            Object value = new JSONTokener(raw).nextValue();
            return value == null ? "null" : String.valueOf(value);
        } catch (Throwable ignored) {
            return raw;
        }
    }

    private void toast(String message) {
        activity.runOnUiThread(() ->
                Toast.makeText(activity, message, Toast.LENGTH_LONG).show());
    }
}
