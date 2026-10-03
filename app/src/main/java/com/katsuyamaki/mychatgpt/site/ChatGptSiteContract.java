package com.katsuyamaki.mychatgpt.site;

import android.net.Uri;

import java.util.HashMap;
import java.util.Map;

/**
 * ChatGPT website contract.
 *
 * Owns values and scripts that exist specifically because MyChatGPT wraps
 * chatgpt.com. Keep this class behavior-preserving: selector/script changes
 * are site-integration changes and require focused owner-device validation.
 */
public final class ChatGptSiteContract {

    private ChatGptSiteContract() {}

    public static final String MAIN_URL = "https://chatgpt.com/";

    public static final String MOBILE_USER_AGENT =
            "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 "
                    + "(KHTML, like Gecko) Chrome/131.0.0.0 Mobile Safari/537.36";

    /**
     * Request headers applied to main-frame loads. The empty
     * X-Requested-With value is inherited WebGPT behavior used to avoid
     * ChatGPT's app-install banner.
     */
    public static Map<String, String> newRequestHeaders() {
        Map<String, String> headers = new HashMap<>();
        headers.put("X-Requested-With", "");
        return headers;
    }

    /** True only for http/https URLs on the site allowlist. */
    public static boolean isAllowedWebUrl(String url) {
        try {
            if (url == null) return false;
            Uri uri = Uri.parse(url);
            String scheme = uri.getScheme();
            if (!"https".equalsIgnoreCase(scheme) && !"http".equalsIgnoreCase(scheme)) {
                return false;
            }
            String host = uri.getHost();
            return host != null && isAllowedHost(host);
        } catch (Throwable t) {
            return false;
        }
    }

    /** ChatGPT-owned host check used when an OAuth popup returns to the app. */
    public static boolean isChatGptHost(String host) {
        return host != null
                && (host.equals("chatgpt.com") || host.endsWith(".chatgpt.com"));
    }

    /** True only for http(s) URLs on a ChatGPT-owned host. */
    public static boolean isChatGptWebUrl(String url) {
        try {
            if (url == null) return false;
            Uri uri = Uri.parse(url);
            String scheme = uri.getScheme();
            return ("https".equalsIgnoreCase(scheme)
                    || "http".equalsIgnoreCase(scheme))
                    && isChatGptHost(uri.getHost());
        } catch (Throwable t) {
            return false;
        }
    }

