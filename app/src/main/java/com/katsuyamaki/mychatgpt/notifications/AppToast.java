package com.katsuyamaki.mychatgpt.notifications;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.webkit.WebView;
import android.widget.Toast;

import java.lang.ref.WeakReference;

/**
 * Retains the existing short Android Toast UX while also saving each app
 * popup to the same timestamped inbox as ChatGPT website notifications.
 *
 * The active Activity owns the NotificationController; weak references avoid
 * leaking its WebView or controller across activity/renderer recreation.
 */
public final class AppToast {
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static WeakReference<NotificationController> active =
            new WeakReference<>(null);
    private static WeakReference<WebView> mainWebView =
            new WeakReference<>(null);

    private AppToast() {}

    public static synchronized void attach(
            NotificationController controller, WebView webView) {
        active = new WeakReference<>(controller);
        mainWebView = new WeakReference<>(webView);
    }

    public static synchronized void updateWebView(WebView webView) {
        mainWebView = new WeakReference<>(webView);
    }

    public static synchronized void detach(NotificationController controller) {
        if (active.get() == controller) {
            active = new WeakReference<>(null);
            mainWebView = new WeakReference<>(null);
        }
    }

    public static Toast makeText(Context context, CharSequence text, int duration) {
        Toast toast = Toast.makeText(context, text, duration);
        if (text != null) mirror(text.toString());
        return toast;
    }

    public static Toast makeText(Context context, int resId, int duration) {
        Toast toast = Toast.makeText(context, resId, duration);
        mirror(context.getString(resId));
        return toast;
    }

    private static void mirror(String message) {
        if (message == null || message.trim().isEmpty()) return;
        Runnable record = () -> {
            NotificationController controller;
            WebView view;
            synchronized (AppToast.class) {
                controller = active.get();
                view = mainWebView.get();
            }
            if (controller == null) return;
            try {
                controller.recordAppToast(message, view == null ? null : view.getUrl());
            } catch (Exception error) {
                // A broken notification subsystem must never break the original toast.
                Log.e("MyChatGPTAppToast", "Native popup mirroring failed", error);
            }
        };
        if (Looper.myLooper() == Looper.getMainLooper()) record.run();
        else MAIN.post(record);
    }
}
