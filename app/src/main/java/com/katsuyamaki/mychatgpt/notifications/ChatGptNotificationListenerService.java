package com.katsuyamaki.mychatgpt.notifications;

import android.app.Notification;
import android.os.Bundle;
import android.service.notification.NotificationListenerService;
import android.service.notification.StatusBarNotification;
import android.util.Log;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Optional, user-consented source of real ChatGPT push notices.
 *
 * Android grants notification listeners broad system visibility. This service
 * immediately rejects every package except the *official* ChatGPT Android app,
 * does not log content, stores nothing from other apps, and sends nothing over
 * the network. It is not an independent connection to OpenAI's push backend:
 * the official app must be allowed to post its Android notifications.
 *
 * Handles posted notices while MyChatGPT is foregrounded, backgrounded, or
 * not running; Android starts/binds the listener if notification access is on.
 */
public final class ChatGptNotificationListenerService extends NotificationListenerService {
    private static final String TAG = "MyChatGPTPushMirror";
    private static final Pattern CHAT_LINK = Pattern.compile(
            "https://chatgpt\\.com/(?:g/[A-Za-z0-9-]+/)?c/[A-Za-z0-9-]{8,128}",
            Pattern.CASE_INSENSITIVE);

    private NotificationController notifications;

    @Override
    public void onCreate() {
        super.onCreate();
        notifications = new NotificationController(getApplicationContext());
    }

    @Override
    public void onListenerConnected() {
        super.onListenerConnected();
        NotificationController.setOsListenerConnected(true);
        // Android sometimes reconnects after battery management or a process
        // restart. Backfill notices still visible in the shade, without
        // re-alerting the user. Stable SQLite dedup prevents repeats.
        try {
            StatusBarNotification[] active = getActiveNotifications();
            if (active != null) {
                for (StatusBarNotification item : active) {
                    importNotification(item, true);
                }
            }
        } catch (Exception ex) {
            Log.w(TAG, "Unable to scan outstanding notifications", ex);
        }
    }

    @Override
    public void onNotificationPosted(StatusBarNotification sbn) {
        importNotification(sbn, false);
    }

    private void importNotification(StatusBarNotification sbn, boolean replay) {
        // Only official ChatGPT notifications. Do not inspect extras, titles,
        // texts, or PendingIntents of any unrelated app.
        if (sbn == null || !NotificationController.OFFICIAL_CHATGPT_PACKAGE
                .equals(sbn.getPackageName())) return;
        if (notifications == null) return;
        notifications.noteOfficialEventSeen();
        try {
            Notification n = sbn.getNotification();
            if (n == null) return;
            if ((n.flags & Notification.FLAG_GROUP_SUMMARY) != 0) return;
            if ((n.flags & Notification.FLAG_ONGOING_EVENT) != 0) return;
            Bundle extra = n.extras;
            if (extra == null) return;

            String title = toText(extra.getCharSequence(Notification.EXTRA_TITLE));
            String message = toText(extra.getCharSequence(Notification.EXTRA_BIG_TEXT));
            if (message.isEmpty()) {
                message = toText(extra.getCharSequence(Notification.EXTRA_TEXT));
            }
            if (message.isEmpty()) {
                CharSequence[] lines = extra.getCharSequenceArray(
                        Notification.EXTRA_TEXT_LINES);
                if (lines != null && lines.length > 0) {
                    message = toText(lines[lines.length - 1]);
                }
            }
            if (message.isEmpty()) {
                message = toText(extra.getCharSequence(Notification.EXTRA_SUB_TEXT));
            }
            if (message.isEmpty()) {
                message = toText(extra.getCharSequence(Notification.EXTRA_SUMMARY_TEXT));
            }
            if (title.isEmpty()) title = "ChatGPT";
            if (message.isEmpty() && "ChatGPT".equalsIgnoreCase(title)) return;

            String textAndTitle = title + " " + message;
            Matcher link = CHAT_LINK.matcher(textAndTitle);
            String destination = link.find()
                    ? NotificationController.safeChatUrl(link.group()) : null;

            // The platform PendingIntent cannot be decoded into a ChatGPT
            // conversation URL. Do not open the native app by proxy or guess
            // which chat the notice belongs to.
            String digest = sha256(sbn.getKey() + "\n" + sbn.getPostTime()
                    + "\n" + title + "\n" + message);
            notifications.recordOfficialChatGptPush(title, message, destination,
                    sbn.getPostTime(), digest, replay);
        } catch (Exception ex) {
            // Not even debug logcat should expose user's notification payload.
            Log.e(TAG, "Failed to handle official ChatGPT notice", ex);
        }
    }

    private static String toText(CharSequence text) {
        if (text == null) return "";
        String value = text.toString().replaceAll("\\s+", " ").trim();
        return value.length() <= 1200 ? value : value.substring(0, 1200);
    }

    private static String sha256(String value) throws Exception {
        byte[] bytes = MessageDigest.getInstance("SHA-256").digest(
                value.getBytes(StandardCharsets.UTF_8));
        char[] digits = "0123456789abcdef".toCharArray();
        StringBuilder out = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            out.append(digits[(b >>> 4) & 15]);
            out.append(digits[b & 15]);
        }
        return out.toString();
    }

    @Override
    public void onListenerDisconnected() {
        super.onListenerDisconnected();
        NotificationController.setOsListenerConnected(false);
    }

    @Override
    public void onDestroy() {
        NotificationController.setOsListenerConnected(false);
        if (notifications != null) {
            notifications.close();
            notifications = null;
        }
        super.onDestroy();
    }
}