    /**
     * Page overrides, installed at DOCUMENT START in EVERY frame (see
     * installDocumentStartOverrides). Because document-start scripts run
     * before any site code, the site can never capture a pre-override
     * reference. The same string doubles as the onPageFinished fallback for
     * WebViews that lack DOCUMENT_START_SCRIPT support — the guards inside
     * (_shareOverridden etc.) keep it idempotent.
     */
    /**
     * Page overrides, installed at DOCUMENT START in EVERY frame (see
     * installDocumentStartOverrides) via WebViewCompat.addDocumentStartJavaScript.
     *
     * Round-5 notes:
     *  - NO JavaScript-side origin gate: ChatGPT runs its share/export UI in
     *    blob: and cross-origin iframes, and a JS-side allowlist blocked
     *    exactly those calls (same "spinner stops, nothing happens" symptom
     *    as having no override at all). The Java side (WebAppInterface)
     *    still gates every bridge method on the WebView's real URL.
     *  - navigator.canShare is now HONEST (we support text/url/title or a
     *    single file), instead of blindly returning true.
     *  - The blob keeper is CAPPED (16 entries / 128MB, oldest evicted) so
     *    blob-heavy pages cannot be memory-bombed by our lifetime extension.
     *  - dbg() toasts (debug builds only) report when the page exercises
     *    share / window.open / blob-download — the telemetry that tells us
     *    which mechanism a misbehaving feature actually uses.
     */
    /**
     * Page overrides, installed at DOCUMENT START in EVERY frame (see
     * installDocumentStartOverrides) via WebViewCompat.addDocumentStartJavaScript.
     *
     * CRITICAL: Java string concatenation produces ONE LINE with no newlines,
     * so a single "//" comment anywhere in this script would comment out the
     * entire remainder of the script (the bug that made rounds 3-6 change
     * nothing). ALL comments here MUST be /* *\/ style.
     *
     * Round-7 notes:
     *  - No JS-side origin gate (ChatGPT share/export UI runs in blob: and
     *    cross-origin iframes); the Java side gates every bridge method.
     *  - Beacon: toasts "overrides active (main frame)" once per page load,
     *    so it is instantly visible whether this script is running at all.
     */
    public static final String PAGE_OVERRIDES_JS = "(function(){" +
            "  function dbg(m){ try { var b = window.AndroidBridge; if (b && b.debugLog) b.debugLog(String(m)); } catch(e) {} }" +
            "  /* 1. navigator.share -> system share sheet / clipboard */" +
            "  try {" +
            "    if (!window._shareOverridden) {" +
            "      window._shareOverridden = true;" +
            "      navigator.share = function(data) {" +
            "        try {" +
            "          var b = window.AndroidBridge;" +
            "          var d = data || {};" +
            "          var files = (d.files && d.files.length) ? d.files : null;" +
            "          var bm = b ? ((b.shareText ? 'S' : '') + (b.shareFile ? 'F' : '') + (b.copyToClipboard ? 'C' : '') + (b.debugLog ? 'D' : '')) : 'none';" +
            "          dbg('share called: files=' + (files ? files.length : 0) + ' text=' + (d.text ? 'yes' : 'no') + ' url=' + (d.url ? 'yes' : 'no') + ' bridge=' + bm);" +
            "          if (!b) return Promise.reject(new Error('No share support'));" +
            "          if (files && files.length === 1 && b.shareFile) {" +
            "            dbg('dispatching shareFile');" +
            "            var f = files[0];" +
            "            var fr = new FileReader();" +
            "            fr.onloadend = function(){" +
            "              try { b.shareFile(String(d.title || d.text || f.name || ''), String(fr.result), String(f.name || 'file'), String(f.type || '')); } catch(e) {}" +
            "            };" +
            "            fr.onerror = function(){" +
            "              try { dbg('share: file read failed'); } catch(e) {}" +
            "              try { if (d.text) b.copyToClipboard(String(d.text)); } catch(e) {}" +
            "            };" +
            "            fr.readAsDataURL(f);" +
            "            return Promise.resolve();" +
            "          }" +
            "          if (files && files.length > 1) return Promise.reject(new Error('Multiple files not supported'));" +
            "          if ((d.text || d.url) && b.shareText) {" +
            "            dbg('dispatching shareText');" +
            "            b.shareText(String(d.text || ''), String(d.url || ''));" +
            "            return Promise.resolve();" +
            "          }" +
            "          if (d.text && b.copyToClipboard) {" +
            "            b.copyToClipboard(String(d.text));" +
            "            return Promise.resolve();" +
            "          }" +
            "          return Promise.reject(new Error('Nothing to share'));" +
            "        } catch(e) { return Promise.reject(e); }" +
            "      };" +
            "      navigator.canShare = function(data) {" +
            "        try {" +
            "          var d = data || {};" +
            "          if (d.files && d.files.length) return d.files.length === 1;" +
            "          return !!(d.text || d.url || d.title);" +
            "        } catch(e) { return false; }" +
            "      };" +
            "      try { navigator.share.toString = function(){ return 'function share() { [native code] }'; }; } catch(e) {}" +
            "      try { navigator.canShare.toString = function(){ return 'function canShare() { [native code] }'; }; } catch(e) {}" +
            "    }" +
            "  } catch(e) {}" +
            "  /* 2. document.execCommand('copy') fallback */" +
            "  try {" +
            "    if (!window._execOverridden) {" +
            "      window._execOverridden = true;" +
            "      var origExec = document.execCommand.bind(document);" +
            "      document.execCommand = function(cmd, showUI, value) {" +
            "        if (cmd === 'copy') {" +
            "          var sel = window.getSelection();" +
            "          if (sel && sel.toString()) {" +
            "            try {" +
            "              if (window.AndroidBridge) window.AndroidBridge.copyToClipboard(sel.toString());" +
            "            } catch(e) {}" +
            "            return true;" +
            "          }" +
            "        }" +
            "        return origExec(cmd, showUI, value);" +
            "      };" +
            "    }" +
            "  } catch(e) {}" +
            "  /* 3. blob lifetime keeper (capped: 16 blobs / 128MB) */" +
            "  try {" +
            "    if (!window._blobKeep) {" +
            "      window._blobKeep = true;" +
            "      var origCreate = URL.createObjectURL;" +
            "      var origRevoke = URL.revokeObjectURL;" +
            "      var store = {};" +
            "      var order = [];" +
            "      var total = 0;" +
            "      function evict(){" +
            "        try {" +
            "          while (order.length > 16 || total > 134217728) {" +
            "            var u = order.shift();" +
            "            if (u === undefined) break;" +
            "            var blob = store[u];" +
            "            if (blob && blob.size) total -= blob.size;" +
            "            delete store[u];" +
            "            try { origRevoke.call(URL, u); } catch(e) {}" +
            "          }" +
            "        } catch(e) {}" +
            "      }" +
            "      URL.createObjectURL = function(blob) {" +
            "        var u = origCreate.call(URL, blob);" +
            "        try {" +
            "          store[u] = blob;" +
            "          order.push(u);" +
            "          if (blob && blob.size) total += blob.size;" +
            "          evict();" +
            "        } catch(e) {}" +
            "        return u;" +
            "      };" +
            "      URL.revokeObjectURL = function(u) {" +
            "        setTimeout(function(){" +
            "          try {" +
            "            delete store[u];" +
            "            var i = order.indexOf(u);" +
            "            if (i >= 0) order.splice(i, 1);" +
            "            origRevoke.call(URL, u);" +
            "          } catch(e) {}" +
            "        }, 600000);" +
            "      };" +
            "      window.__webgptBlobs = store;" +
            "    }" +
            "  } catch(e) {}" +
            "  /* 4. blob download interception: prototype click + capture listener */" +
            "  function webgptSendChunks(name, mime, b64){" +
            "    try {" +
            "      var b = window.AndroidBridge;" +
            "      if (!b) return false;" +
            "      if (!b.onBlobChunk) {" +
            "        if (b.onBlobDownload) b.onBlobDownload(String(name), 'data:' + mime + ';base64,' + b64);" +
            "        return true;" +
            "      }" +
            "      var CH = 262144;" +
            "      var total = Math.ceil(b64.length / CH);" +
            "      if (total < 1) total = 1;" +
            "      for (var i = 0; i < total; i++) {" +
            "        b.onBlobChunk(String(name), String(mime), i, total, b64.substring(i * CH, Math.min((i + 1) * CH, b64.length)));" +
            "      }" +
            "      return true;" +
            "    } catch (e) { return false; }" +
            "  }" +
            "  function webgptFindBlob(href){" +
            "    try {" +
            "      var st = window.__webgptBlobs;" +
            "      if (st && st[href]) return st[href];" +
            /* same-origin child frames may hold the blob (export UI runs in an iframe) */
            "      for (var i = 0; i < window.frames.length; i++) {" +
            "        try { var fs = window.frames[i].__webgptBlobs; if (fs && fs[href]) return fs[href]; } catch (e) {}" +
            "      }" +
            "      try { var ps = window.parent.__webgptBlobs; if (ps && ps[href]) return ps[href]; } catch (e) {}" +
            "      return null;" +
            "    } catch (e) { return null; }" +
            "  }" +
            "  function webgptSendDataUrl(name, dataUrl){" +
            "    try {" +
            "      var b = window.AndroidBridge;" +
            "      if (!b) return false;" +
            "      var comma = dataUrl.indexOf(',');" +
            "      if (comma < 0) return false;" +
            "      var meta = dataUrl.substring(5, comma);" +
            "      var semi = meta.indexOf(';');" +
            "      var mime = semi > 0 ? meta.substring(0, semi) : 'application/octet-stream';" +
            "      return webgptSendChunks(name, mime, dataUrl.substring(comma + 1));" +
            "    } catch (e) { return false; }" +
            "  }" +
            /* THE KEY INSIGHT: revoking a blob URL does NOT destroy the Blob
               object. Our keeper stored the object at createObjectURL time,
               so reading the OBJECT via FileReader works even after the URL
               is dead — no URL resolution needed at all. This is the path a
               real browser's download manager effectively takes. */
            "  function webgptFetchBlob(href, name, retry){" +
            "    try {" +
            "      dbg('blob download: ' + name);" +
            "      var blobObj = webgptFindBlob(href);" +
            "      if (blobObj) {" +
            "        dbg('blob from store: ' + (blobObj.size || '?') + ' bytes');" +
            "        var fr = new FileReader();" +
            "        fr.onloadend = function(){" +
            "          try { dbg('blob read ok'); webgptSendDataUrl(name || 'download', String(fr.result)); } catch(e) {}" +
            "        };" +
            "        fr.onerror = function(){ dbg('blob read failed'); if (retry) retry(); };" +
            "        fr.readAsDataURL(blobObj);" +
            "        return;" +
            "      }" +
            "      dbg('blob store miss');" +
            /* sync XHR fallback (worker-created URLs resolvable from this frame) */
            "      try {" +
            "        var xhr = new XMLHttpRequest();" +
            "        xhr.open('GET', href, false);" +
            "        xhr.overrideMimeType('text/plain; charset=x-user-defined');" +
            "        xhr.send();" +
            "        var s = xhr.responseText || '';" +
            "        if ((xhr.status === 200 || xhr.status === 0) && s.length > 0) {" +
            "          var ct = xhr.getResponseHeader('Content-Type') || '';" +
            "          if (ct.indexOf(';') > 0) ct = ct.split(';')[0];" +
            "          if (!ct) ct = 'application/octet-stream';" +
            "          var parts = [];" +
            "          for (var i = 0; i < s.length; i += 0x2000) {" +
            "            var end = Math.min(i + 0x2000, s.length);" +
            "            var buf = new Array(end - i);" +
            "            for (var j = i; j < end; j++) buf[j - i] = s.charCodeAt(j) & 0xFF;" +
            "            parts.push(String.fromCharCode.apply(null, buf));" +
            "          }" +
            "          var b64 = btoa(parts.join(''));" +
            "          dbg('blob sync ok: ' + s.length + ' bytes');" +
            "          webgptSendChunks(name || 'download', ct, b64);" +
            "          return;" +
            "        }" +
            "        dbg('blob sync status ' + xhr.status);" +
            "      } catch (e) { dbg('blob sync xhr failed'); }" +
            /* async fetch fallback (keeper-preserved URLs) */
            "      fetch(href)" +
            "        .then(function(r){ return r.blob(); })" +
            "        .then(function(b){" +
            "          dbg('blob fetch ok: ' + b.size + ' bytes');" +
            "          var fr = new FileReader();" +
            "          fr.onloadend = function(){" +
            "            try { dbg('blob read ok'); webgptSendDataUrl(name || 'download', String(fr.result)); } catch(e) {}" +
            "          };" +
            "          fr.onerror = function(){ dbg('blob read failed'); if (retry) retry(); };" +
            "          fr.readAsDataURL(b);" +
            "        })" +
            "        .catch(function(e){ dbg('blob fetch failed'); if (retry) retry(); });" +
            "    } catch (e) { dbg('blob hook error'); if (retry) retry(); }" +
            "  }" +
            "  try {" +
            "    if (!window._dlHook) {" +
            "      window._dlHook = true;" +
            "      var origClick = HTMLAnchorElement.prototype.click;" +
            "      HTMLAnchorElement.prototype.click = function() {" +
            "        try {" +
            "          var href = this.href || '';" +
            "          if (href.indexOf('blob:') === 0 && this.hasAttribute('download')) {" +
            "            var self = this, args = arguments;" +
            "            webgptFetchBlob(href, this.getAttribute('download') || 'download', function(){" +
            "              try { origClick.apply(self, args); } catch (e) {}" +
            "            });" +
            "            return;" +
            "          }" +
            "        } catch (e) {}" +
            "        return origClick.apply(this, arguments);" +
            "      };" +
            "      document.addEventListener('click', function(ev){" +
            "        try {" +
            "          var t = ev && ev.target && ev.target.closest ? ev.target.closest('a[download]') : null;" +
            "          if (t) {" +
            "            var href = t.href || '';" +
            "            if (href.indexOf('blob:') === 0) {" +
            "              var b3 = window.AndroidBridge;" +
            "              if (b3 && b3.onBlobChunk) {" +
            /* the sync-XHR path will deliver the file; suppress the default
               download so the (dead-URL) DownloadListener fallback and its
               failure toasts never fire */
            "                try { ev.preventDefault(); ev.stopPropagation(); } catch (e2) {}" +
            "              }" +
            "              webgptFetchBlob(href, t.getAttribute('download') || 'download', null);" +
            "            }" +
            "          }" +
            "        } catch (e) {}" +
            "      }, true);" +
            "    }" +
            "  } catch(e) {}" +
            "  /* 5. window.open — NO HOOK. An earlier debug-only hook here" +
            "     * (dbg('window.open: '+url) before origOpen.apply) broke the" +
            "     * user-gesture context that WebView needs for onCreateWindow" +
            "     * to fire. The toast appeared but the popup was never" +
            "     * created, so external links (X, Reddit, LinkedIn) never" +
            "     * reached the user's default browser. Routing now happens" +
            "     * entirely in the popup's shouldOverrideUrlLoading via" +
            "     * openUrlInBrowser — same as the official release. */" +
            "  /* 6. beacon: proves the script is running (main frame, once per load) */" +
            "  try {" +
            "    if (window.top === window && !window.__webgptBeacon) {" +
            "      window.__webgptBeacon = true;" +
            "      dbg('overrides active (main frame)');" +
            "    }" +
            "  } catch(e) {}" +
            /* 7. LOAD-STATE TRACKER — event-based fully-loaded detection.
             * Two independent settle signals, both device-speed independent:
             *   - NET: last time a fetch/XHR was STARTED (streaming-friendly;
             *     long-lived responses started long ago do not block)
             *   - DOM: last DOM mutation observed anywhere (hydration, SPA
             *     re-renders, token streaming — all mutate continuously)
             * A page is settled when the composer exists, nothing mutated for
             * 2s, and no request started for 1.5s. This is the in-page
             * equivalent of Puppeteer's networkidle heuristic. */
            "  try {" +
            "    if (!window.__webgptLoad) {" +
            "      var L = {lastMut: Date.now(), lastStart: Date.now(), n: 0, mo: null};" +
            "      window.__webgptLoad = L;" +
            "      var origFetch = window.fetch;" +
            "      if (origFetch) {" +
            "        window.fetch = function(){" +
            "          L.lastStart = Date.now();" +
            "          return origFetch.apply(window, arguments);" +
            "        };" +
            "      }" +
            "      var origOpen = XMLHttpRequest.prototype.open;" +
            "      XMLHttpRequest.prototype.open = function(){" +
            "        L.lastStart = Date.now();" +
            "        return origOpen.apply(this, arguments);" +
            "      };" +
            "      var mo = new MutationObserver(function(muts){ L.n += muts.length; L.lastMut = Date.now(); });" +
            "      L.mo = mo;" +
            "      mo.observe(document, {childList: true, subtree: true, attributes: true, characterData: true});" +
            "    }" +
            "  } catch(e) {}" +
            "})();";

