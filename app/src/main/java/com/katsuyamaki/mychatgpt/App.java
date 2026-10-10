package com.katsuyamaki.mychatgpt;

import android.app.Application;
import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;
import android.widget.Toast;

import com.katsuyamaki.mychatgpt.webview.Hooker;
import com.katsuyamaki.mychatgpt.webview.WebViewUtil;

/**
 * Application subclass.
 *
 * The WebView switcher MUST be initialized here in onCreate(), before any Activity
 * has a chance to construct a WebView. Android's WebViewFactory loads the WebView
 * implementation lazily on the first `new WebView(ctx)` call; once that happens
 * the chosen provider is locked in for the process and cannot be changed without
 * killing the process.
 *
 * Boot order:
 *   1. WebViewUtil.init(ctx)        — captures default provider, reads user choice,
 *                                      installs ServiceManager hook if a custom
 *                                      provider is selected
 *   2. Hooker.hookPackageManager()  — always installed; lies about hasSystemFeature
 *   3. Hooker.hookInstallContentProviders()  — only if "webview_optimize" is on;
 *                                      installs a fake DeveloperModeContentProvider
 *                                      so the chosen WebView enables GPU blocklist
 *                                      bypass + surface control
 *   4. CrashTracker is initialized by MainActivity, not here, because
 *      a background OS notification-listener service may start this process
 *      without launching a WebView and must never count as a WebView crash.
 */
public class App extends Application {

    private static final String TAG = "MyChatGPTApp";
    public static final String PREFS_NAME = "webgpt_prefs";

    @Override
    public void onCreate() {
        super.onCreate();

        SharedPreferences prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);

        // 1. Initialize the WebView provider registry. On API < 26 this is a no-op
        //    because WebView.getCurrentWebViewPackage() doesn't exist.
        try {
            WebViewUtil.init(getApplicationContext());
        } catch (Throwable t) {
            Log.e(TAG, "WebViewUtil.init failed", t);
        }

        // 2. Always install the PackageManager proxy hook.
        try {
            Hooker.hookPackageManager(getApplicationContext());
        } catch (Throwable t) {
            Log.e(TAG, "hookPackageManager failed", t);
            com.katsuyamaki.mychatgpt.notifications.AppToast.makeText(this, "WebView hook failed: " + t.getMessage(), Toast.LENGTH_LONG).show();
        }

        // 3. Optionally install the DeveloperModeContentProvider hook.
        if (prefs.getBoolean("webview_optimize", false)) {
            try {
                Hooker.hookInstallContentProviders(getApplicationContext());
            } catch (Throwable t) {
                Log.e(TAG, "hookInstallContentProviders failed", t);
            }
        }

        // CrashTracker is intentionally NOT started from Application.onCreate:
        // Android also invokes this for background push-listener service starts.
    }
}
