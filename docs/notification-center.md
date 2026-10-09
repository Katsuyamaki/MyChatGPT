# Native notification center (focused feed)

MyChatGPT's **normal Android notifications and in-app inbox now contain only:**

1. **Chat responses completed** in the live ChatGPT WebView.
2. **Scheduled-task occurrences**, such as a task finishing or a reminder becoming due.

Opening the sidebar, copying/pasting, downloading, changing settings, and other
routine popups still show their original transient status if the app displays
one, but **do not create persistent history rows or Android notifications**.
A site toast or Android notification is no longer automatically considered
a user-important event merely because it exists.

## Event sources

- `completion` — a document-start script observes actual generation state,
  then a stable final assistant turn/action. The JavaScript-to-Java bridge
  transfers only the validated chat URL, a non-content turn identifier,
  and the chat/project display labels. It never transfers the response text.
  Stable per-turn database fingerprints prevent duplicate completions.
- `scheduled-task` — an actual transient ChatGPT website popup that matches
  the allowlist for a task/reminder **occurrence**, not generic task menu
  entries, task creation, configuration, or routine site toasts. It uses a
  short time-bucket fingerprint to suppress repeated re-renders.
- `official-task` — an **optional** Android notification listener that
  only inspects official ChatGPT package notifications and now filters for
  *scheduled-task occurrences* as well. It is neither required for direct
  foreground completions nor an independent OpenAI push connection.

The site detector still observes generic popup markup for diagnostics, but the
**native recording layer filters events**: only the allowed sources persist
and enter the Android shade.

### Chat and project names

On completion, the script looks for the link pointing to the **exact
conversation URL** in the ChatGPT sidebar and reads its label as the chat
title. For a regular conversation, a meaningful document title is a fallback.
For a project chat, the project name is extracted only from a recognizable
project link or header/breadcrumb. Titles are cached per conversation as the
sidebar opens and closes.

Example Android notice:

- **Title:** `Chat Title`
- **Body:** `Response complete · Project: Project Name`

If one label is not reliably available, MyChatGPT omits it instead of
mistakenly calling the project name the chat title. The completion still
saves and links to the correct conversation. The notification and history
row have the same timestamp and title/body.

Scheduled-task notifications only have a conversation link when the task
notice provides a trustworthy explicit chat link; they never assume that
the currently open conversation is the scheduled task's chat.

### Notification history

The default inbox and unread icon count show only `completion`,
`scheduled-task`, and `official-task` rows. Old copy/download/sidebar
rows and explicitly generated test alerts remain in the private database
**without appearing in the normal inbox**.

**SHOW OTHER HISTORY / TESTS** temporarily reveals those older entries
for diagnosis. **CLEAR** permanently removes all rows, including hidden
historical entries, and cancels posted MyChatGPT Android notifications.
The SQLite database is in private app storage, not backed up, and is
independent of Android shade dismissal. A notification's PendingIntent
references a local database ID and only validated ChatGPT HTTPS
conversation routes may be opened.

## On-device checks

1. Install the feature branch, open a conversation in MyChatGPT, then open
   MyChatGPT icon -> Notifications. Android alerts must be enabled.
2. Ask ChatGPT to generate a response, let it finish, then confirm a
   **response complete** entry appears with the chat title, and the
   project name if known. Tap it to reopen the correct conversation.
3. Copy text, paste text, open/close the sidebar and download a file. The
   original UI confirmations may still flash, but there should be
   **no new Android notices, default inbox entries or unread count**.
4. Trigger an actual scheduled task occurrence (not opening the task
   manager or creating a schedule). If its alert appears in the live
   WebView, confirm a `scheduled-task` row. If using the optional official
   listener, confirm an `official-task` row for task notifications only.
5. Use **SHOW OTHER HISTORY / TESTS** to access historical debug and
   synthetic SEND TEST / TEST SITE POPUP CAPTURE / TEST UNMARKED POPUP
   entries. These remain available for diagnosing missing events.
6. If a chat title is missing, **COPY CAPTURE COUNTERS** shows only
   structural counters plus whether the last completion had a chat title
   and project name. It does not copy private titles or conversation text.

The GitHub Actions workflow syntax-checks injected JavaScript, runs a
stock-JDK smoke test for the scheduled-event filter, assembles the APK,
verifies signing, and uploads the APK artifact.

## Limits

- The WebView completion monitor must observe a generation while it runs.
  Android can pause or terminate the WebView in the background, so this
  method cannot promise independent background push or reconstruct events
  during process death.
- Website scheduled-task popups can vary in wording or markup. The filter
  deliberately prefers **missing an ambiguous event** to spamming routine
  clicks and popups. A real task alert without recognizable task/reminder
  cues may need a new grounded classifier rule based on its actual wording.
- If optional official ChatGPT app mirroring is enabled, that official app
  must post Android notifications and MyChatGPT must be granted Notification
  Access. Android's permission exposes all apps' notifications, although
  this service discards other packages before reading their content.
  The official app may still show its own notification alongside a mirrored
  task notice.
- ChatGPT site UI structure can change, so chat labels may not always be
  available. The app never invents a chat or project label.
- ChatGPT site popup text may include private content. The app does not
  log payloads or transmit them to additional services; local database rows
  stay until manually cleared.
