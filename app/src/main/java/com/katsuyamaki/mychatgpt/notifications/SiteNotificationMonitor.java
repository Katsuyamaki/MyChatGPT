package com.katsuyamaki.mychatgpt.notifications;

/**
 * Watches transient ChatGPT toast/alert elements in the MAIN document only.
 * No service-worker push is claimed: notices are caught while the page runs.
 */
public final class SiteNotificationMonitor {
    private SiteNotificationMonitor() {}
    public static final String SCRIPT =
            "(function(){\n  try {\n    if (window.top !== window) return;\n    var host = location.hostname || '';\n    if (ho" +
            "st !== 'chatgpt.com' && !host.endsWith('.chatgpt.com')) return;\n    if (window.__mychatgptNotificationWatch) r" +
            "eturn;\n    window.__mychatgptNotificationWatch = true;\n    var selectors = '[data-sonner-toast],[data-hot-toas" +
            "t],.Toastify__toast,[data-testid*=\"toast\"],[role=\"alert\"],[role=\"status\"][data-state=\"open\"]';\n    var pending" +
            " = new Set(), sent = new WeakSet(), timer = 0;\n    function enqueue(node) {\n      if (node && node.nodeType ==" +
            "= 1) pending.add(node);\n      if (!timer) timer = setTimeout(flush, 170);\n    }\n    function find(node) {\n    " +
            "  var el = node && node.nodeType === 1 ? node : node && node.parentElement;\n      if (!el) return;\n      var p" +
            "arent = el.closest && el.closest(selectors);\n      if (parent) enqueue(parent);\n      if (el.matches && el.mat" +
            "ches(selectors)) enqueue(el);\n      if (el.querySelectorAll) {\n        var found = el.querySelectorAll(selecto" +
            "rs);\n        for (var i = 0; i < found.length && i < 20; i++) enqueue(found[i]);\n      }\n    }\n    function fl" +
            "ush() {\n      timer = 0;\n      pending.forEach(function(el) {\n        try {\n          if (!el.isConnected || !" +
            "el.getClientRects().length) return;\n          var rect = el.getBoundingClientRect();\n          if (rect.width " +
            "< 2 || rect.height < 2) return;\n          if (rect.height > innerHeight * 0.55) return;\n          var raw = (e" +
            "l.innerText || el.textContent || '').replace(/\\s+/g, ' ').trim();\n          if (raw.length < 3 || raw.length >" +
            " 1200) return;\n          if (sent.has(el)) return;\n          sent.add(el);\n          var anchor =" +
            " el.querySelector('a[href*=\"/c/\"]');\n          var dest = anchor && anchor.href ? anchor.href : '';\n          " +
            "if (!dest && /(?:^|\\/)c\\/[A-Za-z0-9-]{8,128}(?:\\/|$)/.test(location.pathname)) {\n            dest = location.h" +
            "ref;\n          }\n          var bridge = window.AndroidBridge;\n          if (bridge && bridge.siteNotification)" +
            " bridge.siteNotification(raw, dest);\n        } catch (ignored) {}\n      });\n      pending.clear();\n    }\n    v" +
            "ar observer = new MutationObserver(function(records) {\n      for (var i = 0; i < records.length && i < 120; i+" +
            "+) {\n        var record = records[i];\n        if (record.target && record.target.closest) {\n          var cont" +
            "ainer = record.target.closest(selectors);\n          if (container) enqueue(container);\n        }\n        var a" +
            "dded = record.addedNodes;\n        for (var j = 0; j < added.length && j < 40; j++) find(added[j]);\n      }\n   " +
            " });\n    observer.observe(document, {childList:true, subtree:true});\n    if (document.readyState !== 'loading'" +
            ") find(document.documentElement);\n    else document.addEventListener('DOMContentLoaded', function(){find(docum" +
            "ent.documentElement);}, {once:true});\n  } catch (ignored) {}\n})();";
}
