# Native notification center

MyChatGPT stores notifications from three clearly separated sources:

- **ChatGPT Android push relay (optional):** the official ChatGPT Android
  app's actual OS notifications, delivered by Android's
  `NotificationListenerService`, even with MyChatGPT backgrounded.
- **ChatGPT site popups:** transient toast/alert markup while MyChatGPT's
  ChatGPT WebView is running. This is not a cloud push feed.
- **MyChatGPT native popups:** MyChatGPT's own `Toast` status messages
  (download/share, settings and errors).

When an event is received, MyChatGPT delivers it to two destinations:

1. Android notification channel `ChatGPT updates`, if enabled and permitted.
2. Private SQLite database `mychatgpt_notifications.db`, independent of Android dismissals.

The floating native control is the MyChatGPT launcher icon. Open it and select
**Tune** (existing wallpaper/transparency, UI scale, text zoom, corner and reload)
or **Notifications** (status, enable/disable, permission shortcut, test, dated
history, clear, and newer/older pages). A red count on the icon shows unread items.
History is not truncated or automatically pruned; clearing is a deliberate action.

## Routing and privacy

- Each notice is assigned a SQLite ID. Android `PendingIntent` carries only that
  ID; tapping it reads the stored record, marks it read, and navigates the primary
  WebView to an HTTPS `chatgpt.com` conversation URL if available.
- In-app history rows use the same method. System dismissal does not mark read
  or remove the row. A notification without a trustworthy chat link opens the
  in-app history (from Android) or stays in history (from the overlay).
- The observer accepts only the main ChatGPT frame; native bridge revalidates
  the live main-WebView URL. Navigations are restricted to conversation paths
  containing `/c/<conversation-id>`, never arbitrary third-party URLs.
- The site sometimes provides an anchor to a different conversation. That link
  is preferred. Otherwise the current conversation URL is used when present.
  If no link exists, no chat link is fabricated.
- Rows may contain text visible in the originating toast. The private database
  is not backed up by Android (`allowBackup=false`); nothing is sent to a
  third-party service or written to logcat. Clearing the database is permanent.

## Scope and known limits

The site observer captures visible site toasts and alerts **while the ChatGPT
WebView is running**, but does not subscribe to ChatGPT's server push service.
The optional **Android companion-app relay** instead captures the official
ChatGPT app's delivered OS notifications while foregrounded or backgrounded.
If the official app or its notifications are disabled, no background push
source exists in MyChatGPT. Toast selectors are site-dependent and must
be validated on the actual ChatGPT frontend as it evolves. In-app history
persists across process deaths and Android notification dismissals, not
uninstalls or app-data wipes. Android 13+ requires user consent via
`POST_NOTIFICATIONS`; the Notifications page requests it on demand.
A blocked notification channel can be reopened from Android settings.

## Real push relay (official ChatGPT app, opt-in)

**Important:** There is no independently authenticated ChatGPT push feed in
this WebView wrapper. Local test notifications prove output only. A site
MutationObserver cannot detect server events when the WebView is not running.
On a Galaxy Flip 7, the practical background source is the already installed
official ChatGPT Android app. Android can notify MyChatGPT when that *other*
app posts a notification, provided the user explicitly grants Notification
Access.

### Set up

1. **Re-enable official ChatGPT app Android notifications** (the push source).
   MyChatGPT -> Notifications -> **CHATGPT APP ALERT SETTINGS** opens the
   official app's OS notification settings. ChatGPT's own account push setting
   also needs to be enabled for the event type.
2. MyChatGPT -> Notifications -> **GRANT MIRROR ACCESS**. In Android's
   Notification Access page, allow the MyChatGPT notification mirror. This is
   **not** the same permission as allowing MyChatGPT to *post* notifications.
   Some sideloaded Android versions may require manually allowing restricted
   settings in MyChatGPT's App info screen first.
3. MyChatGPT's Notifications page should show
   **Official ChatGPT push: LISTENING** once Android binds the listener.
   If it shows Access Needed, the OS permission is missing; if it says
   Connecting after access is granted, Android has not yet bound the service.
4. Have ChatGPT produce a **real** notification (a scheduled task completion,
   for example), not a local SEND TEST or TEST SITE POPUP CAPTURE.
   Check **Last official event** and **Last saved**. If both change, verify
   the corresponding **ChatGPT** history entry and MyChatGPT Android notice.
5. Swipe away the Android notification; the MyChatGPT history entry must
   remain. Relaunch MyChatGPT and verify the history persists.

The listener immediately filters by exact package
`com.openai.chatgpt` **before reading notification text or extras**. Other
apps' notifications are ignored, never saved and never sent to a server.
Nevertheless, Android's notification access grant is system-wide, so the
settings page must disclose that capability and the user must opt in.

