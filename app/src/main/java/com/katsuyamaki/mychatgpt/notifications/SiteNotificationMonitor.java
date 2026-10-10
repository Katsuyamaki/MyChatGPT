package com.katsuyamaki.mychatgpt.notifications;

import android.content.Context;
import android.util.Log;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

/**
 * Document-start, main-frame ChatGPT popup monitor.
 *
 * The JavaScript is maintained as a standalone asset so the exact code
 * registered with WebView can be syntax-checked in CI and updated as the
 * ChatGPT frontend changes. Read once, before navigation, and reuse for
 * document-start registration and onPageFinished retry.
 */
public final class SiteNotificationMonitor {
    private static final String TAG = "SiteNotificationMonitor";
    private static final String ASSET = "site_notification_monitor.js";
    private static String cachedScript;

    private SiteNotificationMonitor() {}

    public static synchronized String script(Context context) {
        if (cachedScript != null) return cachedScript;
        try (InputStream input = context.getAssets().open(ASSET);
             ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[4096];
            int read;
            while ((read = input.read(buffer)) != -1) {
                output.write(buffer, 0, read);
                if (output.size() > 65536) {
                    throw new IOException("Notification observer asset exceeds maximum size");
                }
            }
            cachedScript = new String(output.toByteArray(), StandardCharsets.UTF_8);
            return cachedScript;
        } catch (IOException failure) {
            Log.e(TAG, "Could not load site notification observer", failure);
            return "(function(){})();";
        }
    }

    /** A visible synthetic web toast, deliberately routed through the real DOM observer. */
    public static final String PROBE_SCRIPT =
            "(function(){try{var m=window.__mychatgptSiteNotificationMonitor;"
            + "return !!(m&&m.active&&m.probe());}catch(e){return false;}})();";
    /** Tests capture of visible floating cards with NO toast/ARIA marker. */
    public static final String PROBE_UNMARKED_SCRIPT =
            "(function(){try{var m=window.__mychatgptSiteNotificationMonitor;"
            + "return !!(m&&m.active&&m.probeUnmarked());}"
            + "catch(e){return false;}})();";
}