    /**
     * PAGE-READY WATCHER — DOM-driven "site REALLY loaded" signal.
     *
     * Problem being solved: onPageFinished fires when the document load
     * event runs, which on chatgpt.com can land long AFTER the React app
     * has hydrated and rendered (streamed HTML, late subresources) — so the
     * loading overlay used to sit on top of an already-usable page for
     * seconds ("site loaded, app not responding").
     *
     * What the site REALLY looks like when done (verified against full
     * DOM snapshots of the loaded page in both variants):
     *   - the composer (id=prompt-textarea / contenteditable textbox), and
     *   - one of the LAST elements to appear:
     *       * the splash greeting — wrapper div carries the stable
     *         attribute data-splash-headline-option (value varies by time
     *         and locale: ON_YOUR_MIND, SHOULD_WE_BEGIN, ...). In the
     *         mobile DOM this wrapper EXISTS but is CSS-hidden
     *         ("hidden sm:block") — so we test VISIBILITY
     *         (getClientRects), not presence.
     *       * the mobile suggestion chips — buttons inside
     *         [data-testid=use-case-prompt-chips] ("Create an image...",
     *         "Write or edit", "Search the web" — text varies by locale).
     *       * a restored conversation — [data-testid^=conversation-turn].
     * Text is never matched — only stable testids/attributes, so it works
     * across languages and greeting rotations.
     *
     * Fallbacks so the overlay can never get stuck:
     *   - settle heuristic (same as waitForComposerReady): composer exists
     *     AND no DOM mutation for 2s AND no fetch/XHR start for 1.5s
     *     (reads window.__webgptLoad, installed by PAGE_OVERRIDES_JS);
     *   - hard cap: fire 8s after injection no matter what;
     *   - Java-side onPageFinished fallback timer.
     *
     * Runs in the MAIN frame only (window.top check + hostname gate), and
     * only on the main WebView — popups never register it. Pure polling at
     * 200ms (no MutationObserver): each tick is a couple of querySelectors
     * plus a layout read, ~5x/sec, cheaper than observer-driven layout
     * thrash during hydration.
     */
    public static final String PAGE_READY_WATCHER_JS = "(function(){" +
            "  try {" +
            "    if (window.top !== window) return;" +
            "    var hst = location.hostname || '';" +
            "    if (hst.indexOf('chatgpt.com') < 0 && hst.indexOf('openai.com') < 0) return;" +
            "    if (window._webgptReadyWatch) return;" +
            "    window._webgptReadyWatch = true;" +
            "    var sent = false, started = Date.now();" +
            "    function fire(){" +
            "      if (sent) return;" +
            "      sent = true;" +
            "      /* The load tracker only exists to decide when the initial SPA is ready. */" +
            "      /* Leaving a subtree+attributes+characterData observer alive makes every */" +
            "      large composer edit pay observer bookkeeping for the lifetime of the chat. */" +
            "      try { var L=window.__webgptLoad; if(L&&L.mo){L.mo.disconnect();L.mo=null;} } catch(e) {}" +
            "      try {" +
            "        if (window.AndroidBridge && window.AndroidBridge.pageReady)" +
            "          window.AndroidBridge.pageReady();" +
            "      } catch(e) {}" +
            "    }" +
            "    function vis(el){" +
            "      try {" +
            "        var r = el && el.getClientRects();" +
            "        /* rendered AND non-zero size: on mobile the greeting wrapper" +
            "           itself is NOT display:none (only its inner child carries" +
            "           hidden sm:block), so it still lays out as an EMPTY flex" +
            "           box — a zero-height client rect. length>0 alone would" +
            "           mistake that for a visible greeting. */" +
            "        return !!(r && r.length && r[0].height > 0 && r[0].width > 0);" +
            "      }" +
            "      catch(e) { return false; }" +
            "    }" +
            "    function tick(){" +
            "      if (sent) return true;" +
            "      var now = Date.now();" +
            "      if (now - started > 8000) { fire(); return true; }" +
            "      try {" +
            "        var box = document.getElementById('prompt-textarea')" +
            "               || document.querySelector('div[contenteditable=\"true\"][role=\"textbox\"]');" +
            "        if (box) {" +
            "          if (vis(document.querySelector('[data-splash-headline-option]'))) { fire(); return true; }" +
            "          if (vis(document.querySelector('[data-testid=\"use-case-prompt-chips\"] button'))) { fire(); return true; }" +
            "          if (document.querySelector('[data-testid^=\"conversation-turn\"]')) { fire(); return true; }" +
            "          var L = window.__webgptLoad;" +
            "          if (L && (now - L.lastMut > 2000) && (now - L.lastStart > 1500)) { fire(); return true; }" +
            "        }" +
            "      } catch(e) {}" +
            "      return false;" +
            "    }" +
            "    var iv = setInterval(function(){ if (tick()) clearInterval(iv); }, 200);" +
            "  } catch(e) {}" +
            "})();";

