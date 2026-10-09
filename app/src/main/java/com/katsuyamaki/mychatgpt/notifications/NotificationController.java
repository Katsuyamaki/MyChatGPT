package com.katsuyamaki.mychatgpt.notifications;

import android.Manifest;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.SystemClock;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.util.Log;
import com.katsuyamaki.mychatgpt.MainActivity;
import com.katsuyamaki.mychatgpt.R;
import java.text.DateFormat;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.lang.ref.WeakReference;
import java.util.regex.Pattern;

/** Mirrors site notices to the Android shade and a durable local inbox. */
public final class NotificationController {
    private static final String TAG = "MyChatGPTNotifications";
    private static final String CHANNEL = "chat_updates_v1";
    private static final String PREFS = "mychatgpt_notification_prefs";
    private static final String KEY_ENABLED = "android_enabled";
    private static final String KEY_OS_SEEN_AT = "chatgpt_os_seen_at";
    private static final String KEY_OS_SAVED_AT = "chatgpt_os_saved_at";
    public static final String OFFICIAL_CHATGPT_PACKAGE = "com.openai.chatgpt";
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static WeakReference<Runnable> uiHistoryListener = new WeakReference<>(null);
    private static volatile boolean osListenerConnected;
    public static final String ACTION_OPEN = "com.katsuyamaki.mychatgpt.OPEN_NOTIFICATION";
    public static final String EXTRA_ID = "notification_id";
    private static final Pattern CHAT_PATH =
            Pattern.compile("(?:^|/)c/[A-Za-z0-9-]{8,128}(?:/|$)");
    private final Context context;
    private final NotificationManager manager;
    private final NotificationStore store;
    private final SharedPreferences prefs;
    private final Map<String, Long> recentKeys = new LinkedHashMap<>();
    private boolean siteMonitorActive;
    private int siteCapturesThisSession;
    private int siteProbeCapturesThisSession;
    private long latestSiteCaptureAt;

