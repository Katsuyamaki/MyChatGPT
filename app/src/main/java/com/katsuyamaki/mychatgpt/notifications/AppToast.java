package com.katsuyamaki.mychatgpt.notifications;

import android.content.Context;
import android.widget.Toast;

/**
 * Displays MyChatGPT's existing, lightweight native status toasts.
 *
 * Copy, paste, download, sidebar and settings confirmations are NOT durable
 * notifications. Only an actual chat reply completion or scheduled task
 * should enter the Android notification shade and in-app history.
 */
public final class AppToast {
    private AppToast() {}

    public static Toast makeText(Context context, CharSequence text, int duration) {
        return Toast.makeText(context, text, duration);
    }

    public static Toast makeText(Context context, int resId, int duration) {
        return Toast.makeText(context, resId, duration);
    }
}
