/*
 * Automation Recorder v13
 *  - safe to run at document-start (no document.head dependency, installed flag only set after a successful setup)
 *  - no "justRecorded" flag that can get stuck; duplicate taps are filtered by element + time
 *  - listeners are removable (named functions), so Stop really stops
 *  - uses composedPath() so fields inside open shadow DOM are captured
 *  - selectors are checked for uniqueness before being recorded
 *  - taps on non-interactive text/containers are ignored (no junk steps)
 *  - top frame only (the replay engine only searches the main document)
 */
(function () {
  "use strict";
  const g = globalThis;

  try { if (window.top !== window) return; } catch (_) { return; }

  if (g.__automationRecorderInstalled) {
    if (typeof g.__automationRecorderResume === "function") g.__automationRecorderResume();
    return;
  }

  let isPaused = false;
  let lastTarget = null;
  let lastTime = 0;
  let styleEl = null;
  const listeners = []; // [type, fn, options]
  const originalFocus = HTMLElement.prototype.focus;

  const INTERACTIVE =
    'input, textarea, select, button, a, summary, [contenteditable=""], [contenteditable="true"], ' +
    '[role="button"], [role="link"], [role="checkbox"], [role="radio"], [role="switch"], ' +
    '[role="combobox"], [role="textbox"], [role="tab"], [role="menuitem"], [onclick]';
  const CLICK_INPUT_TYPES = ["submit", "button", "image", "reset"];
  const FIELD_ROLES = ["checkbox", "radio", "switch", "combobox", "textbox"];

  // ---- make disabled controls tappable (added lazily: document.head may not exist at document-start) ----
  function ensureStyle() {
    if (styleEl && styleEl.isConnected) return;
    const parent = document.head || document.documentElement;
    if (!parent) return;
    styleEl = document.createElement("style");
    styleEl.id = "__automation_recorder_style";
    styleEl.textContent =
      'button:disabled, input:disabled, [aria-disabled="true"] { pointer-events: auto !important; cursor: crosshair !important; opacity: 0.7 !important; }';
    parent.appendChild(styleEl);
  }
  try { ensureStyle(); } catch (_) {}
  if (!styleEl) {
    document.addEventListener("DOMContentLoaded", function () { try { ensureStyle(); } catch (_) {} }, { once: true });
  }

  // ---- block the on-screen keyboard while recording ----
  HTMLElement.prototype.focus = function () {
    if (!isPaused && g.__automationRecorderInstalled) return;
    return originalFocus.apply(this, arguments);
  };

  function on(type, fn, opts) {
    document.addEventListener(type, fn, opts);
    listeners.push([type, fn, opts]);
  }

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

  // ---- bridge (with retry: the Java bridge may not be ready for the first few ms of a page) ----
  function sendToBridge(data) {
    const json = JSON.stringify(data);
    let attempts = 0;
    (function trySend() {
      attempts++;
      try {
        if (window.AutomationBridge && typeof window.AutomationBridge.onElementRecorded === "function") {
          window.AutomationBridge.onElementRecorded(json);
          return;
        }
      } catch (e) { console.error("Recorder: bridge error", e); return; }
      if (attempts < 10) setTimeout(trySend, 150);
      else console.warn("Recorder: AutomationBridge missing");
    })();
  }

  function isTextField(el) {
    if (!el) return false;
    const tag = el.tagName;
    if (tag === "TEXTAREA") return true;
    if (tag === "INPUT") return CLICK_INPUT_TYPES.indexOf((el.type || "").toLowerCase()) === -1 && !isBoxy(el);
    return false;
  }

  function resolveTarget(raw) {
    let t = raw;
    if (!t) return null;
    if (t.nodeType !== 1) t = t.parentElement;
    if (!t) return null;

    let actual = t.closest ? t.closest(INTERACTIVE) : null;

    if (!actual) {
      // non-semantic clickable (div/span with cursor:pointer)
      let c = t, n = 0;
      while (c && c.nodeType === 1 && n++ < 4) {
        try { if (getComputedStyle(c).cursor === "pointer") { actual = c; break; } } catch (_) {}
        c = c.parentElement;
      }
    }
    if (!actual) return null;

    // a styled label / text next to a checkbox or radio: record the real control
    if (actual.tagName !== "A" && actual.tagName !== "BUTTON") {
      const lab = actual.tagName === "LABEL" ? actual : (actual.closest ? actual.closest("label") : null);
      if (lab) {
        const ctl = lab.control || lab.querySelector("input, select, textarea, [role='radio'], [role='checkbox']");
        if (ctl) actual = ctl;
      }
    }
    return actual;
  }

  function processTarget(rawTarget) {
    try {
      if (isPaused) return;
      if (rawTarget && rawTarget.closest && rawTarget.closest("[data-automation-hud]")) return;

      const actual = resolveTarget(rawTarget);
      if (!actual) return;
      if (!isVisible(actual) && !isBoxy(actual)) return;

      // same tap produces touchend + pointerup + click + focusin: record it once.
      // A text field that is still the last recorded element is not recorded again (tapping to move the cursor).
      const now = Date.now();
      if (actual === lastTarget && (now - lastTime < 500 || isTextField(actual))) return;
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
      console.log("Recorder: captured", tag, payload.displayLabel);
      sendToBridge(payload);
    } catch (e) {
      console.error("Recorder: processTarget failed", e);
    }
  }

  function targetOf(e) {
    try {
      const p = e.composedPath && e.composedPath();
      if (p && p.length && p[0] && p[0].nodeType === 1) return p[0];
    } catch (_) {}
    return e.target;
  }

  // ---- touch / pointer / click ----
  let tX = 0, tY = 0, tT = 0, tMoved = false;

  on("touchstart", function (e) {
    if (e.touches.length === 1) {
      tX = e.touches[0].clientX; tY = e.touches[0].clientY;
      tT = Date.now(); tMoved = false;
    }
  }, { capture: true, passive: true });

  on("touchmove", function (e) {
    if (e.touches.length === 1) {
      if (Math.abs(e.touches[0].clientX - tX) > 30 || Math.abs(e.touches[0].clientY - tY) > 30) tMoved = true;
    }
  }, { capture: true, passive: true });

  on("touchend", function (e) {
    if (e.changedTouches.length === 1 && !tMoved && Date.now() - tT < 800) processTarget(targetOf(e));
  }, { capture: true, passive: true });

  on("pointerup", function (e) {
    if (e.pointerType !== "touch") return;
    if (tMoved || Date.now() - tT > 800) return;
    processTarget(targetOf(e));
  }, { capture: true });

  on("click", function (e) { processTarget(targetOf(e)); }, { capture: true });

  on("focusin", function (e) {
    const t = targetOf(e);
    if (t && (t.tagName === "INPUT" || t.tagName === "TEXTAREA")) processTarget(t);
  }, { capture: true });

  g.__automationRecorderPause = function () { isPaused = true; };
  g.__automationRecorderResume = function () { isPaused = false; };
  g.__automationRecorderStop = function () {
    listeners.forEach(function (l) { try { document.removeEventListener(l[0], l[1], l[2]); } catch (_) {} });
    listeners.length = 0;
    try { if (styleEl && styleEl.parentNode) styleEl.parentNode.removeChild(styleEl); } catch (_) {}
    HTMLElement.prototype.focus = originalFocus;
    g.__automationRecorderInstalled = false;
  };

  // Only now is the recorder really installed. If anything above threw, the flag stays false
  // and the next injection (onPageFinished / document-start) simply tries again.
  g.__automationRecorderInstalled = true;
  console.log("Recorder: installed");
})();