    /**
     * Focus guard — suppresses chatgpt.com's programmatic .focus() calls on
     * the composer when the user is browsing an EXISTING chat (URL path
     * starts with /c/ or /g/), so the soft keyboard does NOT auto-open on
     * page load / SPA remount / visibility-resume. The user's TAP on the
     * composer still focuses it (that path goes through C++
     * Element::focus, not the JS prototype) so input still works.
     *
     * Bypassed for ~6 seconds after a share intent lands (markShareActive
     * sets window.__webgptShareActiveUntil), so the share pipeline's own
     * focus() calls still work to commit the attachment.
     *
     * Backup mechanisms on top of the prototype patch:
     *  - document 'focusin' listener (capture phase): blurs the composer
     *    if the focus event is programmatic (not isTrusted) and no share
     *    is active.
     *  - 'visibilitychange' listener: blurs the composer on hidden (the
     *    JS thread is paused while hidden, so the blur is durable across
     *    resume — the SPA's resume-time .focus() can't race with it).
     *  - setInterval(stripAutofocus, 500): SPA remounts can re-add the
     *    autofocus attribute; strip it so even the C++ focus path
     *    doesn't auto-focus.
     *
     * All checks are host-gated to chatgpt.com / openai.com and
     * frame-gated to window.top === window so iframe shares / blob:
     * origins never see the guard.
     *
     * History — v1 (v6.24.21, Round 21) used a focusin listener with a
     * 50ms cooldown and the blur deferred via setTimeout(0). That failed
     * because chatgpt.com's React/SPA kept re-calling composer.focus()
     * in rAF/microtasks: by the time our deferred blur fired, the SPA
     * had already re-focused. The 50ms cooldown was supposed to break
     * the loop but it actually became the escape valve that let the
     * keyboard win after a few cycles — visible as a blinking caret
     * (the intermittent "|" the user reported) and the "opens → closes →
     * opens again" cycle. v2 (v6.24.22, Round 24) kills the loop at the
     * source by monkey-patching the prototype: programmatic .focus() on
     * the composer becomes a no-op, so no focus event ever fires, no
     * blur is needed, and there's no cooldown to leak through. The 3s
     * resume threshold from v1 was also dropped — the user explicitly
     * did not want the keyboard to reopen on return to an existing chat
     * at all, even after a long absence.
     */
    public static final String FOCUS_GUARD_JS = "(function(){  if (window.__webgptFocusGuard) return;  window.__webgptFocusGuard = true;  try { if (window.top !== window) return; } catch(e) { return; }  function hostOk(){ try { var h=(location.hostname||'').toLowerCase();    return h==='chatgpt.com'||h==='chat.openai.com'||h==='openai.com'      || h.endsWith('.chatgpt.com')||h.endsWith('.openai.com'); } catch(e){ return false; } }  if (!hostOk()) return;  function isExistingChat(){ try { var p=location.pathname||'';    return p.indexOf('/c/')===0 || (p.indexOf('/g/')===0 && p.length>3); } catch(e){ return false; } }  function isComposer(el){ if(!el||!el.matches) return false;    try { if(el.id==='prompt-textarea') return true;      if(el.closest && el.closest('#prompt-textarea')) return true;      if(el.matches('div[contenteditable=\"true\"][role=\"textbox\"]')) return true;      if(el.tagName==='TEXTAREA') return true; return false; } catch(e){ return false; } }  function shareActive(){ try { return Date.now() < (window.__webgptShareActiveUntil||0); } catch(e){ return false; } }  function shouldSuppress(el){ try { return isExistingChat() && !shareActive() && isComposer(el); } catch(e){ return false; } }  try {    var origFocus = HTMLElement.prototype.focus;    HTMLElement.prototype.focus = function(){      try { if (shouldSuppress(this)) return; } catch(e){}      return origFocus.apply(this, arguments);    };  } catch(e){}  document.addEventListener('focusin', function(e){    try {      if (shareActive()||!isExistingChat()) return;      var t=e.target; if(!isComposer(t)) return;      if (e.isTrusted) return;      try { t.blur(); } catch(_){}    } catch(_){}  }, true);  document.addEventListener('visibilitychange', function(){    try {      if (document.visibilityState==='hidden'){        if (shareActive()||!isExistingChat()) return;        var ae=document.activeElement;        if (ae && isComposer(ae)){ try{ ae.blur(); }catch(_){} }        return;      }      if (document.visibilityState!=='visible') return;      if (shareActive()||!isExistingChat()) return;      var ae2=document.activeElement;      if (ae2 && isComposer(ae2)){ try{ ae2.blur(); }catch(_){} }    } catch(_){}  });  try {    var stripAutofocus = function(){ try {      var sels=['#prompt-textarea[autofocus]',        'div[contenteditable=\"true\"][role=\"textbox\"][autofocus]',        'textarea[autofocus]'];      for (var i=0;i<sels.length;i++){        var els=document.querySelectorAll(sels[i]);        for (var j=0;j<els.length;j++){          try{ els[j].removeAttribute('autofocus'); }catch(_){}        }      }    } catch(e){} };    setInterval(stripAutofocus, 500);  } catch(e){}})();";


