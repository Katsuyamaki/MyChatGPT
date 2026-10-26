package com.katsuyamaki.mychatgpt.webview;

import android.app.Activity;
import android.content.Context;
import android.content.SharedPreferences;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.WebView;
import android.widget.FrameLayout;
import android.util.Log;

import com.katsuyamaki.mychatgpt.notifications.NotificationController;
import com.katsuyamaki.mychatgpt.site.ChatGptSiteContract;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Several independent, live ChatGPT WebViews in one Activity.
 *
 * Switching hides the previous view but NEVER navigates, destroys or pauses
 * it: ongoing conversations retain their DOM, scroll and in-flight responses.
 * All views use the same Chromium cookie/storage profile. Only the selected
 * view is restored eagerly after process death; other saved URLs are loaded
 * on first selection to avoid a startup memory/network spike.
 *
 * All methods are called on the Android UI thread.
 */
public final class ChatTabsController {
    private static final String TAG = "MyChatGPTTabs";
    private static final String PREFS = "mychatgpt_chat_tabs";
    private static final String KEY_TABS = "tabs_v1";
    private static final String KEY_ACTIVE = "active_id_v1";
    public static final int MAX_TABS = 5;

    public interface Host {
        void setupWebView(WebView view);
        void loadUrl(WebView view, String url);
        void onTabSelected(WebView view, boolean ready);
        void destroyWebView(WebView view);
    }

    public static final class TabInfo {
        public final String id;
        public final String title;
        public final String url;
        public final boolean active;
        public final boolean warm;

        private TabInfo(String id, String title, String url,
                        boolean active, boolean warm) {
            this.id = id;
            this.title = title;
            this.url = url;
            this.active = active;
            this.warm = warm;
        }
    }

    private static final class Tab {
        final String id;
        String customTitle = "";
        String pageTitle = "";
        String url = ChatGptSiteContract.MAIN_URL;
        WebView view;
        boolean ready;

        Tab(String id) { this.id = id; }
    }

    private final Activity activity;
    private final FrameLayout root;
    private final Host host;
    private final SharedPreferences prefs;
    private final List<Tab> tabs = new ArrayList<>();
    private String activeId;

    public ChatTabsController(Activity activity, FrameLayout root,
                              WebView initialView, Host host) {
        this.activity = activity;
        this.root = root;
        this.host = host;
        this.prefs = activity.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        restoreMetadata();
        if (tabs.isEmpty()) tabs.add(new Tab(UUID.randomUUID().toString()));
        activeId = prefs.getString(KEY_ACTIVE, tabs.get(0).id);
        Tab active = find(activeId);
        if (active == null) {
            active = tabs.get(0);
            activeId = active.id;
        }
        active.view = initialView;
        active.view.setVisibility(View.VISIBLE);
    }

    public String getStartupUrl() {
        Tab active = find(activeId);
        return active == null ? ChatGptSiteContract.MAIN_URL : active.url;
    }

    public boolean contains(WebView view) {
        return findByView(view) != null;
    }

    public boolean isActive(WebView view) {
        Tab tab = findByView(view);
        return tab != null && tab.id.equals(activeId);
    }

    public int count() {
        return tabs.size();
    }

    public List<TabInfo> snapshot() {
        List<TabInfo> result = new ArrayList<>();
        for (int i = 0; i < tabs.size(); i++) {
            Tab tab = tabs.get(i);
            captureUrl(tab);
            result.add(new TabInfo(tab.id, displayTitle(tab, i),
                    tab.url, tab.id.equals(activeId), tab.view != null));
        }
        return result;
    }

    public boolean addTab(String requestedUrl) {
        if (tabs.size() >= MAX_TABS) return false;
        Tab tab = new Tab(UUID.randomUUID().toString());
        tab.url = safeUrl(requestedUrl);
        tabs.add(tab);
        return selectTab(tab.id);
    }