    public NotificationController(Context context) {
        this.context = context.getApplicationContext();
        manager = (NotificationManager) this.context.getSystemService(Context.NOTIFICATION_SERVICE);
        store = new NotificationStore(this.context);
        prefs = this.context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        if (manager != null && Build.VERSION.SDK_INT >= 26) {
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL, "ChatGPT updates", NotificationManager.IMPORTANCE_DEFAULT);
            channel.setDescription("ChatGPT notices saved in MyChatGPT");
            manager.createNotificationChannel(channel);
        }
    }

    /** UI only: no Activity is retained by a notification listener service. */
    public static synchronized void observeHistory(Runnable observer) {
        uiHistoryListener = new WeakReference<>(observer);
    }

    public static void notifyHistoryChanged() {
        Runnable observer;
        synchronized (NotificationController.class) {
            observer = uiHistoryListener.get();
        }
        if (observer == null) return;
        if (Looper.myLooper() == Looper.getMainLooper()) observer.run();
        else MAIN.post(observer);
    }

    public static void setOsListenerConnected(boolean connected) {
        osListenerConnected = connected;
        notifyHistoryChanged();
    }

    public boolean isOsListenerConnected() {
        return osListenerConnected;
    }

    /** User-granted notification access, separate from POST_NOTIFICATIONS. */
    public boolean hasOfficialPushMirrorAccess() {
        try {
            if (manager != null && Build.VERSION.SDK_INT >= 27) {
                return manager.isNotificationListenerAccessGranted(new ComponentName(
                        context, ChatGptNotificationListenerService.class));
            }
            String enabled = Settings.Secure.getString(context.getContentResolver(),
                    "enabled_notification_listeners");
            return enabled != null && enabled.contains(context.getPackageName() + "/");
        } catch (Exception e) {
            Log.w(TAG, "Could not query Android notification-listener access", e);
            return false;
        }
    }

    public long latestOfficialSeenAt() {
        return prefs.getLong(KEY_OS_SEEN_AT, 0L);
    }

    public long latestOfficialSavedAt() {
        return prefs.getLong(KEY_OS_SAVED_AT, 0L);
    }

    /** This is a diagnostic timestamp only, never the notification text. */
    public void noteOfficialEventSeen() {
        prefs.edit().putLong(KEY_OS_SEEN_AT, System.currentTimeMillis()).apply();
        notifyHistoryChanged();
    }

    public boolean isAndroidEnabled() { return prefs.getBoolean(KEY_ENABLED, true); }
    public void setAndroidEnabled(boolean enabled) {
        prefs.edit().putBoolean(KEY_ENABLED, enabled).apply();
    }
    public boolean needsPermission() {
        return Build.VERSION.SDK_INT >= 33
                && context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED;
    }
    public boolean canPostAndroid() {
        if (!isAndroidEnabled() || needsPermission() || manager == null) return false;
        if (Build.VERSION.SDK_INT >= 24 && !manager.areNotificationsEnabled()) return false;
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationChannel channel = manager.getNotificationChannel(CHANNEL);
            return channel != null && channel.getImportance() != NotificationManager.IMPORTANCE_NONE;
        }
        return true;
    }

    /** Observer confirms that it was installed in the active ChatGPT page. */
    public void siteMonitorReady() {
        siteMonitorActive = true;
    }

    public void resetSiteMonitor() {
        siteMonitorActive = false;
    }

    public boolean isSiteMonitorActive() {
        return siteMonitorActive;
    }

    public int siteCapturesThisSession() {
        return siteCapturesThisSession;
    }

    public int siteProbeCapturesThisSession() {
        return siteProbeCapturesThisSession;
    }

    public long latestSiteCaptureAt() {
        return latestSiteCaptureAt;
    }

    public long recordSiteNotification(String text, String candidateUrl) {
        String body = clean(text);
        if (body.isEmpty()) return 0;
        String chatUrl = safeChatUrl(candidateUrl);
        long now = SystemClock.elapsedRealtime();
        String key = body + "|" + chatUrl;
        synchronized (recentKeys) {
            Long prev = recentKeys.get(key);
            if (prev != null && now >= prev && now - prev < 10000) return 0;
            recentKeys.put(key, now);
            if (recentKeys.size() > 40) {
                recentKeys.remove(recentKeys.keySet().iterator().next());
            }
        }
        boolean probe = body.startsWith("MyChatGPT site capture test:");
        long id = record(probe ? "MyChatGPT capture test" : "ChatGPT update",
                body, chatUrl, probe ? "site-test" : "site");
        if (id > 0) {
            if (probe) siteProbeCapturesThisSession++;
            else siteCapturesThisSession++;
            latestSiteCaptureAt = System.currentTimeMillis();
        }
        return id;
    }

    /** Existing native Android Toasts also become durable, timestamped entries. */
    public long recordAppToast(String text, String currentUrl) {
        String body = clean(text);
        if (body.isEmpty()) return 0;
        String chatUrl = safeChatUrl(currentUrl);
        long now = SystemClock.elapsedRealtime();
        String key = "native|" + body + "|" + chatUrl;
        synchronized (recentKeys) {
            Long previous = recentKeys.get(key);
            if (previous != null && now >= previous && now - previous < 10000) {
                return 0;
            }
            recentKeys.put(key, now);
            if (recentKeys.size() > 40) {
                recentKeys.remove(recentKeys.keySet().iterator().next());
            }
        }
        return record("MyChatGPT popup", body, chatUrl, "app-toast");
    }

    /**
     * Called only after the OS notification-listener service validates the
     * source package. Android notifications can be posted while WebView is
     * stopped. The digest uniquely identifies that OS posting/content;
     * SQLite enforces dedup even across service/Activity restarts.
     */
    public long recordOfficialChatGptPush(String title, String text,
                                          String candidateUrl, long postedAt,
                                          String externalDigest, boolean replay) {
        String cleanTitle = clean(title);
        String cleanBody = clean(text);
        if (cleanBody.isEmpty() && (cleanTitle.isEmpty()
                || "ChatGPT".equalsIgnoreCase(cleanTitle))) return 0;
        if (cleanTitle.isEmpty()) cleanTitle = "ChatGPT";
        if (cleanBody.isEmpty()) cleanBody = cleanTitle;
        if (externalDigest == null || externalDigest.length() != 64) return 0;
        String url = safeChatUrl(candidateUrl);
        long when = postedAt > 0 ? postedAt : System.currentTimeMillis();
        try {
            long id = store.addExternal(cleanTitle, cleanBody, url,
                    "official-chatgpt", when, externalDigest);
            if (id <= 0) return 0;
            // Reconnected listeners archive outstanding notifications silently:
            // only freshly posted events create another Android shade entry.
            if (!replay && canPostAndroid()) {
                postAndroid(id, "ChatGPT · " + cleanTitle, cleanBody, when);
            }
            prefs.edit().putLong(KEY_OS_SAVED_AT, System.currentTimeMillis()).apply();
            notifyHistoryChanged();
            return id;
        } catch (Exception ex) {
            // Never include private notification text in logs.
            Log.e(TAG, "Official ChatGPT notification mirror failed", ex);
            return 0;
        }
    }

    public long recordTestNotification(String chatUrl) {
        return record("MyChatGPT test", "Android notification and history are working.",
                safeChatUrl(chatUrl), "test");
    }
    private long record(String title, String body, String chatUrl, String source) {
        long when = System.currentTimeMillis();
        try {
            long id = store.add(title, body, chatUrl, source, when);
            if (canPostAndroid()) postAndroid(id, title, body, when);
            return id;
        } catch (Exception ex) {
            // Do not put private notification text in logcat.
            Log.e(TAG, "Notification persistence failed", ex);
            return 0;
        }
    }
    public List<NotificationStore.Entry> recent(int limit) { return store.recent(limit); }
    public List<NotificationStore.Entry> recent(int limit, int offset) {
        return store.recent(limit, offset);
    }
    public int totalCount() { return store.totalCount(); }
    public NotificationStore.Entry find(long id) { return id > 0 ? store.find(id) : null; }
    public int unreadCount() { return store.unreadCount(); }
    public void markRead(long id) {
        if (id <= 0) return;
        store.markRead(id);
        if (manager != null) manager.cancel(notificationId(id));
    }
    public void clearHistory() {
        store.clear();
        if (manager != null) manager.cancelAll();
    }
    public void close() { store.close(); }

    public static String formatTimestamp(long millis) {
        return DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT)
                .format(new Date(millis));
    }

    /** Only explicit conversation routes on chatgpt.com can be opened. */
    public static String safeChatUrl(String candidate) {
        if (candidate == null || candidate.length() > 1200) return null;
        try {
            Uri uri = Uri.parse(candidate);
            if (!"https".equalsIgnoreCase(uri.getScheme())
                    || !"chatgpt.com".equalsIgnoreCase(uri.getHost())
                    || uri.getPort() != -1) return null;
            String path = uri.getPath();
            if (path == null || !CHAT_PATH.matcher(path).find()) return null;
            return uri.buildUpon().clearQuery().fragment(null).build().toString();
        } catch (Exception e) {
            return null;
        }
    }

    private void postAndroid(long id, String title, String body, long when) {
        try {
            Intent intent = new Intent(context, MainActivity.class)
                    .setAction(ACTION_OPEN)
                    .setData(Uri.parse("mychatgpt://notification/" + id))
                    .putExtra(EXTRA_ID, id)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                            | Intent.FLAG_ACTIVITY_CLEAR_TOP
                            | Intent.FLAG_ACTIVITY_SINGLE_TOP);
            int flags = PendingIntent.FLAG_UPDATE_CURRENT
                    | (Build.VERSION.SDK_INT >= 23 ? PendingIntent.FLAG_IMMUTABLE : 0);
            PendingIntent pending = PendingIntent.getActivity(
                    context, notificationId(id), intent, flags);
            String time = formatTimestamp(when);
            Notification.Builder builder = Build.VERSION.SDK_INT >= 26
                    ? new Notification.Builder(context, CHANNEL)
                    : new Notification.Builder(context);
            builder.setSmallIcon(R.drawable.ic_notification)
                    .setContentTitle(title)
                    .setContentText(time + " - " + body)
                    .setStyle(new Notification.BigTextStyle().bigText(time + "\n" + body))
                    .setWhen(when).setShowWhen(true)
                    .setContentIntent(pending).setAutoCancel(true)
                    .setVisibility(Notification.VISIBILITY_PRIVATE)
                    .setCategory(Notification.CATEGORY_STATUS)
                    .setPriority(Notification.PRIORITY_DEFAULT);
            manager.notify(notificationId(id), builder.build());
        } catch (SecurityException e) {
            Log.w(TAG, "Notification permission denied");
        } catch (Exception e) {
            Log.e(TAG, "Posting native notification failed", e);
        }
    }
    private static int notificationId(long id) { return (int) (id % Integer.MAX_VALUE); }
    private static String clean(String text) {
        if (text == null) return "";
        String value = text.replaceAll("\\s+", " ").trim();
        return value.length() <= 480 ? value : value.substring(0, 480).trim();
    }
}
