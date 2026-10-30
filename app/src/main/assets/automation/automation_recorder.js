/*
 * Automation Recorder v19
 *
 * v19 on top of v18:
 *  - at-least-once delivery: every tap is pushed to the bridge AND kept until Kotlin acknowledges it; Kotlin pulls
 *    anything unacknowledged, and de-duplicates by uid (a silently lost bridge call can no longer lose a step)
 *  - field classification from ordered evidence: autocomplete/type, own attributes, labels, aria-labelledby,
 *    web-component (shadow DOM) host labels, nearby label text. Fields stop falling back to "Text Input"
 *  - toasts also explain "not visible" and "paused" taps
 *
 * v18 on top of v17:
 *  - toasts appear ON the tapped element, not at the bottom of the screen
 *  - every captured tap carries a sequence number; Kotlin can acknowledge it, so the toast tells the truth
 *    ("Recorded #4" or "Not recorded: <reason>")
 *  - __automationRecorderDrain(): Kotlin can PULL queued events with evaluateJavascript, so recording no longer
 *    depends on window.AutomationBridge being visible to the page
 *
 * v17 on top of v16:
 *  - on-screen toasts show what the recorder is doing (green = captured, orange = bridge not ready, grey = tap had no
 *    recordable element), so you can see which layer fails without logcat
 *  - wider detection of custom widgets (country-code pickers, date pickers, MUI / Ant / react-select, aria-haspopup...)
 *  - keyboard is suppressed with inputmode=none BEFORE focus (no keyboard flash), restored on pause/stop
 *  - stopPropagation hook only acts on real (trusted) user events
 *
 * v16 on top of v15:
 *  - canary + watchdog: if anything erases our listeners (document.open, page scripts, bfcache restore) they are re-bound within 2s
 *  - taps that pages try to swallow with stopPropagation()/stopImmediatePropagation() are still recorded
 *  - text-field dedupe is time-limited (a field you tap again later is recorded again)
 *  - pointercancel / touchcancel are understood (scroll gestures never become taps)
 *  - health probe now includes counters (captured / ignored / rebinds)
 *
 * Fixes vs v14 (kept from v15):
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
  const VERSION = "v19";
  const DEBUG = true;

  function log() {
    if (!DEBUG) return;
    try { console.log("WebConsole: " + Array.prototype.slice.call(arguments).join(" ")); } catch (_) {}
  }
  function warn() {
    try { console.warn("WebConsole: " + Array.prototype.slice.call(arguments).join(" ")); } catch (_) {}
  }

  try { if (window.top !== window) return; } catch (_) { return; }

  const CFG = { TOASTS: true, OUTBOX_MAX: 200, PUMP_MS: 300, TAP_MAX_MS: 800, MOVE_PX: 30, POINTER_SCAN_DEPTH: 6 };

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
    listeners: [], outbox: [], pumpTimer: null, watchdog: null, styleEl: null, onReady: null, stop: null,
    captured: 0, ignored: 0, rebinds: 0, patched: false, seq: 0, pending: new Map(), unacked: [], docId: Math.random().toString(36).slice(2, 8),
  };

  let lastTarget = null;
  let lastTime = 0;

  const INTERACTIVE =
    'input, textarea, select, button, a, summary, [contenteditable=""], [contenteditable="true"], ' +
    '[role="button"], [role="link"], [role="checkbox"], [role="radio"], [role="switch"], ' +
    '[role="combobox"], [role="textbox"], [role="tab"], [role="menuitem"], [role="option"], ' +
    '[tabindex]:not([tabindex="-1"]), [onclick], ' +
    '[role="spinbutton"], [role="listbox"], [aria-haspopup], ' +
    '[class*="callingCode"], [class*="calling-code"], [class*="countryDropDown"], [class*="country-code"], ' +
    '[class*="countryCode"], [class*="dial-code"], [class*="dialCode"], [class*="birthday"], [class*="bday"], ' +
    '[class*="date-root"], [class*="date-picker"], [class*="datepicker"], ' +
    '.iti__selected-flag, .iti__selected-country, .iti__flag-container, .PhoneInputCountry, .selected-flag, .flag-dropdown, ' +
    '[data-dial-code], [data-country-code], [data-datepicker], ' +
    '.MuiSelect-select, .ant-select-selector, .react-select__control';
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

  // ---- field classification: ordered evidence tiers (mirrors the scanner's token names) ----
  // Rename a token here if your FieldTypeMapper expects a different spelling (e.g. website -> companywebsite).
  const TOKEN_OUT = { website: "website" };
  const AUTOCOMPLETE_MAP = {
    "given-name": "firstname", "family-name": "lastname", "additional-name": "middlename",
    name: "fullname", email: "email", username: "username", "current-password": "password",
    "new-password": "password", "one-time-code": "otp", tel: "phone", "tel-national": "phone",
    "street-address": "address", "address-line1": "address", "address-line2": "address",
    "address-level1": "state", "address-level2": "city", "postal-code": "zip",
    country: "country", "country-name": "country", bday: "dob", "bday-day": "dob",
    "bday-month": "dob", "bday-year": "dob", organization: "company", "organization-title": "jobtitle", sex: "gender",
  };
  const RX = [
    ["confirmpassword", /\b(confirm|repeat|re-?enter|verify)\b.*\b(password|pass)\b|\b(password|pass)\b.*\b(confirm|repeat)\b/i],
    ["confirmemail", /\b(confirm|repeat|re-?enter|verify)\b.*\b(e-?mail)\b|\b(e-?mail)\b.*\b(confirm|repeat)\b/i],
    ["otp", /\b(otp|one[-\s]?time\s*(code|password)|2fa|verification\s*code|security\s*code|sms\s*code|auth\s*code)\b/i],
    ["password", /\b(password|passwd|passcode|pwd)\b/i],
    ["email", /e-?mail/i],
    ["website", /website|web\s*site|homepage|web\s*address|\burl\b/i],
    ["phone", /phone|mobile|whatsapp|telephone|contact\s*number|\btel\b|\bcell\b/i],
    ["firstname", /\b(first|given)\s*name\b|\bfname\b|\bforename\b/i],
    ["middlename", /\b(middle|mid)\s*name\b/i],
    ["lastname", /\b(last|family|sur)\s*name\b|\blname\b|\bsurname\b/i],
    ["dob", /\b(dob|date\s*of\s*birth|birth[\s_-]?date|birthday|bday)\b/i],
    ["nickname", /\b(nick\s*name|nickname|alias)\b/i],
    ["username", /\b(user[\s_-]?name|user[\s_-]?id|login|handle|screen[\s_-]?name)\b/i],
    ["zip", /\b(zip|postal|post[\s_-]?code|pin\s*code|pincode)\b/i],
    ["city", /\b(city|town|ville)\b/i],
    ["state", /\b(state|province|region|county)\b/i],
    ["country", /\b(country|nation)\b/i],
    ["address", /\b(address|street|line\s*1|line\s*2|apartment|apt)\b/i],
    ["company", /company|organi[sz]ation|employer|business/i],
    ["jobtitle", /\b(job\s*title|position|role|occupation|designation)\b/i],
    ["gender", /\b(gender|sex)\b/i],
    ["fullname", /\b(full\s*name|your\s*name|display\s*name|name)\b/i],
    ["message", /\b(message|comment|feedback|description|bio|notes?|details?)\b/i],
  ];
  function classifyText(t) {
    if (!t) return "";
    for (let i = 0; i < RX.length; i++) if (RX[i][1].test(t)) return RX[i][0];
    return "";
  }
  function attrOf(el, n) { try { return (el.getAttribute && el.getAttribute(n)) || ""; } catch (_) { return ""; } }
  // split camelCase / snake_case / kebab-case so "companyName" and "first_name" match word patterns
  function clean(s) {
    return String(s || "").replace(/([a-z0-9])([A-Z])/g, "$1 $2").replace(/[_\-.]+/g, " ").replace(/\s+/g, " ").trim().toLowerCase().slice(0, 200);
  }

  // evidence from the element itself, its labels, its web-component host (shadow DOM) and nearby text
  function evidence(el) {
    const own = [attrOf(el, "placeholder"), attrOf(el, "aria-label"), attrOf(el, "title"), attrOf(el, "name"), el.id,
      attrOf(el, "inputmode"), attrOf(el, "data-testid"), attrOf(el, "data-name")].join(" ");
    const labels = [];
    try { if (el.labels) for (let i = 0; i < el.labels.length; i++) labels.push(el.labels[i].textContent); } catch (_) {}
    try {
      const ids = attrOf(el, "aria-labelledby").split(/\s+/).filter(Boolean);
      const root = el.getRootNode ? el.getRootNode() : document;
      ids.forEach(function (id) {
        const n = (root.getElementById ? root.getElementById(id) : null) || document.getElementById(id);
        if (n) labels.push(n.textContent);
      });
    } catch (_) {}
    try { const wl = el.closest && el.closest("label"); if (wl) labels.push(wl.textContent); } catch (_) {}
    const host = [];
    try {
      let root = el.getRootNode ? el.getRootNode() : null, hops = 0;
      while (root && root.host && hops < 3) {
        const h = root.host;
        host.push(attrOf(h, "label"), attrOf(h, "placeholder"), attrOf(h, "name"), h.id, attrOf(h, "aria-label"),
          attrOf(h, "autocomplete"), attrOf(h, "data-testid"));
        try { const l = h.querySelector("label, [slot=label]"); if (l) host.push(l.textContent); } catch (_) {}
        try { const l2 = root.querySelector("label, [part=label], .label"); if (l2) host.push(l2.textContent); } catch (_) {}
        root = h.getRootNode(); hops++;
      }
    } catch (_) {}
    const near = [];
    try {
      let p = el.parentElement;
      for (let i = 0; i < 3 && p; i++, p = p.parentElement) {
        // a nearby label only counts when this wrapper holds exactly one control (otherwise it belongs to a neighbour)
        let nCtl = 0;
        try { nCtl = p.querySelectorAll("input:not([type=hidden]), select, textarea").length; } catch (_) {}
        if (nCtl !== 1) break;
        let lab = null;
        try { lab = p.querySelector(":scope > label, :scope > legend, :scope > [class*='label' i]"); } catch (_) {}
        if (lab && lab.textContent && lab.textContent.length < 80 && !lab.contains(el)) { near.push(lab.textContent); break; }
        const prev = p.previousElementSibling;
        if (prev && prev.textContent && prev.textContent.length < 60 && !prev.querySelector("input, select, textarea, button")) { near.push(prev.textContent); break; }
      }
    } catch (_) {}
    return { own: clean(own), labels: clean(labels.join(" ")), host: clean(host.join(" ")), near: clean(near.join(" ")) };
  }

  function bestLabel(el) {
    const ev = evidence(el);
    const raw = attrOf(el, "aria-label") || (el.labels && el.labels[0] && el.labels[0].textContent) ||
      attrOf(el, "placeholder") || ev.labels || ev.host || ev.near || attrOf(el, "name") || el.id || "";
    return String(raw).replace(/\s+/g, " ").trim().slice(0, 60);
  }

  function classify(el) {
    const tag = (el.tagName || "").toLowerCase();
    const type = (el.type || "").toLowerCase();
    const role = attrOf(el, "role").toLowerCase();
    const out = function (tok) { return TOKEN_OUT[tok] || tok; };
    const ev = evidence(el);

    if (type === "checkbox" || type === "radio" || role === "checkbox" || role === "radio" || role === "switch") {
      if (/\b(gender|sex|male|female)\b/i.test(ev.own + " " + ev.labels + " " + ev.near)) return "gender";
      return "termscheckbox";
    }
    const ac = attrOf(el, "autocomplete").toLowerCase().split(/\s+/)[0];
    if (ac && AUTOCOMPLETE_MAP[ac]) return out(AUTOCOMPLETE_MAP[ac]);
    if (type === "password") return /\b(confirm|repeat|re-?enter|verify)\b/i.test(ev.own + " " + ev.labels) ? "confirmpassword" : "password";
    if (type === "email") return "email";
    if (type === "tel") return "phone";
    if (type === "date") return "dob";
    if (type === "url") return out("website");

    // strongest evidence first; the first tier that recognises the field wins
    const tiers = [ev.own, ev.labels, ev.host, ev.near, ev.own + " " + ev.labels + " " + ev.host];
    for (let i = 0; i < tiers.length; i++) {
      const tok = classifyText(tiers[i]);
      if (tok) return out(tok);
    }
    if (tag === "textarea" || role === "textbox") return "message";
    return "text";
  }
  function isTextField(el) {
    if (!el) return false;
    const tag = el.tagName;
    if (tag === "TEXTAREA") return true;
    if (tag === "INPUT") return CLICK_INPUT_TYPES.indexOf((el.type || "").toLowerCase()) === -1 && !isBoxy(el);
    return false;
  }


  // ---- on-screen feedback: the badge sits on the tapped element ----
  let toastEl = null, toastTimer = null;
  function toast(msg, color, target) {
    if (!CFG.TOASTS) return;
    try {
      const host = document.body || document.documentElement;
      if (!host) return;
      if (toastEl && toastEl.parentNode) toastEl.parentNode.removeChild(toastEl);
      clearTimeout(toastTimer);
      const el = document.createElement("div");
      el.setAttribute("data-automation-hud", "1");
      el.textContent = msg;
      el.style.cssText =
        "position:fixed;z-index:2147483647;top:0;left:0;visibility:hidden;max-width:80vw;" +
        "background:" + color + ";color:#fff;font:700 12px -apple-system,BlinkMacSystemFont,sans-serif;" +
        "padding:5px 10px;border-radius:8px;box-shadow:0 4px 12px rgba(0,0,0,.35);pointer-events:none;" +
        "white-space:nowrap;overflow:hidden;text-overflow:ellipsis;";
      host.appendChild(el);
      const w = el.offsetWidth, hgt = el.offsetHeight;
      let r = null;
      try { r = target && target.getBoundingClientRect ? target.getBoundingClientRect() : null; } catch (_) {}
      let top, left;
      if (r && (r.width || r.height)) {
        top = r.top - hgt + 4;                       // sits on the top edge of the element
        if (top < 6) top = r.top + 4;                // no room above: sit just inside the element
        left = Math.max(6, Math.min(r.left + 6, window.innerWidth - w - 6));
      } else {
        top = window.innerHeight - 110;
        left = Math.max(6, (window.innerWidth - w) / 2);
      }
      el.style.top = top + "px";
      el.style.left = left + "px";
      el.style.visibility = "visible";
      toastEl = el;
      toastTimer = setTimeout(function () { try { if (el.parentNode) el.parentNode.removeChild(el); } catch (_) {} }, 1600);
    } catch (_) {}
  }

  // ---- keyboard: inputmode=none BEFORE focus so no keyboard flashes up while recording ----
  const kbSaved = new Map();
  function suppressKeyboard(e, doBlur) {
    try {
      if (!S.active || S.isPaused) return;
      const path = pathOf(e);
      const n = Math.min(path.length, 4);
      for (let i = 0; i < n; i++) {
        const el = path[i];
        if (isTextField(el) || el.isContentEditable) {
          if (!kbSaved.has(el)) kbSaved.set(el, el.getAttribute("inputmode"));
          el.setAttribute("inputmode", "none");
          if (doBlur) { try { el.blur(); } catch (_) {} }
          return;
        }
      }
    } catch (_) {}
  }
  function restoreKeyboard() {
    kbSaved.forEach(function (orig, el) {
      try { if (orig === null) el.removeAttribute("inputmode"); else el.setAttribute("inputmode", orig); } catch (_) {}
    });
    kbSaved.clear();
  }

  // ---- delivery: immediate first, outbox only as fallback ----
  function deliver(item) {
    const b = window.AutomationBridge;
    if (!b || !b.onElementRecorded) return false;
    try { b.onElementRecorded(JSON.stringify(item)); return true; }
    catch (e) { S.lastBridgeError = String(e && e.message); warn("bridge call failed:", S.lastBridgeError); toast("\u26A0 bridge call failed", "#DC2626"); return false; }
  }

  function capUnacked() {
    while (S.unacked.length > 50) S.unacked.shift();
  }

  function pump() {
    while (S.outbox.length && deliver(S.outbox[0])) { S.unacked.push(S.outbox.shift()); }
    capUnacked();
    if (!S.outbox.length && S.pumpTimer) { clearInterval(S.pumpTimer); S.pumpTimer = null; }
  }

  // Push to the bridge now (survives instant navigation) but keep the event until Kotlin acknowledges it.
  // Anything unacknowledged is pulled by Kotlin through __automationRecorderDrain().
  function sendToBridge(item, targetEl) {
    if (S.outbox.length === 0 && deliver(item)) {
      S.unacked.push(item); capUnacked();
      log("pushed to bridge, waiting for ack");
      return;
    }
    if (S.outbox.length >= CFG.OUTBOX_MAX) { warn("outbox full, dropping oldest event"); S.outbox.shift(); }
    S.outbox.push(item);
    warn("bridge not ready, queued (outbox=" + S.outbox.length + ")");
    toast("\u23F3 Sending... (bridge not ready)", "#F59E0B", targetEl);
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

  function ignore(msg) { S.ignored++; log(msg); }

  function processEvent(e, src) {
    try {
      if (!S.active) return;
      if (S.isPaused) { ignore("IGNORED (" + src + "): recorder paused"); if (src === "click") toast("Recorder paused", "#6B7280"); return; }

      const path = pathOf(e);
      if (!path.length) { ignore("IGNORED (" + src + "): no target"); return; }
      for (let i = 0; i < path.length; i++) {
        if (path[i].hasAttribute && path[i].hasAttribute("data-automation-hud")) { ignore("IGNORED (" + src + "): HUD tap"); return; }
      }

      const actual = resolveTarget(path);
      if (!actual) { ignore("IGNORED (" + src + "): no interactive target, tapped " + describe(path[0])); toast("Nothing recordable: " + describe(path[0]), "#6B7280", path[0]); return; }
      if (!isVisible(actual) && !isBoxy(actual)) { ignore("IGNORED (" + src + "): not visible " + describe(actual)); toast("Not visible: " + describe(actual), "#6B7280", actual); return; }

      // one tap = touchend + pointerup + click + focusin: record once
      const now = Date.now();
      if (actual === lastTarget && now - lastTime < (isTextField(actual) ? 1200 : 500)) { return; }
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

      const label = getLabel(actual) || bestLabel(actual);
      const payload = {
        kind: kind, tag: tag, domType: type,
        name: actual.getAttribute("name") || "",
        domId: actual.id || "",
        placeholder: actual.getAttribute("placeholder") || "",
        ariaLabel: actual.getAttribute("aria-label") || "",
        cssSelector: getCssSelector(actual),
        xpath: getXPath(actual),
        looksLike: isFieldLike ? classify(actual) : "",
        displayLabel: (isFieldLike ? (bestLabel(actual) || label) : label) || "Element",
        buttonLabel: kind === "ClickNext" ? (label || "Button") : "",
        verified: true,
      };
      S.captured++;
      log("CAPTURED (" + src + "):", tag, payload.displayLabel);
      payload.seq = ++S.seq;
      payload.uid = S.docId + ":" + payload.seq;
      S.pending.set(payload.seq, { el: actual, label: payload.displayLabel || tag });
      if (S.pending.size > 30) S.pending.delete(S.pending.keys().next().value);
      sendToBridge(payload, actual);
      if (S.outbox.length === 0) toast("Grabbed: " + (payload.displayLabel || tag), "#0891B2", actual);

      // keyboard is already suppressed through inputmode=none; make sure the field is not left focused
      if (isTextField(actual)) { try { actual.blur(); } catch (_) {} }
    } catch (err) {
      warn("processEvent failed:", err && err.message);
    }
  }

  // ---- listeners (window + capture: we see the event before page code can swallow it) ----
  const processed = new WeakSet();   // events we already handled (so the stopPropagation hook never double-processes)
  const HANDLERS = {};               // type -> handler, used by the stopPropagation hook

  function on(type, fn, opts) {
    window.addEventListener(type, fn, opts);
    S.listeners.push([type, fn, opts]);
  }

  function track(type, fn) {
    const h = function (e) {
      if (processed.has(e)) return;
      processed.add(e);
      fn(e);
    };
    HANDLERS[type] = h;
    return h;
  }

  let tX = 0, tY = 0, tT = 0, tMoved = false;
  let canaryHit = false;

  function bindListeners() {
    const passiveCap = { capture: true, passive: true };
    const cap = { capture: true };

    on("touchstart", track("touchstart", function (e) {
      if (e.touches && e.touches.length === 1) {
        tX = e.touches[0].clientX; tY = e.touches[0].clientY; tT = Date.now(); tMoved = false;
      }
      suppressKeyboard(e, false);
    }), passiveCap);

    on("pointerdown", track("pointerdown", function (e) {
      if (e.pointerType === "touch") { tX = e.clientX; tY = e.clientY; tT = Date.now(); tMoved = false; }
      suppressKeyboard(e, false);
    }), cap);

    on("touchmove", track("touchmove", function (e) {
      if (e.touches && e.touches.length === 1) {
        if (Math.abs(e.touches[0].clientX - tX) > CFG.MOVE_PX || Math.abs(e.touches[0].clientY - tY) > CFG.MOVE_PX) tMoved = true;
      }
    }), passiveCap);

    // the browser took the gesture over for scrolling: never treat it as a tap
    on("pointercancel", function (e) { if (e.pointerType === "touch") tMoved = true; }, cap);
    on("touchcancel", function () { tT = 0; tMoved = false; }, passiveCap);

    on("touchend", track("touchend", function (e) {
      const moved = tMoved, elapsed = tT ? Date.now() - tT : 0;
      tT = 0;
      if (e.changedTouches && e.changedTouches.length === 1 && !moved && elapsed < CFG.TAP_MAX_MS) processEvent(e, "touchend");
      else if (moved) log("IGNORED (touchend): scroll/swipe");
    }), passiveCap);

    on("pointerup", track("pointerup", function (e) {
      if (e.pointerType !== "touch") return;
      if (tMoved) return;
      processEvent(e, "pointerup");
    }), cap);

    on("click", track("click", function (e) { processEvent(e, "click"); }), cap);

    on("focusin", track("focusin", function (e) {
      suppressKeyboard(e, true);
      const t = e.target;
      if (t && (t.tagName === "INPUT" || t.tagName === "TEXTAREA")) processEvent(e, "focusin");
    }), cap);

    // last chance to hand queued events to Kotlin before the page goes away
    on("pagehide", function () { pump(); }, cap);
    on("pageshow", function (e) { if (e.persisted) verifyListeners("pageshow/bfcache"); }, cap);

    // canary: lets the watchdog prove that our listeners are still attached
    on("__ddg_canary", function () { canaryHit = true; }, cap);
  }

  function unbindListeners() {
    S.listeners.forEach(function (l) { try { window.removeEventListener(l[0], l[1], l[2]); } catch (_) {} });
    S.listeners.length = 0;
  }

  // if anything erased our listeners (document.open(), page code, bfcache restore) bind them again
  function verifyListeners(reason) {
    if (!S.active) return;
    canaryHit = false;
    try { window.dispatchEvent(new Event("__ddg_canary")); } catch (_) {}
    if (!canaryHit) {
      warn("listeners were erased (" + reason + "), re-binding");
      unbindListeners();
      bindListeners();
      S.rebinds++;
    }
    ensureStyle();
  }

  // ---- pages that call stopPropagation()/stopImmediatePropagation() on a tap must not hide it from us ----
  const evProto = Event.prototype;
  const origStop = evProto.stopPropagation;
  const origStopImm = evProto.stopImmediatePropagation;
  function hook(orig) {
    function wrapped() {
      try {
        if (S.active && this.isTrusted) {
          const h = HANDLERS[this.type];
          if (h) h(this);
        }
      } catch (_) {}
      return orig.apply(this, arguments);
    }
    wrapped.__ddgOrig = orig;
    return wrapped;
  }
  const wrappedStop = hook(origStop);
  const wrappedStopImm = hook(origStopImm);

  // ---- lifecycle ----
  function stop() {
    S.active = false;
    restoreKeyboard();
    unbindListeners();
    if (S.pumpTimer) { clearInterval(S.pumpTimer); S.pumpTimer = null; }
    if (S.watchdog) { clearInterval(S.watchdog); S.watchdog = null; }
    if (S.onReady) { try { document.removeEventListener("DOMContentLoaded", S.onReady); } catch (_) {} S.onReady = null; }
    try { if (S.styleEl && S.styleEl.parentNode) S.styleEl.parentNode.removeChild(S.styleEl); } catch (_) {}
    S.styleEl = null;
    try { if (HTMLElement.prototype.focus === wrappedFocus) HTMLElement.prototype.focus = originalFocus; } catch (_) {}
    try {
      if (evProto.stopPropagation === wrappedStop) evProto.stopPropagation = origStop;
      if (evProto.stopImmediatePropagation === wrappedStopImm) evProto.stopImmediatePropagation = origStopImm;
    } catch (_) {}
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
    evProto.stopPropagation = wrappedStop;
    evProto.stopImmediatePropagation = wrappedStopImm;
    S.active = true;
    g.__ddgRecorderState = S;
    S.watchdog = setInterval(function () { verifyListeners("watchdog"); }, 2000);

    g.__automationRecorderPause = function () { S.isPaused = true; restoreKeyboard(); log("Recorder paused"); };
    g.__automationRecorderResume = function () { S.isPaused = false; log("Recorder resumed"); };
    g.__automationRecorderStop = stop;
    // Kotlin can PULL queued events (works even when window.AutomationBridge is not visible to this page)
    g.__automationRecorderDrain = function () {
      const all = S.outbox.splice(0, S.outbox.length).concat(S.unacked.splice(0, S.unacked.length));
      return all.length ? JSON.stringify(all) : "";
    };
    // Kotlin acknowledges every event: the toast then shows what really happened
    g.__automationRecorderAck = function (seq, ok, msg) {
      const p = S.pending.get(seq);
      S.pending.delete(seq);
      S.unacked = S.unacked.filter(function (i) { return i.seq !== seq; });
      const label = p ? p.label : "";
      if (ok) toast("\u2713 Recorded " + (msg || "") + (label ? ": " + label : ""), "#10B981", p && p.el);
      else toast("\u2717 Not recorded: " + msg, "#DC2626", p && p.el);
    };
    g.__automationRecorderHealth = function () {
      return JSON.stringify({
        version: S.version, installed: !!g.__automationRecorderInstalled, active: S.active, paused: S.isPaused,
        listeners: S.listeners.length, outbox: S.outbox.length,
        bridge: !!(window.AutomationBridge && window.AutomationBridge.onElementRecorded),
        boundToThisDoc: S.doc === document, readyState: document.readyState, url: location.href,
        captured: S.captured, ignored: S.ignored, rebinds: S.rebinds, lastBridgeError: S.lastBridgeError || "", pendingAcks: S.pending.size, unacked: S.unacked.length,
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