    /**
     * A notification should select an already-running conversation instead
     * of replacing it, or create a dedicated tab when capacity allows.
     * With a full tab strip, the Activity's existing SPA navigation is used.
     */
    public boolean openChatInTab(String candidateUrl) {
        String target = NotificationController.safeChatUrl(candidateUrl);
        if (target == null) return false;
        for (Tab tab : tabs) {
            captureUrl(tab);
            String candidate = NotificationController.safeChatUrl(tab.url);
            if (target.equals(candidate)) {
                return selectTab(tab.id);
            }
        }
        return addTab(target);
    }

    public boolean selectTab(String id) {
        Tab target = find(id);
        if (target == null) return false;
        if (target.id.equals(activeId)) return true;

        Tab current = find(activeId);
        if (current != null && current.view != null) {
            captureUrl(current);
            current.view.setVisibility(View.INVISIBLE);
            current.view.setImportantForAccessibility(
                    View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS);
            current.view.clearFocus();
        }

        boolean fresh = target.view == null;
        if (fresh) {
            WebView view = new WebView(activity);
            view.setVisibility(View.INVISIBLE);
            view.setLayoutParams(new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT));
            root.addView(view, 0);
            target.view = view;
            host.setupWebView(view);
        }

        activeId = target.id;
        target.view.setVisibility(View.VISIBLE);
        target.view.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_AUTO);
        host.onTabSelected(target.view, target.ready);
        target.view.requestFocus();

        // loadUrl is deliberately NOT called when returning to a warm tab.
        if (fresh) host.loadUrl(target.view, target.url);
        persist();
        return true;
    }

    public boolean closeTab(String id) {
        if (tabs.size() <= 1) return false;
        Tab tab = find(id);
        if (tab == null) return false;
        int index = tabs.indexOf(tab);
        if (tab.id.equals(activeId)) {
            Tab next = tabs.get(index == 0 ? 1 : index - 1);
            selectTab(next.id);
        }
        tabs.remove(tab);
        if (tab.view != null) {
            host.destroyWebView(tab.view);
            tab.view = null;
        }
        persist();
        return true;
    }

    public boolean moveTab(String id, int direction) {
        Tab tab = find(id);
        if (tab == null || (direction != -1 && direction != 1)) return false;
        int index = tabs.indexOf(tab);
        int next = index + direction;
        if (next < 0 || next >= tabs.size()) return false;
        tabs.set(index, tabs.get(next));
        tabs.set(next, tab);
        persist();
        return true;
    }

    public boolean renameTab(String id, String label) {
        Tab tab = find(id);
        if (tab == null) return false;
        tab.customTitle = cleanTitle(label);
        persist();
        return true;
    }

    public void onTitleChanged(WebView view, String title) {
        Tab tab = findByView(view);
        if (tab == null) return;
        String cleaned = cleanTitle(title);
        if (cleaned.equalsIgnoreCase("ChatGPT")) cleaned = "";
        if (cleaned.endsWith(" - ChatGPT")) {
            cleaned = cleanTitle(cleaned.substring(0,
                    cleaned.length() - " - ChatGPT".length()));
        }
        if (!cleaned.equals(tab.pageTitle)) {
            tab.pageTitle = cleaned;
            persist();
        }
    }

    public void onPageStarted(WebView view) {
        Tab tab = findByView(view);
        if (tab != null) tab.ready = false;
    }

    public void onPageFinished(WebView view, String url) {
        Tab tab = findByView(view);
        if (tab == null) return;
        if (isSafeUrl(url)) tab.url = url;
        captureUrl(tab);
        persist();
    }

    public void onPageReady(WebView view) {
        Tab tab = findByView(view);
        if (tab != null) tab.ready = true;
    }

    /** Renderer replacement may happen on either the selected or a hidden tab. */
    public String savedUrlFor(WebView view) {
        Tab tab = findByView(view);
        if (tab == null) return ChatGptSiteContract.MAIN_URL;
        captureUrl(tab);
        return tab.url;
    }

    public void replaceAfterRendererGone(WebView dead, WebView fresh) {
        Tab tab = findByView(dead);
        if (tab == null) return;
        tab.view = fresh;
        tab.ready = false;
        boolean active = tab.id.equals(activeId);
        fresh.setVisibility(active ? View.VISIBLE : View.INVISIBLE);
        fresh.setImportantForAccessibility(active
                ? View.IMPORTANT_FOR_ACCESSIBILITY_AUTO
                : View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS);
    }

    public List<WebView> loadedViews() {
        List<WebView> result = new ArrayList<>();
        for (Tab tab : tabs) if (tab.view != null) result.add(tab.view);
        return result;
    }

    public void persist() {
        JSONArray array = new JSONArray();
        for (Tab tab : tabs) {
            captureUrl(tab);
            JSONObject json = new JSONObject();
            try {
                json.put("id", tab.id);
                json.put("url", tab.url);
                json.put("label", tab.customTitle);
                json.put("title", tab.pageTitle);
                array.put(json);
            } catch (Exception e) {
                Log.w(TAG, "Unable to save tab entry", e);
            }
        }
        prefs.edit().putString(KEY_TABS, array.toString())
                .putString(KEY_ACTIVE, activeId).apply();
    }

    public void destroyAll() {
        persist();
        for (Tab tab : tabs) {
            if (tab.view != null) {
                host.destroyWebView(tab.view);
                tab.view = null;
            }
        }
    }

    private Tab find(String id) {
        if (id == null) return null;
        for (Tab tab : tabs) if (id.equals(tab.id)) return tab;
        return null;
    }

    private Tab findByView(WebView view) {
        if (view == null) return null;
        for (Tab tab : tabs) if (tab.view == view) return tab;
        return null;
    }

    private void captureUrl(Tab tab) {
        if (tab.view == null) return;
        try {
            String current = tab.view.getUrl();
            if (isSafeUrl(current)) tab.url = current;
        } catch (Throwable ignored) {
            // A WebView whose renderer just died still has its last safe URL.
        }
    }

    private String displayTitle(Tab tab, int index) {
        if (!tab.customTitle.isEmpty()) return tab.customTitle;
        if (!tab.pageTitle.isEmpty()) return tab.pageTitle;
        return "Chat " + (index + 1);
    }

    private void restoreMetadata() {
        String saved = prefs.getString(KEY_TABS, null);
        if (saved == null) return;
        Set<String> seen = new HashSet<>();
        try {
            JSONArray array = new JSONArray(saved);
            for (int i = 0; i < array.length() && tabs.size() < MAX_TABS; i++) {
                JSONObject json = array.optJSONObject(i);
                if (json == null) continue;
                String id = json.optString("id", "");
                if (id.isEmpty() || !seen.add(id)) continue;
                Tab tab = new Tab(id);
                tab.url = safeUrl(json.optString("url", ""));
                tab.customTitle = cleanTitle(json.optString("label", ""));
                tab.pageTitle = cleanTitle(json.optString("title", ""));
                tabs.add(tab);
            }
        } catch (Exception e) {
            Log.w(TAG, "Saved tab metadata corrupt; using a fresh tab", e);
            tabs.clear();
        }
    }

    private static String cleanTitle(String candidate) {
        if (candidate == null) return "";
        return candidate.replace('\n', ' ').replace('\r', ' ')
                .trim().substring(0, Math.min(64,
                        candidate.replace('\n', ' ').replace('\r', ' ').trim().length()));
    }

    private static boolean isSafeUrl(String url) {
        return url != null && url.startsWith("https://")
                && ChatGptSiteContract.isChatGptWebUrl(url);
    }

    private static String safeUrl(String url) {
        return isSafeUrl(url) ? url : ChatGptSiteContract.MAIN_URL;
    }
}