    /**
     * DNS-boundary check for allowed hosts. Accepts exactly the listed domain
     * or any subdomain of it (e.g. "chatgpt.com" or "auth.openai.com"), but
     * NOT unrelated domains like "evilchatgpt.com".
     */
    public static boolean isAllowedHost(String host) {
        if (host == null) return false;
        // NOTE: bare "auth0.com" was deliberately removed — OpenAI's login
        // runs on auth.openai.com (covered by the openai.com entry), while
        // *.auth0.com hosts arbitrary third-party tenants we must not trust.
        //
        // The google.com / googleusercontent.com / gstatic.com entries are
        // REQUIRED for the "Continue with Google" OAuth chain: after
        // accounts.google.com the account picker and consent screens hop
        // through myaccount.google.com, www.google.com and
        // oauthaccount.googleusercontent.com before landing back on
        // auth.openai.com. Without these entries every one of those hops
        // was routed to the external browser mid-login (the F-Droid review
        // symptom: "app crashes when clicking any login button" — the
        // popup teardown that followed that routing crashed the app on
        // Android 15). Same set the Gemini-based sibling app ships.
        String[] allowed = {
            "chatgpt.com",
            "openai.com",
            "accounts.google.com",
            "google.com",
            "googleusercontent.com",
            "gstatic.com"
        };
        for (String domain : allowed) {
            if (host.equals(domain) || host.endsWith("." + domain)) {
                return true;
            }
        }
        return false;
    }


