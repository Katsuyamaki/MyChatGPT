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
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
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
    private int sitePopupCandidates;
    private int siteUnmarkedCandidates;
    private int sitePopupForwarded;
    private int siteGenerationSignals;
    private int siteCompletionSignals;
    private int siteMutationsObserved;
    private int completionsRecordedThisSession;
    private long latestCompletionAt;
    private boolean lastCompletionHadChatTitle;
    private boolean lastCompletionHadProjectName;

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
        sitePopupCandidates = 0;
        siteUnmarkedCandidates = 0;
        sitePopupForwarded = 0;
        siteGenerationSignals = 0;
        siteCompletionSignals = 0;
        siteMutationsObserved = 0;
    }

    /** Counters contain no conversation or popup text. */
    public boolean updateSiteMonitorMetrics(int mutations, int candidates,
                                            int unmarked, int forwarded,
                                            int generating, int completed) {
        int oldCandidates = sitePopupCandidates;
        int oldUnmarked = siteUnmarkedCandidates;
        int oldSent = sitePopupForwarded;
        int oldGenerating = siteGenerationSignals;
        int oldCompleted = siteCompletionSignals;
        siteMutationsObserved = Math.max(0, mutations);
        sitePopupCandidates = Math.max(0, candidates);
        siteUnmarkedCandidates = Math.max(0, unmarked);
        sitePopupForwarded = Math.max(0, forwarded);
        siteGenerationSignals = Math.max(0, generating);
        siteCompletionSignals = Math.max(0, completed);
        return sitePopupCandidates != oldCandidates
                || siteUnmarkedCandidates != oldUnmarked
                || sitePopupForwarded != oldSent
                || siteGenerationSignals != oldGenerating
                || siteCompletionSignals != oldCompleted;
    }

    public String siteMonitorSummary() {
        return "Popup candidates: " + sitePopupCandidates
                + " (unmarked " + siteUnmarkedCandidates + ")"
                + " · forwarded " + sitePopupForwarded
                + " · generations " + siteGenerationSignals
                + " · completions " + siteCompletionSignals;
    }

    public String siteMonitorDiagnostics() {
        return "Listener active: " + siteMonitorActive + "\n"
                + "Mutation records: " + siteMutationsObserved + "\n"
                + "Popup candidates: " + sitePopupCandidates + "\n"
                + "Unmarked floating candidates: " + siteUnmarkedCandidates + "\n"
                + "Web popup events forwarded: " + sitePopupForwarded + "\n"
                + "Generation starts: " + siteGenerationSignals + "\n"
                + "Completion signals forwarded: " + siteCompletionSignals + "\n"
                + "Real popups saved this session: " + siteCapturesThisSession + "\n"
                + "Response completions saved this session: " + completionsRecordedThisSession + "\n"
                + "Last completion chat title available: " + lastCompletionHadChatTitle + "\n"
                + "Last completion project name available: " + lastCompletionHadProjectName;
    }

    public int completionsRecordedThisSession() {
        return completionsRecordedThisSession;
    }

    public long latestCompletionAt() {
        return latestCompletionAt;
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

    /**
     * A DOM popup alone is not a user-facing notification. Reject sidebar,
     * clipboard, download and generic UI notices at this native boundary.
     * The synthetic probe remains explicitly test-only and is hidden from
     * the regular inbox (viewable under "Show other history").
     */
    public long recordSiteNotification(String text, String candidateUrl,
                                       String chatTitle, String projectName) {
        String body = clean(text);
        if (body.isEmpty()) return 0;
        boolean probe = body.startsWith("MyChatGPT site capture test:")
                || body.startsWith("MyChatGPT unmarked capture test:");
        if (!probe && !NotificationFilter.isScheduledTaskOccurrence("", body)) {
            return 0;
        }
        String chatUrl = safeChatUrl(candidateUrl);
        long now = SystemClock.elapsedRealtime();
        String key = "web-task|" + body + "|" + chatUrl;
        synchronized (recentKeys) {
            Long prev = recentKeys.get(key);
            if (prev != null && now >= prev && now - prev < 15000) return 0;
            recentKeys.put(key, now);
            if (recentKeys.size() > 40) {
                recentKeys.remove(recentKeys.keySet().iterator().next());
            }
        }
        long id;
        if (probe) {
            id = record("MyChatGPT capture test", body, chatUrl, "site-test");
        } else {
            try {
                long when = System.currentTimeMillis();
                // A repeated render/reconnect does not announce the same task
                // again, but the same recurring task can alert on later runs.
                String fingerprint = sha256("site-scheduled-task\n" + body
                        + "\n" + chatUrl + "\n" + (when / 120000L));
                String chatName = chatUrl == null ? "" : safeLabel(chatTitle);
                String project = chatUrl == null ? "" : safeLabel(projectName);
                if (!chatName.isEmpty() && chatName.equalsIgnoreCase(project)) {
                    project = "";
                }
                String title = chatName.isEmpty()
                        ? "Scheduled task" : "Scheduled task · " + chatName;
                String content = project.isEmpty()
                        ? body : body + " · Project: " + project;
                id = store.addExternal(title, content, chatUrl,
                        "scheduled-task", when, fingerprint);
                if (id > 0 && canPostAndroid()) {
                    postAndroid(id, title, content, when);
                }
                if (id > 0) notifyHistoryChanged();
            } catch (Exception ex) {
                Log.e(TAG, "Scheduled task notification persistence failed", ex);
                return 0;
            }
        }
        if (id > 0) {
            if (probe) siteProbeCapturesThisSession++;
            else siteCapturesThisSession++;
            latestSiteCaptureAt = System.currentTimeMillis();
        }
        return id;
    }

    /**
     * Independent chat-completion signal: no toast selector or official Android
     * app required. The page emits this only after seeing a generation in
     * progress and then a stable, finished assistant reply.
     *
     * Store uses an external-key SHA-256 digest of the route/opaque turn ID,
     * so a WebView reload cannot duplicate the same completion. No message
     * text is transferred from the page.
     */
    public long recordResponseCompletion(String conversation, String turnKey,
                                         String chatTitle, String projectName) {
        String url = safeChatUrl(conversation);
        if (url == null || turnKey == null || turnKey.length() < 3
                || turnKey.length() > 160) return 0;
        String name = safeLabel(chatTitle);
        String project = safeLabel(projectName);
        if (!name.isEmpty() && name.equalsIgnoreCase(project)) project = "";
        try {
            // Title content does NOT affect the stable turn fingerprint.
            String fingerprint = sha256("response-complete\n" + url + "\n" + turnKey);
            String title = name.isEmpty() ? "ChatGPT response complete" : name;
            String body = project.isEmpty()
                    ? "Response complete"
                    : "Response complete · Project: " + project;
            long when = System.currentTimeMillis();
            long id = store.addExternal(title, body, url, "completion",
                    when, fingerprint);
            if (id <= 0) return 0;
            completionsRecordedThisSession++;
            latestCompletionAt = when;
            lastCompletionHadChatTitle = !name.isEmpty();
            lastCompletionHadProjectName = !project.isEmpty();
            if (canPostAndroid()) postAndroid(id, title, body, when);
            notifyHistoryChanged();
            return id;
        } catch (Exception ex) {
            Log.e(TAG, "Could not save response completion", ex);
            return 0;
        }
    }

    private static String sha256(String input) throws Exception {
        byte[] bytes = MessageDigest.getInstance("SHA-256")
                .digest(input.getBytes(StandardCharsets.UTF_8));
        char[] digits = "0123456789abcdef".toCharArray();
        StringBuilder out = new StringBuilder(64);
        for (byte b : bytes) {
            out.append(digits[(b >>> 4) & 0x0F]);
            out.append(digits[b & 0x0F]);
        }
        return out.toString();
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
        if (!NotificationFilter.isScheduledTaskOccurrence(cleanTitle, cleanBody)) {
            return 0;
        }
        if (externalDigest == null || externalDigest.length() != 64) return 0;
        String url = safeChatUrl(candidateUrl);
        long when = postedAt > 0 ? postedAt : System.currentTimeMillis();
        try {
            long id = store.addExternal("Scheduled task · " + cleanTitle, cleanBody, url,
                    "official-task", when, externalDigest);
            if (id <= 0) return 0;
            // Reconnected listeners archive outstanding notifications silently:
            // only freshly posted events create another Android shade entry.
            if (!replay && canPostAndroid()) {
                postAndroid(id, "Scheduled task · " + cleanTitle, cleanBody, when);
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
    public List<NotificationStore.Entry> recent(int limit, int offset, boolean showAll) {
        return store.recent(limit, offset, showAll);
    }
    public int totalCount() { return store.totalCount(); }
    public int totalCount(boolean showAll) { return store.totalCount(showAll); }
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
    /** Chat/project labels are display metadata; never use them as navigation URLs. */
    private static String safeLabel(String raw) {
        if (raw == null) return "";
        String value = raw.replaceAll("[\\p{Cntrl}\\p{Cf}]", " ")
                .replaceAll("\\s+", " ").trim();
        if (value.length() > 120) value = value.substring(0, 120).trim();
        if (value.isEmpty() || value.contains("<") || value.contains(">")
                || value.contains("://")
                || value.equalsIgnoreCase("ChatGPT")
                || value.equalsIgnoreCase("New chat")
                || value.equalsIgnoreCase("Open chat")
                || value.equalsIgnoreCase("Chat options")) return "";
        return value;
    }

    private static String clean(String text) {
        if (text == null) return "";
        String value = text.replaceAll("\\s+", " ").trim();
        return value.length() <= 480 ? value : value.substring(0, 480).trim();
    }
}
