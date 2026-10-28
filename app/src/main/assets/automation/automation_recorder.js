/*
 * Automation Recorder v15
 *
 * Fixes vs v14:
 *  - NEVER touches document.head at install time (it is null at document-start; v14 threw there AFTER setting the
 *    "installed" flag, which left the recorder permanently dead on every page that loaded with the document-start script)
 *  - the "installed" flag is only set after listeners are really attached; a failed install is retried by the next injection
 *  - listeners are bound to window (capture) so pages cannot swallow taps before we see them
 *  - events are delivered to the native bridge IMMEDIATELY (survives instant navigation); the outbox only
 *    exists as a fallback when the bridge is not visible yet
 *  - links are NOT blocked any more: tapping a link records the step and the page navigates normally
 *  - top frame only
 */
(function () {
  "use strict";
  const g = globalThis;
  const VERSION = "v15";
  const DEBUG = true;

  function log() {
    if (!DEBUG) return;
    try { console.log("WebConsole: " + Array.prototype.slice.call(arguments).join(" ")); } catch (_) {}
  }
  function warn() {
    try { console.warn("WebConsole: " + Array.prototype.slice.call(arguments).join(" ")); } catch (_) {}
  }

  try { if (window.top !== window) return; } catch (_) { return; }

  const CFG = { OUTBOX_MAX: 200, PUMP_MS: 300, TAP_MAX_MS: 800, MOVE_PX: 30, POINTER_SCAN_DEPTH: 6 };

  // ---- re-injection: only skip when we are really bound to THIS document ----
  const prev = g.__ddgRecorderState;
  if (prev && prev.active && prev.doc === document && prev.listeners.length > 0) {
    log("already active on this document, skipping re-install (" + prev.version + ")");
    return;
  }
  const carriedPause = !!(prev && prev.isPaused);
  if (prev && typeof prev.stop === "function") { try { prev.stop(); } catch (_) {} }

  const S = {
    version: VERSION, doc: document, active: false, isPaused: carriedPause,
    listeners: [], outbox: [], pumpTimer: null, styleEl: null, onReady: null, stop: null,
  };

  let lastTarget = null;
  let lastTime = 0;

  const INTERACTIVE =
    'input, textarea, select, button, a, summary, [contenteditable=""], [contenteditable="true"], ' +
    '[role="button"], [role="link"], [role="checkbox"], [role="radio"], [role="switch"], ' +
    '[role="combobox"], [role="textbox"], [role="tab"], [role="menuitem"], [role="option"], ' +
    '[tabindex]:not([tabindex="-1"]), [onclick]';
  const CLICK_INPUT_TYPES = ["submit", "button", "image", "reset"];
  const FIELD_ROLES = ["checkbox", "radio", "switch", "combobox", "textbox"];

  // ---- make disabled controls tappable (lazy: document.head does not exist at document-start) ----
  function ensureStyle() {
    try {
      if (S.styleEl && S.styleEl.isConnected) return true;
      const parent = document.head || document.documentElement;
      if (!parent) return false;
      const el = document.createElement("style");
      el.id = "__automation_recorder_style";
      el.textContent =
        'button:disabled, input:disabled, [aria-disabled="true"] { pointer-events: auto !important; cursor: crosshair !important; opacity: 0.7 !important; }';
      parent.appendChild(el);
      S.styleEl = el;
      return true;
    } catch (_) { return false; }
  }

  // ---- keyboard blocking: wrapper is chain-safe (scanner may wrap on top of us) ----
  const proto = HTMLElement.prototype;
  const currentFocus = proto.focus;
  const originalFocus = (currentFocus && currentFocus.__ddgOrig) ? currentFocus.__ddgOrig : currentFocus;
  function wrappedFocus() {
    if (S.active && !S.isPaused) return;
    return originalFocus.apply(this, arguments);
  }
  wrappedFocus.__ddgOrig = originalFocus;

  // ---- helpers (unchanged from v13) ----
  function isVisible(el) {
    if (!el || !el.isConnected) return false;
    try {
      const st = window.getComputedStyle(el);
      if (!st || st.display === "none" || st.visibility === "hidden") return false;
      const r = el.getBoundingClientRect();
      return r.width > 0 || r.height > 0 || el.offsetParent !== null || el.getClientRects().length > 0;
    } catch (_) { return false; }
  }

  function isBoxy(el) {
    return !!el && el.tagName === "INPUT" && (el.type === "checkbox" || el.type === "radio");
  }

  function stableId(id) {
    return !!id && !/\d{4,}/.test(id) && !/^:/.test(id) && !/^\d/.test(id);
  }

  function countMatches(sel) {
    try { return document.querySelectorAll(sel).length; } catch (_) { return 0; }
  }

  // ---- selectors ----
  function getCssSelector(el) {
    if (!el || el.nodeType !== 1) return "";
    const tag = el.nodeName.toLowerCase();

    if (stableId(el.id)) {
      const s = "#" + CSS.escape(el.id);
      if (countMatches(s) === 1) return s;
    }

    const attrs = ["data-testid", "data-test", "data-cy", "name", "aria-label", "title", "placeholder"];
    for (const a of attrs) {
      const v = el.getAttribute(a);
      if (v && v.length > 1 && v.length < 60 && !/\d{4,}/.test(v)) {
        const s = tag + "[" + a + '="' + CSS.escape(v) + '"]';
        if (countMatches(s) === 1) return s;
      }
    }

    const path = [];
    let cur = el;
    while (cur && cur.nodeType === 1 && cur !== document.documentElement) {
      let name = cur.nodeName.toLowerCase();
      if (stableId(cur.id) && countMatches("#" + CSS.escape(cur.id)) === 1) {
        path.unshift("#" + CSS.escape(cur.id));
        break;
      }
      const parent = cur.parentElement;
      if (!parent) break;
      const sibs = Array.from(parent.children).filter(function (c) { return c.nodeName === cur.nodeName; });
      if (sibs.length > 1) name += ":nth-of-type(" + (sibs.indexOf(cur) + 1) + ")";
      path.unshift(name);
      cur = parent;
    }
    return path.join(" > ");
  }

  function getXPath(el) {
    if (!el || el.nodeType !== 1) return "";
    if (stableId(el.id) && el.id.indexOf('"') === -1) return '//*[@id="' + el.id + '"]';

    const tag = el.tagName.toLowerCase();
    if (tag === "button" || tag === "a") {
      const text = (el.innerText || el.textContent || "").replace(/\s+/g, " ").trim();
      if (text.length > 0 && text.length < 40 && text.indexOf('"') === -1) {
        return "//" + tag + '[contains(normalize-space(.), "' + text + '")]';
      }
    }

    const path = [];
    let cur = el;
    while (cur && cur.nodeType === 1) {
      let i = 1;
      let sib = cur.previousSibling;
      while (sib) {
        if (sib.nodeType === 1 && sib.nodeName === cur.nodeName) i++;
        sib = sib.previousSibling;
      }
      path.unshift(cur.nodeName.toLowerCase() + "[" + i + "]");
      cur = cur.parentElement;
    }
    return "/" + path.join("/");
  }

  function getLabel(el) {
    if (!el) return "";
    let label = "";
    const type = (el.type || "").toLowerCase();
    if (el.labels && el.labels.length) {
      label = el.labels[0].innerText || el.labels[0].textContent;
    } else if (el.closest && el.closest("label") && !(el.tagName === "A" || el.tagName === "BUTTON")) {
      const l = el.closest("label");
      label = l.innerText || l.textContent;
    } else {
      label = el.innerText || el.textContent ||
        (el.tagName === "INPUT" && CLICK_INPUT_TYPES.indexOf(type) !== -1 ? el.value : "") ||
        el.getAttribute("aria-label") || el.getAttribute("title") ||
        el.getAttribute("placeholder") || el.getAttribute("name") || el.id;
      if (!label && el.querySelector) {
        const img = el.querySelector("img[alt]");
        if (img) label = img.getAttribute("alt");
      }
    }
    return (label || "").replace(/\s+/g, " ").trim().slice(0, 100);
  }

  function classify(el) {
    const tag = (el.tagName || "").toLowerCase();
    const type = (el.type || "").toLowerCase();
    const role = (el.getAttribute("role") || "").toLowerCase();
    const ac = (el.getAttribute("autocomplete") || "").toLowerCase();
    const c = (getLabel(el) + " " + (el.name || "") + " " + (el.id || "")).toLowerCase();
    if (type === "checkbox" || type === "radio" || role === "checkbox" || role === "radio" || role === "switch") {
      if (/\b(gender|sex|male|female)\b/i.test(c)) return "gender";
      return "termscheckbox";
    }
    if (ac) {
      if (ac.includes("email")) return "email";
      if (ac.includes("tel")) return "phone";
      if (ac.includes("name")) {
        if (ac.includes("given")) return "firstName";
        if (ac.includes("family")) return "lastName";
        return "fullName";
      }
      if (ac.includes("street")) return "address";
      if (ac.includes("postal")) return "zip";
    }
    if (type === "email") return "email";
    if (type === "tel") return "phone";
    if (type === "password") return "password";
    if (type === "date") return "dob";
    if (/\b(first|given)\s*name\b|\bfname\b/.test(c)) return "firstName";
    if (/\b(last|family|sur)\s*name\b|\blname\b/.test(c)) return "lastName";
    if (/\b(full\s*name|your\s*name|name)\b/.test(c)) return "fullName";
    if (/\b(e-?mail)\b/.test(c)) return "email";
    if (/\b(phone|mobile|tel)\b/.test(c)) return "phone";
    if (/\b(password|pass)\b/.test(c)) return "password";
    if (/\b(city)\b/.test(c)) return "city";
    if (/\b(state)\b/.test(c)) return "state";
    if (/\b(zip|postal)\b/.test(c)) return "zip";
    if (/\b(country)\b/.test(c)) return "country";
    if (tag === "textarea" || /\b(message|comment|description)\b/.test(c)) return "message";
    return "text";
  }
  function isTextField(el) {
    if (!el) return false;
    const tag = el.tagName;
    if (tag === "TEXTAREA") return true;
    if (tag === "INPUT") return CLICK_INPUT_TYPES.indexOf((el.type || "").toLowerCase()) === -1 && !isBoxy(el);
    return false;
  }

  // ---- delivery: immediate first, outbox only as fallback ----
  function deliver(item) {
    const b = window.AutomationBridge;
    if (!b || !b.onElementRecorded) return false;
    try { b.onElementRecorded(JSON.stringify(item)); return true; }
    catch (e) { warn("bridge call failed:", e && e.message); return false; }
  }

  function pump() {
    while (S.outbox.length && deliver(S.outbox[0])) S.outbox.shift();
    if (!S.outbox.length && S.pumpTimer) { clearInterval(S.pumpTimer); S.pumpTimer = null; }
  }

  function sendToBridge(item) {
    if (S.outbox.length === 0 && deliver(item)) { log("delivered to bridge"); return; }
    if (S.outbox.length >= CFG.OUTBOX_MAX) { warn("outbox full, dropping oldest event"); S.outbox.shift(); }
    S.outbox.push(item);
    warn("bridge not ready, queued (outbox=" + S.outbox.length + ")");
    if (!S.pumpTimer) S.pumpTimer = setInterval(pump, CFG.PUMP_MS);
  }

  // ---- target resolution (walks the composed path, so open shadow DOM works) ----
  function describe(el) {
    try {
      let s = el.tagName.toLowerCase();
      if (el.id) s += "#" + el.id;
      return s.slice(0, 60);
    } catch (_) { return "?"; }
  }

  function pathOf(e) {
    const out = [];
    try {
      const p = e.composedPath && e.composedPath();
      if (p && p.length) for (const n of p) if (n && n.nodeType === 1) out.push(n);
    } catch (_) {}
    if (!out.length) {
      let t = e.target;
      if (t && t.nodeType !== 1) t = t.parentElement;
      while (t && t.nodeType === 1) { out.push(t); t = t.parentElement; }
    }
    return out;
  }

  function resolveTarget(path) {
    let actual = null;
    for (let i = 0; i < path.length; i++) {
      const el = path[i];
      if (el === document.documentElement || el === document.body) break;
      try {
        if (el.tagName === "LABEL" && el.control) { actual = el.control; break; }
        if (el.matches(INTERACTIVE)) { actual = el; break; }
      } catch (_) {}
    }
    if (!actual) {
      // non-semantic clickable (div/span with cursor:pointer)
      const n = Math.min(path.length, CFG.POINTER_SCAN_DEPTH);
      for (let i = 0; i < n; i++) {
        const el = path[i];
        if (el === document.documentElement || el === document.body) break;
        try { if (getComputedStyle(el).cursor === "pointer") { actual = el; break; } } catch (_) {}
      }
    }
    if (!actual) return null;

    // styled label / text next to a checkbox or radio: record the real control
    if (actual.tagName !== "A" && actual.tagName !== "BUTTON") {
      const lab = actual.tagName === "LABEL" ? actual : (actual.closest ? actual.closest("label") : null);
      if (lab) {
        const ctl = lab.control || lab.querySelector("input, select, textarea, [role='radio'], [role='checkbox']");
        if (ctl) actual = ctl;
      }
    }
    return actual;
  }

  function processEvent(e, src) {
    try {
      if (!S.active) return;
      if (S.isPaused) { log("IGNORED (" + src + "): recorder paused"); return; }

      const path = pathOf(e);
      if (!path.length) { log("IGNORED (" + src + "): no target"); return; }
      for (let i = 0; i < path.length; i++) {
        if (path[i].hasAttribute && path[i].hasAttribute("data-automation-hud")) { log("IGNORED (" + src + "): HUD tap"); return; }
      }

      const actual = resolveTarget(path);
      if (!actual) { log("IGNORED (" + src + "): no interactive target, tapped " + describe(path[0])); return; }
      if (!isVisible(actual) && !isBoxy(actual)) { log("IGNORED (" + src + "): not visible " + describe(actual)); return; }

      // one tap = touchend + pointerup + click + focusin: record once
      const now = Date.now();
      if (actual === lastTarget && (now - lastTime < 500 || isTextField(actual))) { return; }
      lastTarget = actual;
      lastTime = now;

      const tag = actual.tagName.toLowerCase();
      const type = (actual.type || "").toLowerCase();
      const role = (actual.getAttribute("role") || "").toLowerCase();
      const isFieldLike =
        tag === "textarea" || tag === "select" ||
        (tag === "input" && CLICK_INPUT_TYPES.indexOf(type) === -1) ||
        actual.isContentEditable || FIELD_ROLES.indexOf(role) !== -1;
      const kind = isFieldLike ? "Field" : "ClickNext";

      try {
        const old = actual.style.outline;
        actual.style.outline = "3px solid #06b6d4";
        setTimeout(function () { try { actual.style.outline = old; } catch (_) {} }, 600);
      } catch (_) {}

      const label = getLabel(actual);
      const payload = {
        kind: kind, tag: tag, domType: type,
        name: actual.getAttribute("name") || "",
        domId: actual.id || "",
        placeholder: actual.getAttribute("placeholder") || "",
        ariaLabel: actual.getAttribute("aria-label") || "",
        cssSelector: getCssSelector(actual),
        xpath: getXPath(actual),
        looksLike: isFieldLike ? classify(actual) : "",
        displayLabel: label || "Element",
        buttonLabel: kind === "ClickNext" ? (label || "Button") : "",
        verified: true,
      };
      log("CAPTURED (" + src + "):", tag, payload.displayLabel);
      sendToBridge(payload);

      // keep the on-screen keyboard away from tapped text fields
      if (isTextField(actual)) {
        try {
          const wasRO = actual.hasAttribute("readonly");
          actual.setAttribute("readonly", "true");
          actual.blur();
          setTimeout(function () { try { if (!wasRO) actual.removeAttribute("readonly"); } catch (_) {} }, 250);
        } catch (_) {}
      }
    } catch (err) {
      warn("processEvent failed:", err && err.message);
    }
  }

  // ---- listeners (window + capture: we see the event before page code can swallow it) ----
  function on(type, fn, opts) {
    window.addEventListener(type, fn, opts);
    S.listeners.push([type, fn, opts]);
  }

  let tX = 0, tY = 0, tT = 0, tMoved = false;

  function bindListeners() {
    const passiveCap = { capture: true, passive: true };
    const cap = { capture: true };

    on("touchstart", function (e) {
      if (e.touches && e.touches.length === 1) {
        tX = e.touches[0].clientX; tY = e.touches[0].clientY; tT = Date.now(); tMoved = false;
      }
    }, passiveCap);

    on("touchmove", function (e) {
      if (e.touches && e.touches.length === 1) {
        if (Math.abs(e.touches[0].clientX - tX) > CFG.MOVE_PX || Math.abs(e.touches[0].clientY - tY) > CFG.MOVE_PX) tMoved = true;
      }
    }, passiveCap);

    on("touchend", function (e) {
      const moved = tMoved, elapsed = tT ? Date.now() - tT : 0;
      tT = 0;
      if (e.changedTouches && e.changedTouches.length === 1 && !moved && elapsed < CFG.TAP_MAX_MS) processEvent(e, "touchend");
      else if (moved) log("IGNORED (touchend): scroll/swipe");
    }, passiveCap);

    on("pointerup", function (e) {
      if (e.pointerType !== "touch") return;
      if (tMoved) return;
      processEvent(e, "pointerup");
    }, cap);

    on("click", function (e) { processEvent(e, "click"); }, cap);

    on("focusin", function (e) {
      const t = e.target;
      if (t && (t.tagName === "INPUT" || t.tagName === "TEXTAREA")) processEvent(e, "focusin");
    }, cap);

    // last chance to hand queued events to Kotlin before the page goes away
    on("pagehide", function () { pump(); }, cap);
  }

  // ---- lifecycle ----
  function stop() {
    S.active = false;
    S.listeners.forEach(function (l) { try { window.removeEventListener(l[0], l[1], l[2]); } catch (_) {} });
    S.listeners.length = 0;
    if (S.pumpTimer) { clearInterval(S.pumpTimer); S.pumpTimer = null; }
    if (S.onReady) { try { document.removeEventListener("DOMContentLoaded", S.onReady); } catch (_) {} S.onReady = null; }
    try { if (S.styleEl && S.styleEl.parentNode) S.styleEl.parentNode.removeChild(S.styleEl); } catch (_) {}
    S.styleEl = null;
    try { if (HTMLElement.prototype.focus === wrappedFocus) HTMLElement.prototype.focus = originalFocus; } catch (_) {}
    g.__automationRecorderInstalled = false;
    log("Recorder stopped");
  }
  S.stop = stop;

  try {
    if (!ensureStyle()) {
      S.onReady = function () { ensureStyle(); };
      document.addEventListener("DOMContentLoaded", S.onReady, { once: true });
    }
    HTMLElement.prototype.focus = wrappedFocus;
    bindListeners();
    S.active = true;
    g.__ddgRecorderState = S;

    g.__automationRecorderPause = function () { S.isPaused = true; log("Recorder paused"); };
    g.__automationRecorderResume = function () { S.isPaused = false; log("Recorder resumed"); };
    g.__automationRecorderStop = stop;
    g.__automationRecorderHealth = function () {
      return JSON.stringify({
        version: S.version, installed: !!g.__automationRecorderInstalled, active: S.active, paused: S.isPaused,
        listeners: S.listeners.length, outbox: S.outbox.length,
        bridge: !!(window.AutomationBridge && window.AutomationBridge.onElementRecorded),
        boundToThisDoc: S.doc === document, readyState: document.readyState, url: location.href,
      });
    };

    // flag is set ONLY now that everything above really worked
    g.__automationRecorderInstalled = true;
    log("Recorder " + VERSION + " installed readyState=" + document.readyState + " url=" + location.href);
  } catch (err) {
    warn("INSTALL FAILED, will retry on next injection:", err && err.message);
    try { stop(); } catch (_) {}
  }
})();