    /**
     * Validated wallpaper-passthrough page treatment from MyChatGPT-Prototype
     * v0.3. Keep ChatGPT-specific surface selectors here rather than in native
     * shell code.
     */
    public static final String WALLPAPER_TRANSPARENCY_JS =
            "(function(){try{" +
            "var css='html,body,#__next,main,main>div,[data-nextjs-scroll-focus-boundary]," +
            "[class*=\\'bg-token-main-surface-primary\\']," +
            "[class*=\\'bg-token-main-surface-secondary\\']," +
            "[class*=\\'bg-token-sidebar-surface-primary\\']{" +
            "background:transparent!important;background-color:transparent!important;}" +
            "html,body{min-height:100%!important;}';" +
            "var id='mychatgpt-wallpaper-css';" +
            "var d=document;var root=d.documentElement;if(!root)return;" +
            "var style=d.getElementById(id);" +
            "if(!style){style=d.createElement('style');style.id=id;" +
            "(d.head||root).appendChild(style);}" +
            "style.textContent=css;" +
            "root.style.setProperty('background-color','transparent','important');" +
            "if(d.body)d.body.style.setProperty('background-color','transparent','important');" +
            "}catch(e){}})();";


    /**
     * Large-paste accelerator for the ChatGPT composer.
     *
     * Chromium's legacy execCommand('insertText') path becomes extremely slow
     * for multi-kilobyte contenteditable inserts. For large plain-text pastes,
     * intercept the user paste before the site/browser fallback, update the
     * active selection directly, then dispatch one input event so ChatGPT can
     * synchronize its editor state. Small pastes stay on the site's normal path.
     */
    public static final String LARGE_PASTE_ACCELERATOR_JS =
            "(function(){try{" +
            "if(window.__mychatgptLargePaste)return;window.__mychatgptLargePaste=true;" +
            "if(window.top!==window)return;" +
            "var LARGE_PASTE_MIN=2048;" +
            "var handling=false;" +
            "function composer(el){try{" +
            "if(!el)return null;" +
            "if(el.id==='prompt-textarea')return el;" +
            "if(el.closest){var c=el.closest('#prompt-textarea');if(c)return c;}" +
            "if(el.matches&&el.matches('div[contenteditable=\\\"true\\\"][role=\\\"textbox\\\"]'))return el;" +
            "if(el.closest){var r=el.closest('div[contenteditable=\\\"true\\\"][role=\\\"textbox\\\"]');if(r)return r;}" +
            "if(el.tagName==='TEXTAREA'||el.tagName==='INPUT')return el;" +
            "return null;}catch(e){return null;}}" +
            "function directInsert(el,text,ev){try{" +
            "if(!el||!text||text.length<LARGE_PASTE_MIN||handling)return false;" +
            "handling=true;" +
            "try{ev.preventDefault();}catch(_){}" +
            "try{ev.stopImmediatePropagation();}catch(_){}" +
            "try{ev.stopPropagation();}catch(_){}" +
            "try{el.focus({preventScroll:true});}catch(_){try{el.focus();}catch(__){}}" +
            "if(el.tagName==='TEXTAREA'||el.tagName==='INPUT'){" +
            "var a=(typeof el.selectionStart==='number')?el.selectionStart:(el.value||'').length;" +
            "var b=(typeof el.selectionEnd==='number')?el.selectionEnd:a;" +
            "var old=String(el.value||'');var next=old.slice(0,a)+text+old.slice(b);" +
            "var proto=Object.getPrototypeOf(el);" +
            "var desc=proto&&Object.getOwnPropertyDescriptor(proto,'value');" +
            "if(desc&&desc.set)desc.set.call(el,next);else el.value=next;" +
            "try{el.setSelectionRange(a+text.length,a+text.length);}catch(_){}" +
            "}else{" +
            "var s=window.getSelection();" +
            "if(!s||!s.rangeCount||!el.contains(s.anchorNode)){" +
            "var er=document.createRange();er.selectNodeContents(el);er.collapse(false);" +
            "s=window.getSelection();s.removeAllRanges();s.addRange(er);" +
            "}" +
            "var rr=(s&&s.rangeCount)?s.getRangeAt(0):null;" +
            "if(!rr)return false;" +
            "rr.deleteContents();" +
            "var node=document.createTextNode(text);rr.insertNode(node);" +
            "rr.setStartAfter(node);rr.collapse(true);s.removeAllRanges();s.addRange(rr);" +
            "}" +
            "el.dispatchEvent(new InputEvent('input',{bubbles:true,inputType:'insertText',data:text}));" +
            "return true;" +
            "}catch(e){return false;}finally{handling=false;}}" +
            "document.addEventListener('paste',function(e){try{" +
            "var el=composer(e.target);if(!el)return;" +
            "var text='';" +
            "try{if(e.clipboardData)text=e.clipboardData.getData('text/plain')||'';}catch(_){}" +
            "if(text.length>=LARGE_PASTE_MIN)directInsert(el,text,e);" +
            "}catch(_){}},true);" +
            "document.addEventListener('beforeinput',function(e){try{" +
            "var el=composer(e.target);if(!el||handling)return;" +
            "var type=String(e.inputType||'');var text='';" +
            "try{if(e.dataTransfer)text=e.dataTransfer.getData('text/plain')||'';}catch(_){}" +
            "if(!text&&typeof e.data==='string')text=e.data;" +
            "if(text.length<LARGE_PASTE_MIN)return;" +
            "if(type==='insertFromPaste'||type==='insertText')directInsert(el,text,e);" +
            "}catch(_){}},true);" +
            "}catch(e){}})();";

