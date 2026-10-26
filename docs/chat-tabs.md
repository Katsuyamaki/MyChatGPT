# Live chat tabs — feature branch

## Goal

Avoid expensive ChatGPT page navigation/reloads when alternating between
several active conversations, especially on the Galaxy Z Flip cover screen.

## How to use

1. Tap the draggable MyChatGPT floating icon.
2. Select **TABS** from the menu.
3. Tap **+ NEW CHAT TAB** to open a separate ChatGPT home session. Navigate
   to the desired conversation inside that tab.
4. Tap any existing tab name to switch back **without reloading its WebView**.
5. Use **EDIT** to assign a friendly label. Use **↑ / ↓** to reorder the list.
   Closing requires confirmation and destroys that tab's in-memory WebView.

The tabs page lists at most **five** sessions to bound renderer memory. The
menu remains independently draggable/pinnable.

## Architecture and lifecycle

- Each tab has a separate Android WebView, kept attached to the same
  FrameLayout. Inactive tabs are INVISIBLE, not destroyed, navigated, or
  paused by tab switching; ongoing site work can continue while the app is
  foregrounded. Only the active WebView is visible and receives input.
- WebViews share the app's normal CookieManager / site storage, like browser
  tabs in the same profile. Existing JS/document-start overrides, host
  policy, transfers, permissions, and OAuth popup setup apply to each.
- **Only on Activity pause** are all WebViews paused; resuming restores
  their timers. The previous battery-saving background behavior remains.
- The active tab is always the one used by the file/share/native navigation
  host APIs. Page-ready, loading-overlay, error-dialog, and progress
  callbacks are scoped to that tab so a background load does not dismiss
  another tab's overlay.
- Trusted completion/scheduled-task bridge callbacks are accepted from any
  registered ChatGPT tab, not merely the visible WebView. The per-tab
  canonical conversation URL still must match the completion's claim.
- Chat notifications select an already-open matching tab, or create a new tab
  while slots remain. When full, the previous in-page navigation behavior
  is retained.
- Android's renderer-gone callback recreates and reloads **only the dead tab**,
  preserving its saved ChatGPT URL. Other intact WebViews remain mounted.
- Tab IDs, order, label, last safe HTTPS ChatGPT URL, and selected tab are
  persisted using app-private SharedPreferences. Process death/relaunch
  reopens the selected tab and **lazily** loads other saved tabs on selection.
  Actual DOM/unsent drafts are only retained while the WebView stays alive.
  This is intentional: eagerly starting five WebViews on every launch would
  hurt startup and memory.
- For the selected tab, the legacy Android WebView history save/restore path
  is retained. Other tabs persist their URLs, not complete back/forward lists.

## Manual acceptance checklist

- Create three tabs with different conversations; alternate among them and
  confirm scroll, unsent composer, and ongoing responses persist without reload.
- Rename/reorder tabs and verify labels/order and selected URL survive app
  background/foreground and process restart (lazy loading after restart).
- Tap a completed-response notification for an open chat; it should select
  that tab without navigating a different one. Tap a new chat notification:
  it should open a new tab when room exists.
- Start a response in an inactive tab and verify that the app's normal
  completion notification still fires if the site keeps executing.
- Confirm OAuth popup/login, attachment picker, download and blob export in
  the selected tab work as before; background tabs must not steal their UI.
- Force-stop and reopen, rotate/fold, use external share intent, and trigger
  renderer recovery. Confirm the floating icon remains draggable.
- Open five tabs and verify **+ NEW CHAT TAB** disables until one is closed.

## Known limits

Inactive WebView JavaScript may still be throttled by Android/Chromium; live
tabs avoid navigation reloads but cannot guarantee background model execution
during OS memory pressure. Closing a tab or process death can discard unsent
local composer text. A server-side ChatGPT conversation is not deleted when
its local tab closes.