Some original ChatGPT notices may appear alongside a mirrored MyChatGPT
notice; this implementation does not cancel or hide the official app's
notifications. Silence the official app's *sound* if desired, but **do not
block the app's notifications** or there will be nothing to relay.

### What the OS relay can and cannot do

- Receives official ChatGPT notifications in foreground/background via
  Android's managed notification listener (the WebView need not be alive).
  On reconnect it archives still-outstanding official notifications without
  generating fresh Android alerts.
- Private SQLite history uses a SHA-256 event fingerprint with unique index,
  preventing reimport on reconnect or repeat delivery. The v1 database
  migrates to v2 without dropping history.
- If Android includes an explicit safe conversation URL in the notification
  text, it is saved. Android normally exposes only an **opaque PendingIntent**,
  not the underlying ChatGPT chat URL. The app **does not invent an exact
  conversation link**; a record without one opens the history, not a made-up
  chat destination.
- Cannot receive any ChatGPT push if the official Android app notifications
  are disabled, the user revokes Notification Access, Android suppresses that
  notification, or ChatGPT never generates a push event. This is not an
  independent replacement for the official push transport.
- If the OS strips confidential/sensitive notification text, the listener
  cannot recover it. The "Last official event" time may update without a
  usable history row. Only unredacted content supplied by Android is used.
- The listener starts an Application process while backgrounded, but no
  WebView-crash-counter is bumped until an actual MainActivity starts.

## Foreground capture v2

The previous popup watcher only matched a small set of toast elements and waited
170 ms before checking them. Some transient website notices appeared/disappeared
or changed visibility/text before the watcher could capture them.

The updated main-document watcher now observes text insertions and relevant
state/ARIA attribute changes, captures with an initial 30 ms delay, retries
temporarily hidden/empty candidates, and recognizes Sonner, Radix, Toastify,
ARIA status/alert regions and notification-like popup cards. It excludes
conversation turns, composer fields, dialogs and navigation areas to avoid
logging normal chat content. The original Android posting/SQLite storage and
chat-routing code remains intact.

The in-app Notifications page shows **Site popup listener: ACTIVE** after the
injected watcher reports successful installation, and a count of real website
popups captured during the current app session. It also has two separate tests:

- **SEND TEST** validates Android notification posting and SQLite history, but
  bypasses the website.
- **TEST SITE POPUP CAPTURE** creates a temporary toast in the ChatGPT WebView
  and exercises the full page MutationObserver -> JavaScript bridge -> SQLite
  -> Android notification path. The matching history item is clearly labeled
  `MyChatGPT capture test`, not a real site notification.

To verify on the Flip 7: open a ChatGPT conversation, expand Notifications,
confirm the watcher is ACTIVE, run TEST SITE POPUP CAPTURE, then trigger a
genuine popup (not the test). Confirm it is reflected once in both Android
notifications and MyChatGPT history with a timestamp. Dismissing the Android
notification must not clear the saved record. Re-test after WebView reload or
renderer recreation; the same observer is registered at document start.

In addition to the page observer, all existing MyChatGPT-owned Android
`Toast.makeText` call sites now use `AppToast`: the original transient toast
is still displayed, and a separate **MyChatGPT popup** item is stored locally
and posted through Android when enabled. This covers existing download, share,
reload, settings and failure popups without requiring a DOM observer.

Neither path intercepts external apps' toasts, OEM-generated system UI notices,
or cloud push while the WebView is terminated. Website toast markup may change
with future ChatGPT releases.

## On-device acceptance checks (Flip 7)

- Icon (not `TUNE`) opens menu; back and close work; page fits folded cover
  screen in both top and bottom corners and is scrollable.
- Tune options retain previous values and behavior.
- In Notifications, SEND TEST: grant Android permission once, confirm a
  timestamped system entry and identical timestamped in-app history entry.
- Swipe the Android notification away. Reopen the menu; the history entry remains.
- With a ChatGPT conversation open, send another test; tap Android notification
  and history row separately. Both should reopen that conversation.
- Trigger a *real* ChatGPT site toast while the WebView is running; verify a
  single saved event and system notification, not duplicates from DOM changes.
- Turn official ChatGPT notifications **on**, grant MyChatGPT Notification
  Access, trigger an actual ChatGPT push and confirm **Last official event**
  updates and a timestamped `official-chatgpt` history entry appears.
- Repeat with MyChatGPT backgrounded. Do not treat SEND TEST as proof of
  upstream ChatGPT push.
- Revoke Notification Access and verify OS relay stops receiving other app
  notifications.
- Disable Android alerts; ensure history still grows with no Android post.
- Deny notification permission; ensure history and navigation still work.
- Trigger an existing native MyChatGPT Toast (such as a download/share status); verify the original popup remains visible AND a timestamped **MyChatGPT popup** appears in history and Android.
- Kill/relaunch the app; verify log remains. Use Older/Newer paging.
- CLEAR requires confirmation; it clears history and posted app notifications.
