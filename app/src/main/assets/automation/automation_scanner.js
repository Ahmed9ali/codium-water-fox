/*
 * Automation Scanner (v9 - Keyboard Readonly Block + Calendar Bypass)
 */
(function () {
  "use strict";
  const g = globalThis;

  if (g.__automationScannerInstalled) {
    try { g.__automationScan(); } catch (_) {}
    return;
  }
  g.__automationScannerInstalled = true;

  const CFG = {
    MAX_IFRAME_DEPTH: 2,
    MAX_SHADOW_DEPTH: 3,
    MIN_SIZE_PX: 2,
    MAX_BUTTONS_OUT: 25,
    MAX_LINKS_OUT: 20,
    FILL_DELAY_MS: 50,
    CLICK_SETTLE_POLL_MS: 100,
  };

  g.__automation_active = false;

  // Block keyboard if automation is active
  const originalFocus = HTMLElement.prototype.focus;
  HTMLElement.prototype.focus = function(...args) {
    if (g.__automation_active) return;
    return originalFocus.apply(this, args);
  };

  const RX = {
    otp: /\b(otp|one[-\s]?time\s*(code|password)|2fa|verification\s*code|security\s*code|sms\s*code|auth\s*code)\b/i,
    confirm_password: /\b(confirm|repeat|re-?enter|verify)\b.*\b(password|pass)\b|\b(password|pass)\b.*\b(confirm|repeat)\b/i,
    confirm_email: /\b(confirm|repeat|re-?enter|verify)\b.*\b(e-?mail)\b|\b(e-?mail)\b.*\b(confirm|repeat)\b/i,
    password: /\b(password|passwd|passcode|pwd)\b/i,
    email: /\b(e-?mail|mail)\b/i,
    phone: /\b(phone|mobile|cell|tel|whatsapp|contact\s*number)\b/i,
    firstname: /\b(first|given)\s*name\b|\bfname\b|\bforename\b/i,
    lastname: /\b(last|family|sur)\s*name\b|\blname\b|\bsurname\b/i,
    middlename: /\b(middle|mid)\s*name\b/i,
    fullname: /\b(full\s*name|your\s*name|display\s*name|name)\b/i,
    username: /\b(user[\s_-]?name|user[\s_-]?id|login|handle|screen[\s_-]?name)\b/i,
    message: /\b(message|comment|feedback|description|bio|notes?|details?)\b/i,
    dob: /\b(dob|date\s*of\s*birth|birth[\s_-]?date|birthday|bday)\b/i,
    zip: /\b(zip|postal|post[\s_-]?code|pin\s*code|pincode)\b/i,
    city: /\b(city|town|ville)\b/i,
    state: /\b(state|province|region|county)\b/i,
    country: /\b(country|nation)\b/i,
    address: /\b(address|street|line\s*1|line\s*2|apartment|apt)\b/i,
    company: /\b(company|organi[sz]ation|employer|business)\b/i,
    jobtitle: /\b(job\s*title|position|role|occupation|designation)\b/i,
    gender: /\b(gender|sex)\b/i,
    terms: /\b(terms|agree|consent|privacy|policy|accept|conditions)\b/i,
  };

  const BTN_POS = /\b(next|continue|submit|sign\s*up|signup|register|create|join|proceed|verify|confirm|finish|done|save|send|login|log\s*in|sign\s*in|apply)\b/i;
  const BTN_NEG = /\b(back|previous|prev|cancel|close|dismiss|reset|clear|delete|remove|log\s*out|sign\s*out|logout|skip|forgot|resend|no\s*thanks|not\s*now|decline|reject|deny)\b/i;

  let refCounter = 0;
  const elToRef = new WeakMap();
  const refToEl = new Map();

  function getRef(el) {
    if (!el) return null;
    let r = elToRef.get(el);
    if (r) return r;
    r = "r" + (++refCounter);
    elToRef.set(el, r);
    try { refToEl.set(r, new WeakRef(el)); } catch (_) { refToEl.set(r, { deref: () => el }); }
    try { el.setAttribute("data-dd-ref", r); } catch (_) {}
    return r;
  }

  function getByRef(ref) {
    const w = refToEl.get(ref);
    if (!w) return null;
    const el = w.deref ? w.deref() : null;
    return el && el.isConnected ? el : null;
  }

  const sleep = (ms) => new Promise((r) => setTimeout(r, ms));
  const txt = (el) => el ? ((el.innerText || el.textContent || "").replace(/\s+/g, " ").trim()) : "";
  const attr = (el, n) => (el && el.getAttribute ? el.getAttribute(n) || "" : "");

  function isVisible(el) {
    if (!el || !el.isConnected) return false;
    try {
      if (attr(el, "aria-hidden") === "true") return false;
      const st = el.ownerDocument.defaultView.getComputedStyle(el);
      if (!st) return false;
      if (st.display === "none" || st.visibility === "hidden" || st.visibility === "collapse") return false;
      if (parseFloat(st.opacity) === 0) return false;
      const r = el.getBoundingClientRect();
      if (r.width < CFG.MIN_SIZE_PX && r.height < CFG.MIN_SIZE_PX && el.offsetParent === null) return false;
      if (r.right < -2000 || r.bottom < -2000) return false;
      return true;
    } catch (_) { return false; }
  }

  function isDisabled(el) {
    if (!el) return false;
    if (el.disabled === true || el.inert === true) return true;
    if (attr(el, "aria-disabled") === "true") return true;
    if (el.closest && el.closest("[inert]")) return true;
    return false;
  }

  function findLabel(el) {
    if (!el) return "";
    const out = [];
    try {
      if (el.labels && el.labels.length) for (const l of el.labels) { const t = txt(l).slice(0, 120); if (t && !out.includes(t)) out.push(t); }
      if (el.id) {
        try {
          const l = el.ownerDocument.querySelector('label[for="' + CSS.escape(el.id) + '"]');
          if (l) { const t = txt(l).slice(0, 120); if (t && !out.includes(t)) out.push(t); }
        } catch (_) {}
      }
      const wrap = el.closest && el.closest("label");
      if (wrap) { const t = txt(wrap).slice(0, 120); if (t && !out.includes(t)) out.push(t); }
      const lblBy = attr(el, "aria-labelledby");
      if (lblBy) for (const id of lblBy.split(/\s+/)) { const n = el.ownerDocument.getElementById(id); if (n) { const t = txt(n).slice(0, 120); if (t && !out.includes(t)) out.push(t); } }
    } catch (_) {}
    return out.join(" ").trim();
  }

  function combinedLabel(el) {
    return [attr(el, "placeholder"), attr(el, "aria-label"), attr(el, "title"), attr(el, "name"), el.id, attr(el, "autocomplete"), attr(el, "inputmode"), attr(el, "data-testid"), attr(el, "data-name"), findLabel(el)].join(" ").toLowerCase();
  }

  const AUTOCOMPLETE_MAP = {
    "given-name": "firstname", "family-name": "lastname", "additional-name": "middlename",
    name: "fullname", email: "email", username: "username", "current-password": "password",
    "new-password": "password", "one-time-code": "otp", tel: "phone", "tel-national": "phone",
    "street-address": "address", "address-line1": "address", "address-line2": "address",
    "address-level1": "state", "address-level2": "city", "postal-code": "zip",
    country: "country", "country-name": "country", bday: "dob", "bday-day": "dob",
    "bday-month": "dob", "bday-year": "dob", organization: "company", "organization-title": "jobtitle", sex: "gender",
  };

  function classify(el) {
    const ac = attr(el, "autocomplete").toLowerCase().split(/\s+/)[0];
    if (ac && AUTOCOMPLETE_MAP[ac]) return AUTOCOMPLETE_MAP[ac];
    const tag = (el.tagName || "").toLowerCase();
    const type = (el.type || "").toLowerCase();
    const role = attr(el, "role").toLowerCase();
    if (type === "password") return "password";
    if (type === "email") return "email";
    if (type === "tel") return "phone";
    const c = combinedLabel(el);
    if (type === "checkbox" || role === "checkbox" || role === "switch") return "termscheckbox";
    if (type === "radio" || role === "radio") {
      if (RX.gender.test(c)) return "gender";
      return "termscheckbox";
    }
    if (RX.confirm_password.test(c)) return "confirmpassword";
    if (RX.confirm_email.test(c)) return "confirmemail";
    if (RX.otp.test(c)) return "otp";
    if (RX.password.test(c)) return "password";
    if (RX.email.test(c)) return "email";
    if (RX.phone.test(c)) return "phone";
    if (RX.firstname.test(c)) return "firstname";
    if (RX.middlename.test(c)) return "middlename";
    if (RX.lastname.test(c)) return "lastname";
    if (RX.dob.test(c)) return "dob";
    if (RX.fullname.test(c)) return "fullname";
    if (RX.username.test(c)) return "username";
    if (RX.zip.test(c)) return "zip";
    if (RX.city.test(c)) return "city";
    if (RX.state.test(c)) return "state";
    if (RX.country.test(c)) return "country";
    if (RX.address.test(c)) return "address";
    if (RX.company.test(c)) return "company";
    if (RX.jobtitle.test(c)) return "jobtitle";
    if (RX.gender.test(c)) return "gender";
    if (tag === "textarea" || role === "textbox" || RX.message.test(c)) return "message";
    return "text";
  }

  function isCustomSelectTrigger(el) {
    if (!el) return false;
    const role = attr(el, "role").toLowerCase();
    if (role === "combobox" || role === "listbox" || attr(el, "aria-haspopup") === "listbox") return true;
    const cls = (el.className || "").toString().toLowerCase();
    if (/\b(mui-?select|ant-?select|chakra-?select|react-?select|rs-?select|v-?select|select2|choices|dd-?trigger|dd-?toggle)\b/.test(cls)) return true;
    return false;
  }

  const FIELD_SELECTOR = 'input:not([type=hidden]):not([type=submit]):not([type=button]):not([type=reset]):not([type=image]):not([type=file]), textarea, select, [role=combobox], [role=textbox], [contenteditable="true"], [contenteditable=""], .dd, .ctl, .select-trigger, [data-dropdown], [data-select]';
  const BUTTON_SELECTOR = 'button, a[role=button], input[type=submit], input[type=button], input[type=image], [role=button]:not(a), [onclick]';

  function collectAll(doc, selector) {
    const out = [];
    try { out.push(...Array.from(doc.querySelectorAll(selector))); } catch (_) {}
    let count = 0;
    function walk(root, depth) {
      if (!root || depth > CFG.MAX_SHADOW_DEPTH || count >= 3000) return;
      let nodeList;
      try { nodeList = root.querySelectorAll("*"); } catch (_) { return; }
      for (const el of nodeList) {
        count++;
        if (el.shadowRoot) {
          try { out.push(...Array.from(el.shadowRoot.querySelectorAll(selector))); } catch (_) {}
          walk(el.shadowRoot, depth + 1);
        }
      }
    }
    walk(doc, 0);
    return out;
  }

  function scanPage() {
    if (document.readyState === "loading") return emptyResult();

    const fieldElements = collectAll(document, FIELD_SELECTOR);
    const fields = [];
    const seenFields = new Set();
    for (const el of fieldElements) {
      try {
        if (!el || !el.isConnected || !isVisible(el) || isDisabled(el)) continue;
        if (el.type === "search" || (el.closest && el.closest('[role=search]'))) continue;
        if (seenFields.has(el)) continue;
        seenFields.add(el);
        
        const tag = (el.tagName || "").toLowerCase();
        const type = (el.type || "").toLowerCase();
        const ref = getRef(el);
        const looksLike = classify(el) || (tag === "select" ? "" : "text");
        
        fields.push({
          index: fields.length, ref, tag, type,
          kind: tag === "select" ? "native" : (type === "date" || looksLike === "dob") ? "date" : isCustomSelectTrigger(el) ? "custom-select" : "native",
          name: attr(el, "name") || findLabel(el) || el.id || "",
          looksLike: looksLike || "text",
          currentValue: el.value || "",
          required: !!(el.required || attr(el, "aria-required") === "true")
        });
      } catch (_) {}
    }

    const btnElements = collectAll(document, BUTTON_SELECTOR);
    const seenButtons = new Set();
    const buttons = [];
    for (const el of btnElements) {
      try {
        if (!el || !el.isConnected || !isVisible(el) || seenButtons.has(el)) continue;
        seenButtons.add(el);
        
        const label = (txt(el) || attr(el, "value") || attr(el, "aria-label") || "").trim();
        let score = 0;
        if ((el.type || "").toLowerCase() === "submit") score += 50;
        if (el.tagName.toLowerCase() === "button" && !el.type) score += 10;
        if (BTN_POS.test(label)) score += 100;
        if (BTN_NEG.test(label)) score -= 150;

        if (score > -9999) {
          buttons.push({ ref: getRef(el), label: label.slice(0, 60), score });
        }
      } catch (_) {}
    }
    buttons.sort((a, b) => b.score - a.score);
    
    const bestBtn = buttons[0] || null;
    sendToBridge(fields);

    return {
      url: location.href, title: document.title || "",
      fields, buttons, links: [],
      actionButtonRef: bestBtn ? bestBtn.ref : "", actionButtonName: bestBtn ? bestBtn.label : "",
      captchaDetected: false, captchaType: "", cookieBannerPresent: false, errorMessages: []
    };
  }

  function emptyResult() {
    return { url: location.href, fields: [], buttons: [], links: [], actionButtonRef: "", actionButtonName: "", captchaDetected: false, captchaType: "", cookieBannerPresent: false, hasIframes: false, iframeCount: 0, crossOriginIframeCount: 0, errorMessages: [] };
  }

  function sendToBridge(fields) {
    const payload = JSON.stringify(fields);
    let attempts = 0;
    (function trySend() {
      attempts++;
      try { if (window.AutomationBridge && typeof window.AutomationBridge.onFieldsDetected === "function") { window.AutomationBridge.onFieldsDetected(payload); return; } } catch (_) {}
      if (attempts < 5) setTimeout(trySend, 200);
    })();
  }

  async function fillFields(fieldsJson, optsJson) {
    g.__automation_active = true;
    const items = JSON.parse(fieldsJson || "[]");
    const opts = JSON.parse(optsJson || "{}");
    const typingDelay = opts.typingDelayMs || 50;
    const fieldDelay = opts.fieldDelayMs || 100;
    const results = [];
    for (const item of items) {
      try {
        const el = getByRef(item.ref);
        if (!el) { results.push({ ref: item.ref, ok: false, err: "not_found" }); continue; }
        const ok = await fillOne(el, item.value, { typingDelay, humanize: !!opts.humanize });
        results.push({ ref: item.ref, ok });
      } catch (e) { results.push({ ref: item.ref, ok: false, err: String(e) }); }
      if (fieldDelay) await sleep(fieldDelay);
    }
    return { filled: results.filter(r => r.ok).length, failed: results.filter(r => !r.ok).length, results };
  }

  async function fillOne(el, value, opts) {
    const tag = (el.tagName || "").toLowerCase();
    const type = (el.type || "").toLowerCase();
    
    // BUG 2 FIX: Bypass Custom Calendar (DOB)
    const isDate = type === "date" || type === "datetime-local" || classify(el) === "dob";
    if (isDate) {
      const wasReadonly = el.hasAttribute('readonly');
      if (wasReadonly) el.removeAttribute('readonly');
      
      try {
        el.focus(); // Original focus, bypass blocked focus
        const setter = Object.getOwnPropertyDescriptor(HTMLInputElement.prototype, "value").set;
        if (setter) setter.call(el, value);
        else el.value = value;
        
        el.dispatchEvent(new Event("input", { bubbles: true }));
        el.dispatchEvent(new Event("change", { bubbles: true }));
        el.dispatchEvent(new Event("blur", { bubbles: true }));
        if (el._valueTracker) el._valueTracker.setValue(value);
      } catch (_) {}
      
      if (wasReadonly) el.setAttribute('readonly', 'true');
      return true;
    }

    if (tag === "select") {
      const target = value.trim(), lower = target.toLowerCase();
      let matched = Array.from(el.options).find(o => (o.value || "").trim() === target) || Array.from(el.options).find(o => (o.textContent || "").trim() === target) || Array.from(el.options).find(o => (o.textContent || "").toLowerCase().includes(lower));
      if (!matched) return false;
      el.value = matched.value; el.dispatchEvent(new Event("input", { bubbles: true })); el.dispatchEvent(new Event("change", { bubbles: true })); return true;
    }
    if (tag === "input" && (type === "checkbox" || type === "radio")) {
      const want = /^(1|true|yes|on|checked)$/i.test(value.trim());
      if (el.checked !== want) { try { el.click(); } catch (_) { el.checked = want; el.dispatchEvent(new Event("change", { bubbles: true })); } }
      return true;
    }
    if (tag === "input" || tag === "textarea") {
      // BUG 1 FIX: Use readonly trick to prevent keyboard
      const wasReadonly = el.hasAttribute('readonly');
      el.setAttribute('readonly', 'true');
      try { originalFocus.call(el); } catch (_) {}
      
      const proto = el.tagName.toLowerCase() === "textarea" ? HTMLTextAreaElement.prototype : HTMLInputElement.prototype;
      const setter = Object.getOwnPropertyDescriptor(proto, "value").set;
      
      if (!opts.humanize) {
        try { if (setter) setter.call(el, value); else el.value = value; } catch (_) { el.value = value; }
        el.dispatchEvent(new Event("input", { bubbles: true })); el.dispatchEvent(new Event("change", { bubbles: true }));
      } else {
        let currentText = "";
        for (let i = 0; i < value.length; i++) {
          currentText += value[i];
          try { if (setter) setter.call(el, currentText); else el.value = currentText; } catch (_) { el.value = currentText; }
          el.dispatchEvent(new Event("input", { bubbles: true }));
          if (el._valueTracker) el._valueTracker.setValue(currentText);
          await sleep(opts.typingDelay * (0.5 + Math.random()));
        }
        el.dispatchEvent(new Event("change", { bubbles: true }));
      }
      
      if (!wasReadonly) el.removeAttribute('readonly');
      try { el.blur(); } catch (_) {}
      return true;
    }
    if (el.isContentEditable) {
      try { originalFocus.call(el); el.innerText = value; el.dispatchEvent(new InputEvent("input", { bubbles: true, data: value, inputType: "insertText" })); } catch (_) { el.textContent = value; el.dispatchEvent(new Event("input", { bubbles: true })); }
      return true;
    }
    if (isCustomSelectTrigger(el)) {
      const trigger = el.matches("button,[role=combobox],[aria-haspopup]") ? el : el.querySelector("button, [role=combobox], [aria-haspopup], .ctl, .select-trigger");
      if (!trigger) return false;
      simulateClick(trigger); await sleep(300);
      const target = value.trim().toLowerCase();
      const candidates = [];
      for (const s of ['[role=option]','[role=menuitem]','.MuiMenuItem-root','.ant-select-item-option','.chakra-menu__menuitem','[data-radix-collection-item]','.dd-i','.dropdown-item','.select-option']) {
        try { for (const n of document.querySelectorAll(s)) if (isVisible(n)) candidates.push(n); } catch (_) {}
      }
      let chosen = candidates.find(c => (txt(c) || "").toLowerCase() === target) || candidates.find(c => (txt(c) || "").toLowerCase().includes(target));
      if (chosen) { simulateClick(chosen); await sleep(250); return true; }
      simulateClick(trigger); await sleep(150); return false;
    }
    return false;
  }

  function simulateClick(el) {
    if (!el) return false;
    try { el.scrollIntoView({ block: "center", behavior: "instant" }); } catch (_) { try { el.scrollIntoView(); } catch (_) {} }
    try {
      const r = el.getBoundingClientRect(), cx = r.left + r.width / 2, cy = r.top + r.height / 2, opts = { bubbles: true, cancelable: true, clientX: cx, clientY: cy, button: 0, view: window };
      try { el.dispatchEvent(new PointerEvent("pointerdown", opts)); } catch (_) {}
      try { el.dispatchEvent(new MouseEvent("mousedown", opts)); } catch (_) {}
      try { el.dispatchEvent(new PointerEvent("pointerup", opts)); } catch (_) {}
      try { el.dispatchEvent(new MouseEvent("mouseup", opts)); } catch (_) {}
      el.click(); return true;
    } catch (_) { try { el.click(); return true; } catch (_) {} return false; }
  }

  async function clickNextAndSettle(optsJson) {
    const opts = JSON.parse(optsJson || "{}");
    const settleTimeoutMs = opts.settleTimeoutMs || 2000;
    let el = opts.actionRef ? getByRef(opts.actionRef) : null;
    if (!el) {
      const cands = collectAll(document, BUTTON_SELECTOR).map(b => ({ el: b, score: 0 })).filter(x => x.score >= 100);
      el = cands[0] ? cands[0].el : null;
    }
    if (!el) return { verdict: "no_button_found" };
    const urlBefore = location.href;
    simulateClick(el);
    const start = Date.now();
    while (Date.now() - start < settleTimeoutMs) {
      await sleep(100);
      if (location.href !== urlBefore) return { verdict: "navigated" };
    }
    return { verdict: "clicked_no_change" };
  }

  function dismissCookieBanner() { return { dismissed: false }; }

  let scanTimeout = null;
  function scheduleScan() {
    if (g.__automation_active) return;
    if (scanTimeout) return;
    scanTimeout = setTimeout(() => {
      scanTimeout = null;
      try { scanPage(); } catch (_) {}
    }, 500);
  }

  try {
    new MutationObserver(scheduleScan).observe(document.documentElement || document, { subtree: true, childList: true });
  } catch (_) {}

  g.__automationScan = scanPage;
  g.__automationFill = fillFields;
  g.__automationClickNextAndSettle = clickNextAndSettle;
  g.__automationDismissCookieBanner = dismissCookieBanner;

  try { scanPage(); } catch (_) {}
})();