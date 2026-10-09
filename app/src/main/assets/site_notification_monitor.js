(function () {
  'use strict';
  // Only the top-level ChatGPT document can report real site events.
  try {
    if (window.self !== window.top) return;
    var host = (location.hostname || '').toLowerCase();
    if (host !== 'chatgpt.com' && !host.endsWith('.chatgpt.com')) return;

    var older = window.__mychatgptSiteNotificationMonitor;
    if (older && older.version === 3 && older.active) {
      older.scan();
      older.report(true);
      return;
    }
    if (older && typeof older.disconnect === 'function') older.disconnect();

    var STRONG = [
      '[data-sonner-toast]', '[data-hot-toast]', '.Toastify__toast',
      '[data-radix-toast]', '[data-slot="toast"]',
      '[data-slot="toast-item"]', '[data-notification-toast]',
      '[data-testid*="toast" i]', '[data-testid*="snackbar" i]',
      '[class*="toast" i]', '[class*="snackbar" i]'
    ].join(',');
    var SEMANTIC = '[role="alert"],[role="status"],[aria-live="assertive"],[aria-live="polite"]';
    var CHAT = [
      '[data-testid^="conversation-turn-"]',
      '[data-message-author-role]', '[data-turn="assistant"]', '[data-turn="user"]',
      '#prompt-textarea', '[contenteditable="true"]', 'textarea', 'input',
      '[role="log"]', 'nav', '[role="navigation"]', '[role="tooltip"]'
    ].join(',');
    var STOP = [
      'button[data-testid="stop-button"]',
      'button[aria-label="Stop generating"]',
      'button[aria-label="Stop response"]',
      'button[aria-label="回答を停止"]', 'button[aria-label="停止"]'
    ].join(',');
    var STREAM = '[data-is-streaming="true"],[data-stream-active="true"]';
    var ASSISTANT = '[data-message-author-role="assistant"],[data-turn="assistant"]';
    var FINISHED = [
      'button[data-testid="copy-turn-action-button"]',
      'button[aria-label="Copy response"]', 'button[aria-label="Regenerate response"]',
      '[data-markdown-copy]'
    ].join(',');
    var pending = new Map();
    var delivered = new WeakMap();
    var timer = 0, stateTimer = 0, pulseAt = 0, lastText = '';
    var stats = { mutations: 0, candidates: 0, floating: 0, sent: 0,
                  generating: 0, completed: 0 };
    var observer;
    var response = { active: false, route: '', since: 0, finishing: 0,
                     interrupted: false, generation: 0, lastStop: 0,
                     startingIdentity: '', startingLength: 0 };
    var monitor = { version: 3, active: false };

    function bridge() { return window.AndroidBridge; }
    function report(force) {
      try {
        var b = bridge();
        if (!b) return;
        if (b.siteMonitorReady) b.siteMonitorReady();
        if (b.siteMonitorMetrics && (force || Date.now() - pulseAt > 1900)) {
          pulseAt = Date.now();
          b.siteMonitorMetrics(stats.mutations, stats.candidates,
                               stats.floating, stats.sent,
                               stats.generating, stats.completed);
        }
      } catch (ignored) {}
    }
    monitor.report = report;
    function shortText(el, max) {
      if (!el) return '';
      var s = (el.innerText || el.textContent || '').replace(/\s+/g, ' ').trim();
      return s.length >= 3 && s.length <= max ? s : '';
    }
    function visible(el) {
      if (!el || !el.isConnected) return false;
      if (!el.getClientRects().length) return false;
      var r = el.getBoundingClientRect();
      if (r.width < 14 || r.height < 8 || r.bottom < 0 || r.top > innerHeight) return false;
      if (r.height > Math.max(245, innerHeight * 0.60)) return false;
      var css = getComputedStyle(el);
      if (css.display === 'none' || css.visibility === 'hidden') return false;
      if (Number(css.opacity) === 0) return false;
      if (el.closest('[aria-hidden="true"],[hidden]')) return false;
      return true;
    }
    function blocked(el) {
      return !!(el && el.closest && el.closest(CHAT));
    }
    function metadata(el) {
      if (!el) return '';
      return [el.id || '', typeof el.className === 'string' ? el.className : '',
              el.getAttribute('data-testid') || '', el.getAttribute('data-slot') || '',
              el.getAttribute('aria-label') || ''].join(' ').slice(0, 300).toLowerCase();
    }
    function named(el) {
      return /toast|snackbar|flash[-_ ]?message|notification[-_ ]?(?:toast|banner|message|popup)|alert[-_ ]?banner/.test(metadata(el));
    }
    function nearEdge(r) {
      return r.top < innerHeight * 0.38 || r.bottom > innerHeight * 0.62;
    }
    function compact(el, max, requireEdge) {
      if (!visible(el) || blocked(el)) return false;
      var r = el.getBoundingClientRect();
      return r.width <= innerWidth * 1.04 && r.height < Math.min(260, innerHeight * 0.54)
          && (!requireEdge || nearEdge(r)) && !!shortText(el, max)
          && !el.querySelector('textarea,input,[contenteditable="true"]');
    }
    function floatingCandidate(el) {
      if (!el || !el.isConnected || blocked(el)) return null;
      var chain = [];
      for (var a = el, n = 0; a && n < 8; a = a.parentElement, n++) {
        if (a === document.body || a === document.documentElement || a.tagName === 'MAIN') break;
        if (blocked(a)) return null;
        chain.push(a);
      }
      var floating = -1;
      for (var i = 0; i < chain.length; i++) {
        var style = getComputedStyle(chain[i]);
        var z = parseInt(style.zIndex, 10);
        if (style.position === 'fixed' || style.position === 'sticky' ||
            (style.position === 'absolute' && isFinite(z) && z >= 15)) {
          floating = i;
          break;
        }
      }
      if (floating < 0) return null;
      // Pick a small text-bearing card under a floating portal, not the
      // fullscreen portal itself. This catches unlabeled ChatGPT popups.
      var choice = null;
      for (var j = 0; j <= floating; j++) {
        var candidate = chain[j], css = getComputedStyle(candidate);
        if (!compact(candidate, 380, true)) continue;
        var skin = css.backgroundColor !== 'rgba(0, 0, 0, 0)' &&
                   css.backgroundColor !== 'transparent';
        var shadow = css.boxShadow && css.boxShadow !== 'none';
        var cls = metadata(candidate);
        if (!skin && !shadow && !/rounded|border|shadow|pointer-events-auto/.test(cls)) continue;
        if (candidate.closest('[role="dialog"],[aria-modal="true"]') &&
            !named(candidate)) continue;
        if (candidate.matches('button,[role="button"],[role="menu"],[role="menuitem"]')) continue;
        choice = candidate;
      }
      return choice;
    }
    function match(el) {
      if (!el || !el.closest || blocked(el)) return null;
      try {
        var direct = el.closest(STRONG);
        if (direct && !blocked(direct)) return { el: direct, kind: 'marked' };
        for (var node = el, depth = 0; node && depth < 8; depth++, node = node.parentElement) {
          if (node === document.body || node === document.documentElement) break;
          if (named(node)) return { el: node, kind: 'named' };
          var role = node.getAttribute('role');
          if (role === 'alert' || role === 'status') return { el: node, kind: 'semantic' };
          var live = node.getAttribute('aria-live');
          if (live === 'assertive' || live === 'polite') {
            var child = el;
            while (child.parentElement && child.parentElement !== node) {
              child = child.parentElement;
            }
            if (child !== node && compact(child, 420, true))
              return { el: child, kind: 'semantic' };
            if (compact(node, 420, true)) return { el: node, kind: 'semantic' };
          }
        }
        var unmarked = floatingCandidate(el);
        if (unmarked) return { el: unmarked, kind: 'floating' };
      } catch (ignored) {}
      return null;
    }
    function enqueue(hit) {
      if (!hit || !hit.el || blocked(hit.el)) return;
      if (!pending.has(hit.el)) {
        pending.set(hit.el, { tries: 0, kind: hit.kind });
        stats.candidates++;
        if (hit.kind === 'floating') stats.floating++;
      }
      if (!timer) timer = setTimeout(flush, 0);
    }
    function inspect(node, deep) {
      var el = node && node.nodeType === 1 ? node : node && node.parentElement;
      if (!el || blocked(el)) return;
      var found = match(el);
      if (found) enqueue(found);
      if (!deep || !el.querySelectorAll) return;
      try {
        var matches = el.querySelectorAll(STRONG + ',' + SEMANTIC);
        for (var i = 0; i < matches.length && i < 28; i++) {
          var candidate = match(matches[i]);
          if (candidate) enqueue(candidate);
        }
      } catch (ignored) {}
    }
    function destination(el) {
      var a = el.querySelector('a[href*="/c/"]');
      if (a && a.href) return a.href;
      return /(?:^|\/)c\/[A-Za-z0-9-]{8,128}(?:\/|$)/.test(location.pathname)
           ? location.href : '';
    }
    function flush() {
      timer = 0;
      var retry = false;
      pending.forEach(function (state, el) {
        pending.delete(el);
        try {
          if (!el.isConnected) return;
          // Explicit toast markup may be centered; unlabeled floating cards
          // and weak semantic live regions require a viewport-edge position.
          var ok = compact(el, state.kind === 'floating' ? 380 : 1200,
                           state.kind === 'semantic' || state.kind === 'floating');
          if (ok && state.kind === 'semantic') {
            var r = el.getBoundingClientRect();
            // A persistent status line can be anywhere on the page; a toast
            // must be in a compact floating region or near the viewport edge.
            ok = nearEdge(r) && (floatingCandidate(el) ||
                 !!el.closest('[data-sonner-toaster],[data-hot-toast]') ||
                 named(el) || getComputedStyle(el).position === 'fixed');
          }
          if (ok && state.kind === 'floating') {
            ok = !!floatingCandidate(el);
          }
          var text = ok ? shortText(el, state.kind === 'floating' ? 380 : 1200) : '';
          if (!text && state.tries < 10) {
            state.tries++;
            pending.set(el, state);
            retry = true;
            return;
          }
          if (!text || /^(loading|working|thinking)\s*(?:\.{1,3}|…)?$/i.test(text)) return;
          var old = delivered.get(el);
          if (old && (old.text === text || old.count >= 2 ||
                      Date.now() - old.at < 1200)) return;
          var b = bridge();
          if (!b || !b.siteNotification) {
            if (state.tries++ < 10) {
              pending.set(el, state);
              retry = true;
            }
            return;
          }
          delivered.set(el, { text: text, count: old ? old.count + 1 : 1, at: Date.now() });
          b.siteNotification(text, destination(el));
          stats.sent++;
          report(true);
        } catch (ignored) {}
      });
      if (retry && !timer) timer = setTimeout(flush, 65);
    }
    function scan() {
      try {
        var nodes = document.querySelectorAll(STRONG + ',' + SEMANTIC);
        for (var i = 0; i < nodes.length && i < 90; i++) {
          var h = match(nodes[i]);
          if (h) enqueue(h);
        }
      } catch (ignored) {}
      scheduleState();
    }
    monitor.scan = scan;

    function route() {
      return /(?:^|\/)c\/[A-Za-z0-9-]{8,128}(?:\/|$)/.test(location.pathname)
             ? location.origin + location.pathname : '';
    }
    function isRendered(el) {
      if (!el || !el.isConnected || !el.getClientRects().length) return false;
      var style = getComputedStyle(el);
      return style.display !== 'none' && style.visibility !== 'hidden';
    }
    function main() {
      return document.querySelector('main');
    }
    function signals() {
      var root = main();
      if (!root) return { busy: false, assistant: null, ready: false, error: false };
      var active = root.querySelectorAll(STOP + ',' + STREAM);
      var busy = false;
      for (var i = 0; i < active.length; i++) {
        if (isRendered(active[i])) { busy = true; break; }
      }
      var assistant = root.querySelectorAll(ASSISTANT);
      var last = assistant.length ? assistant[assistant.length - 1] : null;
      var parent = last && (last.closest('[data-testid^="conversation-turn-"],article[data-turn-id]') || last);
      var finalControl = parent && parent.querySelector(FINISHED);
      var ready = !!(last && isRendered(last) &&
                     ((finalControl && isRendered(finalControl)) ||
                       ((last.getAttribute('data-message-id') ||
                         parent.getAttribute('data-turn-id')) &&
                        shortText(last, 100000))));
      // Some layouts use a request timeline rather than conversation turns.
      if (!last) {
        var timeline = root.querySelectorAll('[data-markdown-copy],button[aria-label="Regenerate response"]');
        ready = !busy && !!timeline.length && isRendered(timeline[timeline.length - 1]);
      }
      var errors = root.querySelectorAll('[role="alert"]');
      var error = false;
      for (var j = 0; j < errors.length; j++) {
        if (isRendered(errors[j]) &&
            /something went wrong|error generating|network error|try again/i.test(
              (errors[j].textContent || '').slice(0, 150))) {
          error = true;
          break;
        }
      }
      var identity = last && (
        last.getAttribute('data-message-id') ||
        (parent && parent.getAttribute('data-turn-id')) ||
        (parent && parent.getAttribute('data-testid'))) || '';
      identity = String(identity).slice(0, 150);
      return { busy: busy, assistant: last, ready: ready, error: error,
               identity: identity, answerLength: last ?
                 (last.textContent || '').length : 0 };
    }
    function scheduleState() {
      if (!stateTimer) stateTimer = setTimeout(checkState, 125);
    }
    function checkState() {
      stateTimer = 0;
      var current = route(), signal = signals(), now = Date.now();
      if (current !== response.route) {
        response.route = current;
        response.active = false;
        response.finishing = 0;
        response.interrupted = false;
      }
      if (!current) return;
      if (signal.busy) {
        if (!response.active) {
          response.active = true;
          response.since = now;
          response.finishing = 0;
          response.interrupted = false;
          response.startingIdentity = signal.identity || '';
          response.startingLength = signal.answerLength || 0;
          response.generation++;
          stats.generating++;
          report(true);
        } else {
          response.finishing = 0;
        }
        return;
      }
      if (!response.active) return;
      if (response.interrupted || now - response.lastStop < 12000) {
        response.active = false;
        response.finishing = 0;
        return;
      }
      if (!response.finishing) response.finishing = now;
      if (now - response.finishing < 1250) {
        if (!stateTimer) stateTimer = setTimeout(checkState, 1350);
        return;
      }
      if (!signal.ready && !signal.assistant) {
        if (now - response.finishing < 5500) {
          stateTimer = setTimeout(checkState, 850);
        } else {
          response.active = false;
        }
        return;
      }
      if (!signal.ready && now - response.finishing < 5500) {
        stateTimer = setTimeout(checkState, 850);
        return;
      }
      if (!signal.ready || signal.error || now - response.since < 250) {
        response.active = false;
        return;
      }
      // A stale completed reply must never be mistaken for a fresh finish.
      if (signal.identity && signal.identity === response.startingIdentity &&
          signal.answerLength === response.startingLength) {
        response.active = false;
        return;
      }
      response.active = false;
      response.finishing = 0;
      var b = bridge();
      if (b && b.chatResponseCompleted) {
        // No conversation or assistant text leaves the web page; only the
        // validated route and a non-content identifier go to Android.
        var key = signal.identity || ('session-' + now + '-' + response.generation);
        b.chatResponseCompleted(current, key);
        stats.completed++;
        report(true);
      }
    }
    function onStopClick(e) {
      var target = e.target;
      var stop = target && target.closest && target.closest(STOP);
      if (stop) {
        response.interrupted = true;
        response.lastStop = Date.now();
        setTimeout(scheduleState, 500);
      }
    }

    function synthetic(marked) {
      if (!document.body) return false;
      var el = document.createElement('div');
      if (marked) {
        el.setAttribute('data-sonner-toast', '');
        el.setAttribute('role', 'status');
      }
      el.textContent = marked
        ? 'MyChatGPT site capture test: ' + new Date().toLocaleTimeString()
        : 'MyChatGPT unmarked capture test: ' + new Date().toLocaleTimeString();
      el.style.cssText = 'position:fixed;top:14px;left:12px;right:12px;z-index:2147483647;'
          + 'padding:14px;color:white;background:#263340;border-radius:12px;'
          + 'font:13px sans-serif;box-shadow:0 4px 12px #0005';
      document.body.appendChild(el);
      setTimeout(function () { try { el.remove(); } catch (ignored) {} }, 2600);
      return true;
    }
    monitor.probe = function () { return synthetic(true); };
    monitor.probeUnmarked = function () { return synthetic(false); };

    observer = new MutationObserver(function (records) {
      stats.mutations += records.length;
      // Recent mutations are most useful during high-frequency streaming.
      var seen = 0;
      for (var i = records.length - 1; i >= 0 && seen < 260; i--, seen++) {
        var r = records[i];
        inspect(r.target, false);
        if (r.type === 'childList') {
          for (var j = 0; j < r.addedNodes.length && j < 28; j++) {
            inspect(r.addedNodes[j], true);
          }
        }
      }
      scheduleState();
      if (Date.now() - pulseAt > 2400) report(false);
    });
    observer.observe(document, {
      childList: true, subtree: true, characterData: true,
      attributes: true,
      // Previous listener missed CSS-only show/hide animation transitions.
      attributeFilter: ['class', 'style', 'hidden', 'aria-hidden', 'aria-live',
                        'role', 'data-state', 'data-visible', 'data-testid',
                        'data-slot', 'data-sonner-toast', 'data-is-streaming',
                        'data-stream-active']
    });
    document.addEventListener('click', onStopClick, true);
    document.addEventListener('visibilitychange', scheduleState);
    monitor.active = true;
    monitor.disconnect = function () {
      monitor.active = false;
      observer.disconnect();
      document.removeEventListener('click', onStopClick, true);
      document.removeEventListener('visibilitychange', scheduleState);
      if (timer) clearTimeout(timer);
      if (stateTimer) clearTimeout(stateTimer);
      pending.clear();
    };
    window.__mychatgptSiteNotificationMonitor = monitor;
    report(true);
    if (document.readyState === 'loading') {
      document.addEventListener('DOMContentLoaded', scan, { once: true });
    } else scan();
    // A state/control can change without a DOM mutation after CSS transitions.
    var periodic = setInterval(function () {
      if (!monitor.active) { clearInterval(periodic); return; }
      checkState();
    }, 1600);
  } catch (failure) {
    // The onPageFinished fallback can retry if document-start had no body.
    try { delete window.__mychatgptSiteNotificationMonitor; } catch (ignored) {}
  }
})();
