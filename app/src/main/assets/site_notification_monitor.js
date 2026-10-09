(function () {
  'use strict';

  // The native bridge is exposed to all WebView frames, but the site observer
  // only runs in the top-level ChatGPT document.
  try {
    if (window.self !== window.top) return;
    var hostname = (location.hostname || '').toLowerCase();
    if (hostname !== 'chatgpt.com' && !hostname.endsWith('.chatgpt.com')) return;

    var previous = window.__mychatgptSiteNotificationMonitor;
    if (previous && previous.version === 2 && previous.active) {
      previous.scan();
      previous.report();
      return;
    }

    var STRONG = [
      '[data-sonner-toast]', '[data-hot-toast]', '.Toastify__toast',
      '[data-radix-toast]', '[data-slot="toast"]', '[data-slot="toast-item"]',
      '[data-testid*="toast" i]', '[data-testid*="snackbar" i]',
      '[data-testid*="notification-toast" i]',
      '[data-notification-toast]', '[role="alertdialog"][data-state="open"]'
    ].join(',');
    var SEMANTIC = '[role="alert"],[role="status"],[aria-live="assertive"],[aria-live="polite"]';
    var BLOCKED = [
      '[data-testid^="conversation-turn"]', '#prompt-textarea',
      '[data-testid*="composer" i]', '[contenteditable="true"]',
      'textarea', 'input', 'nav', '[role="navigation"]',
      '[role="dialog"]', '[aria-modal="true"]',
      '[data-testid*="notification-center" i]'
    ].join(',');
    var pending = new Map();
    var delivered = new WeakMap();
    var timer = 0;
    var observer = null;
    var monitor = { version: 2, active: false };

    function bridge() { return window.AndroidBridge; }
    function report() {
      try {
        var target = bridge();
        if (target && target.siteMonitorReady) target.siteMonitorReady();
      } catch (ignored) {}
    }
    monitor.report = report;

    function metadata(el) {
      var a = '';
      try {
        a = [el.id || '', el.className || '',
             el.getAttribute('data-testid') || '',
             el.getAttribute('data-slot') || '',
             el.getAttribute('aria-label') || ''].join(' ').toLowerCase();
      } catch (ignored) {}
      return a.slice(0, 350);
    }
    function namedToast(el) {
      return /toast|snackbar|flash[-_ ]?message|notification[-_ ]?(?:toast|banner|message|popup)|alert[-_ ]?banner/.test(metadata(el));
    }
    function excluded(el) {
      try {
        return !!(el.closest && el.closest(BLOCKED));
      } catch (ignored) { return true; }
    }
    function isStrong(el) {
      try { return !!(el.matches && el.matches(STRONG)); }
      catch (ignored) { return false; }
    }
    function smallVisible(el) {
      if (!el || !el.isConnected || excluded(el)) return false;
      var bounds = el.getBoundingClientRect();
      if (bounds.width < 15 || bounds.height < 8) return false;
      if (bounds.height > Math.max(240, innerHeight * 0.58)) return false;
      if (bounds.width > innerWidth * 1.03) return false;
      if (bounds.bottom < 0 || bounds.top > innerHeight) return false;
      var css = getComputedStyle(el);
      return css.display !== 'none' && css.visibility !== 'hidden'
        && parseFloat(css.opacity || '1') > 0.02;
    }
    function textOf(el) {
      var value = (el.innerText || el.textContent || '').replace(/\s+/g, ' ').trim();
      return value.length >= 3 && value.length <= 1200 ? value : '';
    }
    function anchoredToCorner(el) {
      var element = el;
      for (var depth = 0; element && depth < 5; depth++, element = element.parentElement) {
        var css = getComputedStyle(element);
        if (css.position === 'fixed' || css.position === 'sticky') {
          var r = el.getBoundingClientRect();
          return r.top < innerHeight * 0.4 || r.bottom > innerHeight * 0.6;
        }
      }
      return false;
    }

    // Match explicit toast markup first. For framework-specific popups, use
    // semantic status regions and notification-like cards, not the whole page.
    function candidate(node) {
      var el = node && node.nodeType === 1 ? node : node && node.parentElement;
      if (!el || excluded(el)) return null;
      try {
        var direct = el.closest(STRONG);
        if (direct) return direct;
        var current = el;
        for (var depth = 0; current && depth < 7; depth++, current = current.parentElement) {
          if (current === document.body || current === document.documentElement) break;
          if (namedToast(current)) return current;
          var role = current.getAttribute('role');
          if (role === 'alert' || role === 'status') return current;
          if ((current.getAttribute('aria-live') === 'polite' ||
               current.getAttribute('aria-live') === 'assertive')) {
            // Avoid recording an entire persistent live-region stack. Prefer
            // the newly inserted toast card directly underneath that region.
            var card = el;
            while (card.parentElement && card.parentElement !== current) {
              card = card.parentElement;
            }
            if (card !== current && card !== el && card.nodeType === 1) return card;
            if (anchoredToCorner(current)) return current;
          }
        }
        // Some sites give no accessibility role to popup cards. Require both
        // ephemeral-looking metadata and a floating visual position.
        if (namedToast(el) && anchoredToCorner(el)) return el;
      } catch (ignored) {}
      return null;
    }

    function enqueue(el) {
      if (!el || el.nodeType !== 1 || excluded(el)) return;
      if (!pending.has(el)) pending.set(el, 0);
      if (!timer) timer = setTimeout(flush, 30);
    }

    function inspect(node, searchDescendants) {
      var el = node && node.nodeType === 1 ? node : node && node.parentElement;
      if (!el) return;
      var match = candidate(el);
      if (match) enqueue(match);
      if (!searchDescendants || !el.querySelectorAll) return;
      try {
        var direct = el.querySelectorAll(STRONG + ',' + SEMANTIC);
        for (var i = 0; i < direct.length && i < 28; i++) {
          var found = candidate(direct[i]);
          if (found) enqueue(found);
        }
      } catch (ignored) {}
    }

    function flush() {
      timer = 0;
      var again = false;
      pending.forEach(function (tries, el) {
        pending.delete(el);
        try {
          if (!el.isConnected) return;
          if (!smallVisible(el)) {
            if (tries < 8) { pending.set(el, tries + 1); again = true; }
            return;
          }
          var value = textOf(el);
          if (!value) {
            if (tries < 8) { pending.set(el, tries + 1); again = true; }
            return;
          }
          var isToast = isStrong(el) || namedToast(el);
          var role = el.getAttribute('role');
          var semantic = role === 'status' || role === 'alert' ||
              el.getAttribute('aria-live') === 'polite' ||
              el.getAttribute('aria-live') === 'assertive';
          // The weak semantic match must look like a short-lived floating
          // popup, not a persistent form validation or chat status message.
          if (!isToast && !(semantic && anchoredToCorner(el))) return;
          if (el.querySelector('textarea,input,[contenteditable="true"]')) return;
          var old = delivered.get(el);
          if (old && (old.text === value || old.count >= 2 ||
              Date.now() - old.at < 1600)) return;

          // Some frameworks show an intermediate "loading" toast on the same
          // node. Give it a short chance to resolve to the useful final notice.
          var type = (el.getAttribute('data-type') || '').toLowerCase();
          if (type === 'loading' && tries < 7) {
            pending.set(el, tries + 1);
            again = true;
            return;
          }

          var anchor = el.querySelector('a[href*="/c/"]');
          var url = anchor && anchor.href ? anchor.href : '';
          if (!url && /(?:^|\/)c\/[A-Za-z0-9-]{8,128}(?:\/|$)/.test(location.pathname)) {
            url = location.href;
          }
          var target = bridge();
          if (!target || !target.siteNotification) {
            if (tries < 8) { pending.set(el, tries + 1); again = true; }
            return;
          }
          target.siteNotification(value, url);
          delivered.set(el, { text: value, at: Date.now(),
              count: old ? old.count + 1 : 1 });
        } catch (ignored) {}
      });
      if (again && !timer) timer = setTimeout(flush, 140);
    }

    function scan() {
      try {
        var nodes = document.querySelectorAll(STRONG + ',' + SEMANTIC);
        for (var i = 0; i < nodes.length && i < 60; i++) {
          var match = candidate(nodes[i]);
          if (match) enqueue(match);
        }
      } catch (ignored) {}
    }
    monitor.scan = scan;

    // Unlike SEND TEST (which starts in Java), this test enters through the
    // page's real MutationObserver -> Java bridge -> SQLite -> Android path.
    monitor.probe = function () {
      if (!document.body) return false;
      var el = document.createElement('div');
      el.setAttribute('data-sonner-toast', '');
      el.setAttribute('role', 'status');
      el.textContent = 'MyChatGPT site capture test: ' + new Date().toLocaleTimeString();
      el.style.cssText = 'position:fixed;top:16px;left:12px;right:12px;z-index:2147483647;'
        + 'padding:14px;color:white;background:#263340;border-radius:12px;'
        + 'font:13px sans-serif;box-shadow:0 4px 12px #0005';
      document.body.appendChild(el);
      setTimeout(function () { try { el.remove(); } catch (ignored) {} }, 2400);
      return true;
    };

    observer = new MutationObserver(function (records) {
      // Process newest mutations first: streaming chat replies can produce
      // large mutation batches, and a newly arrived toast must not be starved.
      var checked = 0;
      for (var i = records.length - 1; i >= 0 && checked < 220; i--, checked++) {
        var mutation = records[i];
        if (mutation.type === 'characterData') {
          inspect(mutation.target, false);
        } else if (mutation.type === 'attributes') {
          inspect(mutation.target, false);
        } else {
          inspect(mutation.target, false);
          var nodes = mutation.addedNodes;
          for (var j = 0; j < nodes.length && j < 24; j++) {
            inspect(nodes[j], true);
          }
        }
      }
    });
    observer.observe(document, {
      subtree: true, childList: true, characterData: true,
      attributes: true,
      attributeFilter: ['role', 'aria-live', 'data-state', 'data-visible',
                        'data-testid', 'data-slot', 'data-sonner-toast']
    });
    monitor.active = true;
    monitor.disconnect = function () {
      monitor.active = false;
      if (timer) clearTimeout(timer);
      if (observer) observer.disconnect();
    };
    window.__mychatgptSiteNotificationMonitor = monitor;
    report();
    if (document.readyState === 'loading') {
      document.addEventListener('DOMContentLoaded', scan, { once: true });
    } else {
      scan();
    }
  } catch (failure) {
    // Reset the sentinel so onPageFinished can retry if document-start ran
    // before the WebView had a usable document or bridge.
    try { delete window.__mychatgptSiteNotificationMonitor; } catch (ignored) {}
  }
})();
