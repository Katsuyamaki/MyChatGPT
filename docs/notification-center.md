# Native notification center

MyChatGPT mirrors transient ChatGPT web-page toast/alert elements to two destinations:

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

This observes visible site toasts and alerts **while the ChatGPT WebView is
running**. It is not Firebase Cloud Messaging, and does not receive server push
while the app has been terminated. Toast selectors are site-dependent and must
be validated on the actual ChatGPT frontend as it evolves. In-app history
persists across process deaths and Android notification dismissals, not
uninstalls or app-data wipes. Android 13+ requires user consent via
`POST_NOTIFICATIONS`; the Notifications page requests it on demand.
A blocked notification channel can be reopened from Android settings.

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

The observer still cannot capture notices emitted outside the ChatGPT web-page
DOM (for example, native Android toasts from download operations or cloud push
while the WebView is terminated). It is a frontend integration and the popup
markup may change with future ChatGPT releases.

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
- Disable Android alerts; ensure history still grows with no Android post.
- Deny notification permission; ensure history and navigation still work.
- Kill/relaunch the app; verify log remains. Use Older/Newer paging.
- CLEAR requires confirmation; it clears history and posted app notifications.