    // Java-side polling / attachment timings inherited from WebGPT's
    // ChatGPT-specific integration. Keep these together with the selectors
    // and injected scripts so site tuning does not leak back into Activity.
    public static final int COMPOSER_READY_MAX_WAIT_MS = 25000;
    public static final int COMPOSER_READY_POLL_MS = 700;
    public static final int PAGE_FINISHED_READY_FALLBACK_MS = 3500;
    public static final int FILE_CHOOSER_RETRIGGER_GUARD_MS = 4000;
    public static final int FILE_INJECTION_BASE64_CHUNK_SIZE = 524288;
    public static final int ATTACHMENT_VERIFY_DELAY_MS = 3000;
    public static final int BLOB_DOWNLOAD_TIMEOUT_MS = 30000;

    public static final int AUTO_ATTACH_FOCUS_DELAY_1_MS = 200;
    public static final int AUTO_ATTACH_FOCUS_DELAY_2_MS = 500;
    public static final int AUTO_ATTACH_FOCUS_DELAY_3_MS = 900;
    public static final int AUTO_ATTACH_FOCUS_DELAY_4_MS = 1400;

    public static final int DROP_REFOCUS_DELAY_1_MS = 500;
    public static final int DROP_REFOCUS_DELAY_2_MS = 1500;
    public static final int DROP_REFOCUS_DELAY_3_MS = 2500;
    public static final int DROP_KEYBOARD_DELAY_1_MS = 800;
    public static final int DROP_KEYBOARD_DELAY_2_MS = 2000;

    public static final String COMPOSER_READY_PROBE_JS =
"(function(){"
                            + "var el=!!(document.querySelector('#prompt-textarea')"
                            + "||document.querySelector('div[contenteditable=\"true\"][role=\"textbox\"]')"
                            + "||document.querySelector('div[contenteditable=\"true\"]')"
                            + "||document.querySelector('textarea[placeholder]'));"
                            + "var hEl=document.querySelector('[data-splash-headline-option]');"
                            + "var cEl=document.querySelector('[data-testid=\"use-case-prompt-chips\"] button');"
                            + "function vis(e){try{var r=e&&e.getClientRects();return !!(r&&r.length&&r[0].height>0&&r[0].width>0)}catch(x){return false}}"
                            + "var mk=!!((hEl&&vis(hEl))||(cEl&&vis(cEl)));"
                            + "var L=window.__webgptLoad;"
                            + "if(!L) return el;"
                            + "var now=Date.now();"
                            + "var domAge=now-L.lastMut, netAge=now-L.lastStart;"
                            + "return (el && (mk || (domAge>2000 && netAge>1500)))"
                            + " ? 'ready|'+domAge+'|'+netAge : 'wait|'+domAge+'|'+netAge;"
                            + "})();";

    public static final String FILE_BUFFER_RESET_JS = "window.__webgptFileB64='';";

    public static String appendFileBufferJs(String chunk) {
        return "(function(){window.__webgptFileB64=(window.__webgptFileB64||'')+'" + chunk + "';})();";
    }

    public static String buildFileDropJs(String safeName, String safeMime) {
        return "(function(){" +
                "  try {" +
                "    window.__webgptShareActiveUntil = Date.now() + 6000;" +
                "    var b64 = window.__webgptFileB64 || '';" +
                "    window.__webgptFileB64 = null;" +
                "    var AB = window.AndroidBridge;" +
                "    if (!b64) { if (AB && AB.onFileDropResult) AB.onFileDropResult(false, 'no data'); return; }" +
                "    var bin = atob(b64);" +
                "    var n = bin.length;" +
                "    var bytes = new Uint8Array(n);" +
                "    for (var i = 0; i < n; i++) bytes[i] = bin.charCodeAt(i);" +
                "    var file = new File([bytes], '" + safeName + "', {type: '" + safeMime + "'});" +
                "    var dt = new DataTransfer();" +
                "    dt.items.add(file);" +
                "    var el = document.querySelector('#prompt-textarea')" +
                "          || document.querySelector('div[contenteditable=\"true\"][role=\"textbox\"]')" +
                "          || document.querySelector('div[contenteditable=\"true\"]')" +
                "          || document.querySelector('textarea[placeholder]');" +
                "    var input = null;" +
                "    try {" +
                "      var ins = document.querySelectorAll('input[type=file]');" +
                "      for (var i = 0; i < ins.length; i++) { input = ins[i]; break; }" +
                "    } catch (e) {}" +
                "    if (input) {" +
                "      try { input.files = dt.files; } catch (e) { try { Object.getOwnPropertyDescriptor(window.HTMLInputElement.prototype, 'files').set.call(input, dt.files); } catch (e2) {} }" +
                "      try { input.dispatchEvent(new Event('input', {bubbles: true})); } catch (e) {}" +
                "      try { input.dispatchEvent(new Event('change', {bubbles: true})); } catch (e) {}" +
                "      if (el) { try { el.focus({preventScroll: true}); } catch (e) {} }" +
                "      if (AB && AB.onFileDropResult) AB.onFileDropResult(true, 'input ' + n + ' bytes');" +
                "      return;" +
                "    }" +
                "    if (!el) { if (AB && AB.onFileDropResult) AB.onFileDropResult(false, 'no composer'); return; }" +
                /* FALLBACK — drag events (only when no file input exists).
                 * Synthetic dragenter/dragover can leave the site's drop
                 * overlay stuck, so fire document drop/dragend/dragleave and
                 * a page-level Escape afterwards to tear it down. */
                "    try { el.dispatchEvent(new DragEvent('dragenter', {bubbles: true, cancelable: true, dataTransfer: dt})); } catch (e) {}" +
                "    try { el.dispatchEvent(new DragEvent('dragover', {bubbles: true, cancelable: true, dataTransfer: dt})); } catch (e) {}" +
                "    try { el.dispatchEvent(new DragEvent('drop', {bubbles: true, cancelable: true, dataTransfer: dt})); } catch (e) {}" +
                "    try { document.dispatchEvent(new DragEvent('drop', {bubbles: true, cancelable: true, dataTransfer: dt})); } catch (e) {}" +
                "    try { document.dispatchEvent(new DragEvent('dragend', {bubbles: true, cancelable: true})); } catch (e) {}" +
                "    try { document.dispatchEvent(new DragEvent('dragleave', {bubbles: true, cancelable: true})); } catch (e) {}" +
                "    try { el.focus({preventScroll: true}); } catch (e) {}" +
                "    setTimeout(function(){" +
                "      try { document.dispatchEvent(new KeyboardEvent('keydown', {key: 'Escape', code: 'Escape', keyCode: 27, which: 27, bubbles: true})); } catch (e) {}" +
                "      try { document.dispatchEvent(new KeyboardEvent('keyup', {key: 'Escape', code: 'Escape', keyCode: 27, which: 27, bubbles: true})); } catch (e) {}" +
                "      try { el.focus({preventScroll: true}); } catch (e) {}" +
                "    }, 350);" +
                "    if (AB && AB.onFileDropResult) AB.onFileDropResult(true, 'dropped ' + n + ' bytes');" +
                "  } catch (e) {" +
                "    try { var AB2 = window.AndroidBridge; if (AB2 && AB2.onFileDropResult) AB2.onFileDropResult(false, String(e)); } catch (e2) {}" +
                "  }" +
                "})();";
    }

    public static String buildAttachmentVisibilityJs(String jsName) {
        return "(function(){"
                + "var name='" + jsName + "';"
                + "var nodes=document.querySelectorAll('[data-testid*=attachment i],[class*=attachment i],[aria-label*=file i],div,span,button');"
                + "for(var i=0;i<nodes.length;i++){"
                + "  var t=((nodes[i].textContent||'')+' '+(nodes[i].getAttribute('aria-label')||''));"
                + "  if(t.indexOf(name)>=0 && t.length<300) return 'visible';"
                + "}"
                + "return 'gone';"
                + "})();";
    }

    public static String buildBlobFetchJs(String blobUrl) {
        return "(function(){" +
                "  function fail(){ try { window.AndroidBridge && AndroidBridge.onBlobFailed && AndroidBridge.onBlobFailed(); } catch(e) {} }" +
                "  try {" +
                "    fetch('" + blobUrl.replace("'", "\\'") + "')" +
                "      .then(function(r){ return r.blob(); })" +
                "      .then(function(b){" +
                "        var fr = new FileReader();" +
                "        fr.onloadend = function(){" +
                "          try {" +
                "            if (window.AndroidBridge && AndroidBridge.onBlobResult) { AndroidBridge.onBlobResult(String(fr.result)); }" +
                "            else { fail(); }" +
                "          } catch(e) { fail(); }" +
                "        };" +
                "        fr.onerror = fail;" +
                "        fr.readAsDataURL(b);" +
                "      })" +
                "      .catch(fail);" +
                "  } catch(e) { fail(); }" +
                "})();";
    }

    public static final String MARK_SHARE_ACTIVE_JS =
            "try{window.__webgptShareActiveUntil=Date.now()+6000;}catch(e){}";

    public static final String COMPOSER_FOCUS_JS =
"(function(){" +
                "  try {" +
                "    window.__webgptShareActiveUntil = Date.now() + 6000;" +
                "    var el = document.querySelector('#prompt-textarea')" +
                "          || document.querySelector('div[contenteditable=\"true\"][role=\"textbox\"]')" +
                "          || document.querySelector('div[contenteditable=\"true\"]')" +
                "          || document.querySelector('textarea[placeholder]');" +
                "    var AB = window.AndroidBridge;" +
                "    if (!el) { try { if (AB && AB.debugLog) AB.debugLog('composer NOT FOUND'); } catch (e) {} return; }" +
                "    try { if (AB && AB.debugLog) AB.debugLog('composer focus: ' + (el.id || el.getAttribute('data-testid') || el.tagName)); } catch (e) {}" +
                "    try { el.focus({preventScroll: true}); } catch (e) { try { el.focus(); } catch (e2) {} }" +
                "    try { el.dispatchEvent(new Event('focus', {bubbles: true})); } catch (e) {}" +
                "    try { el.dispatchEvent(new Event('focusin', {bubbles: true})); } catch (e) {}" +
                "  } catch (e) {}" +
                "})();";

}
