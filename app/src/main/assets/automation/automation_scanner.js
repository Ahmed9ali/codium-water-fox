/*
 * Automation Scanner (v6 - Keyboard Block + Humanize Fix)
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
    THROTTLE_MS: 800,
    MIN_SIZE_PX: 2,
    MAX_BUTTONS_OUT: 25,
    MAX_LINKS_OUT: 20,
    FILL_DELAY_MS: 50,
    CLICK_SETTLE_POLL_MS: 100,
  };

  // BUG 1 FIX: Block soft keyboard during automation
  g.__automationDisableKeyboard = false;
  document.addEventListener("focusin", (e) => {
    if (g.__automationDisableKeyboard) {
      try { e.target.blur(); } catch (_) {}
    }
  }, true);

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

  function hasCountryCode(el) {
    if (!el || (el.type || "").toLowerCase() !== "tel") return false;
    if (el.closest("[class*=iti], .react-tel-input, [class*=phone-input], [class*=PhoneInput]")) return true;
    const par = el.parentElement;
    if (par) {
      const sel = par.querySelector("select");
      if (sel && /(\+\d|country|code)/i.test((sel.options[0] && sel.options[0].textContent) || "")) return true;
    }
    return false;
  }

  const CAPTCHA_SELECTORS = ['iframe[src*="recaptcha" i]', '.g-recaptcha', 'iframe[src*="hcaptcha" i]', '.h-captcha', 'iframe[src*="challenges.cloudflare.com" i]', '.cf-turnstile', '[class*="captcha" i]:not([class*="captcha-hidden"])', '[id*="captcha" i]'];
  function detectCaptcha(doc) {
    doc = doc || document;
    for (const s of CAPTCHA_SELECTORS) {
      try {
        for (const n of doc.querySelectorAll(s)) if (isVisible(n)) return "captcha";
      } catch (_) {}
    }
    return null;
  }

  const COOKIE_SELECTORS = ['[id*="onetrust" i]', '[class*="onetrust" i]', '[id*="cookie" i][role="dialog"]', '[class*="cookie" i][role="dialog"]', '[id*="consent" i]', '[class*="consent" i]', '[id*="gdpr" i]', '[class*="gdpr" i]', '#CybotCookiebotDialog', '.cc-window', '.cookiebanner', '.cookie-banner', '[aria-label*="cookie" i]', '[aria-label*="consent" i]'];
  function detectCookieBanner(doc) {
    doc = doc || document;
    for (const s of COOKIE_SELECTORS) {
      try {
        for (const n of doc.querySelectorAll(s)) {
          if (!isVisible(n)) continue;
          for (const b of n.querySelectorAll('button, [role=button], a')) {
            const t = txt(b).toLowerCase();
            if (/^(accept|agree|allow|ok|got it|i agree|continue|accept all|accept cookies)$/i.test(t)) return { node: n, acceptBtn: b };
          }
        }
      } catch (_) {}
    }
    return null;
  }

  const FIELD_SELECTOR = 'input:not([type=hidden]):not([type=submit]):not([type=button]):not([type=reset]):not([type=image]):not([type=file]), textarea, select, [role=combobox], [role=textbox], [contenteditable="true"], [contenteditable=""], .dd, .ctl, .select-trigger, [data-dropdown], [data-select]';
  const BUTTON_SELECTOR = 'button, a[role=button], input[type=submit], input[type=button], input[type=image], [role=button]:not(a), [onclick]';
  const LINK_SELECTOR = 'a[href]';

  function collectAll(doc, fieldSelector, buttonSelector, linkSelector) {
    const out = { fields: [], buttons: [], links: [] };
    try { out.fields = Array.from(doc.querySelectorAll(fieldSelector)); } catch (_) {}
    try { out.buttons = Array.from(doc.querySelectorAll(buttonSelector)); } catch (_) {}
    try { out.links = Array.from(doc.querySelectorAll(linkSelector)); } catch (_) {}

    let count = 0;
    function walk(root, depth) {
      if (!root || depth > CFG.MAX_SHADOW_DEPTH || count >= 3000) return;
      let nodeList;
      try { nodeList = root.querySelectorAll("*"); } catch (_) { return; }
      for (const el of nodeList) {
        count++;
        if (el.shadowRoot) {
          try { out.fields.push(...Array.from(el.shadowRoot.querySelectorAll(fieldSelector))); } catch (_) {}
          try { out.buttons.push(...Array.from(el.shadowRoot.querySelectorAll(buttonSelector))); } catch (_) {}
          try { out.links.push(...Array.from(el.shadowRoot.querySelectorAll(linkSelector))); } catch (_) {}
          walk(el.shadowRoot, depth + 1);
        }
      }
    }
    walk(doc, 0);
    return out;
  }

  function collectFromIframes(fieldSelector, buttonSelector, linkSelector) {
    const collected = { fields: [], buttons: [], links: [], iframeCount: 0, crossOriginCount: 0 };
    function recurse(doc, depth) {
      if (depth > CFG.MAX_IFRAME_DEPTH) return;
      try {
        for (const fr of doc.querySelectorAll("iframe")) {
          collected.iframeCount++;
          try {
            const cd = fr.contentDocument;
            if (!cd) { collected.crossOriginCount++; continue; }
            try { collected.fields.push(...Array.from(cd.querySelectorAll(fieldSelector))); } catch (_) {}
            try { collected.buttons.push(...Array.from(cd.querySelectorAll(buttonSelector))); } catch (_) {}
            try { collected.links.push(...Array.from(cd.querySelectorAll(linkSelector))); } catch (_) {}
            recurse(cd, depth + 1);
          } catch (_) { collected.crossOriginCount++; }
        }
      } catch (_) {}
    }
    recurse(document, 0);
    return collected;
  }

  function buildFieldRecord(el, idx) {
    const tag = (el.tagName || "").toLowerCase();
    const type = (el.type || "").toLowerCase();
    const ref = getRef(el);
    const looksLike = classify(el) || (tag === "select" ? "" : "text");
    const kind = (tag === "select") ? "native" : (tag === "input" && ["date", "datetime-local", "month", "time"].includes(type)) ? "date" : (tag === "input" && type === "tel" && hasCountryCode(el)) ? "phone-cc" : el.isContentEditable ? "contenteditable" : isCustomSelectTrigger(el) ? "custom-select" : "native";
    return {
      index: idx, ref, tag, type, kind,
      name: attr(el, "name") || findLabel(el) || el.id || "",
      id: el.id || "",
      placeholder: attr(el, "placeholder"),
      ariaLabel: attr(el, "aria-label"),
      looksLike: looksLike || "text",
      currentValue: readValue(el),
      checked: !!(el.checked),
      required: !!(el.required || attr(el, "aria-required") === "true"),
      disabled: isDisabled(el),
      readonly: !!(el.readOnly),
      hasCountryCode: hasCountryCode(el)
    };
  }

  function readValue(el) {
    const tag = (el.tagName || "").toLowerCase();
    const type = (el.type || "").toLowerCase();
    if (tag === "select") return el.options[el.selectedIndex] ? el.options[el.selectedIndex].textContent.trim() : "";
    if (type === "checkbox" || type === "radio") return el.checked ? "true" : "";
    if (el.isContentEditable) return (el.innerText || "").trim();
    return el.value != null ? String(el.value) : "";
  }

  function scoreButton(el) {
    if (!isVisible(el) || isDisabled(el)) return -9999;
    const label = (txt(el) || attr(el, "value") || attr(el, "aria-label") || "").trim();
    const type = (el.type || "").toLowerCase();
    let score = 0;
    if (type === "submit") score += 50;
    if (el.tagName.toLowerCase() === "button" && !type) score += 10;
    if (el.form) score += 20;
    if (BTN_POS.test(label)) score += 100;
    if (BTN_NEG.test(label)) score -= 150;
    return score;
  }

  function buildButtonRecord(el) {
    const label = (txt(el) || attr(el, "value") || attr(el, "aria-label") || "").trim().slice(0, 60);
    return {
      ref: getRef(el), label, type: (el.type || el.tagName || "").toLowerCase(), role: attr(el, "role"),
      isSubmit: (el.type || "").toLowerCase() === "submit", score: scoreButton(el),
      isNextCandidate: scoreButton(el) >= 100, disabled: isDisabled(el)
    };
  }

  function buildLinkRecord(el) {
    return { ref: getRef(el), text: txt(el).slice(0, 60), href: attr(el, "href"), isExternal: /^https?:/i.test(attr(el, "href")) && !attr(el, "href").startsWith(location.origin) };
  }

  async function scanPage() {
    if (document.readyState === "loading") {
      await new Promise(r => document.addEventListener("DOMContentLoaded", r, { once: true }));
    }

    let attempts = 0;
    while (attempts < 30 && document.querySelectorAll(FIELD_SELECTOR).length === 0) {
      await sleep(100);
      attempts++;
    }

    const fromDoc = collectAll(document, FIELD_SELECTOR, BUTTON_SELECTOR, LINK_SELECTOR);
    const fromFrames = collectFromIframes(FIELD_SELECTOR, BUTTON_SELECTOR, LINK_SELECTOR);

    const fields = [];
    const seenFields = new Set();
    for (const el of [...fromDoc.fields, ...fromFrames.fields]) {
      try {
        if (!el || !el.isConnected || !isVisible(el) || isDisabled(el)) continue;
        if (el.type === "search" || (el.closest && el.closest('[role=search]'))) continue;
        if (seenFields.has(el)) continue;
        seenFields.add(el);
        fields.push(buildFieldRecord(el, fields.length));
      } catch (_) {}
    }

    const buttonsScored = [];
    const seenButtons = new Set();
    for (const el of [...fromDoc.buttons, ...fromFrames.buttons]) {
      try {
        if (!el || !el.isConnected || seenButtons.has(el)) continue;
        seenButtons.add(el);
        const r = buildButtonRecord(el);
        if (r.score > -9999) buttonsScored.push(r);
      } catch (_) {}
    }
    buttonsScored.sort((a, b) => b.score - a.score);
    const buttons = buttonsScored.slice(0, CFG.MAX_BUTTONS_OUT);

    const links = [];
    const seenLinks = new Set();
    for (const el of [...fromDoc.links, ...fromFrames.links]) {
      try {
        if (!el || !el.isConnected || !isVisible(el) || seenLinks.has(el)) continue;
        seenLinks.add(el);
        links.push(buildLinkRecord(el));
        if (links.length >= CFG.MAX_LINKS_OUT) break;
      } catch (_) {}
    }

    const bestBtn = buttons[0] || null;
    const captchaType = detectCaptcha(document);
    const cookieBanner = detectCookieBanner(document);

    const errorMessages = [];
    try {
      const errEls = document.querySelectorAll('[role=alert], .error, [class*="error" i]:not([class*="no-error"]), [class*="invalid" i], .Mui-error, .ant-form-item-explain-error, [aria-invalid="true"]');
      for (const e of errEls) {
        if (isVisible(e)) {
          const t = txt(e).slice(0, 120);
          if (t && t.length > 2 && !errorMessages.includes(t)) errorMessages.push(t);
        }
        if (errorMessages.length >= 5) break;
      }
    } catch (_) {}

    sendToBridge(fields);

    return {
      url: location.href, title: document.title || "", readyState: document.readyState,
      fields, buttons, links,
      actionButtonRef: bestBtn ? bestBtn.ref : "", actionButtonName: bestBtn ? bestBtn.label : "",
      captchaDetected: !!captchaType, captchaType: captchaType || "",
      cookieBannerPresent: !!cookieBanner,
      hasIframes: fromFrames.iframeCount > 0, iframeCount: fromFrames.iframeCount,
      crossOriginIframeCount: fromFrames.crossOriginCount, errorMessages, timestamp: Date.now(),
    };
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
    // BUG 1 FIX: Disable keyboard while filling
    g.__automationDisableKeyboard = true;
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
    g.__automationDisableKeyboard = false;
    return { filled: results.filter(r => r.ok).length, failed: results.filter(r => !r.ok).length, results };
  }

  async function fillOne(el, value, opts) {
    const tag = (el.tagName || "").toLowerCase();
    const type = (el.type || "").toLowerCase();
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
    if (tag === "input" && ["date", "datetime-local", "month", "time"].includes(type)) {
      try {
        Object.getOwnPropertyDescriptor(HTMLInputElement.prototype, "value").set.call(el, value);
        el.dispatchEvent(new Event("input", { bubbles: true })); el.dispatchEvent(new Event("change", { bubbles: true })); el.dispatchEvent(new Event("blur", { bubbles: true })); return true;
      } catch (_) { return false; }
    }
    if (tag === "input" || tag === "textarea") {
      try { el.focus(); } catch (_) {}
      const proto = el.tagName.toLowerCase() === "textarea" ? HTMLTextAreaElement.prototype : HTMLInputElement.prototype;
      const setter = Object.getOwnPropertyDescriptor(proto, "value").set;
      if (!opts.humanize) {
        try { if (setter) setter.call(el, value); else el.value = value; } catch (_) { el.value = value; }
        el.dispatchEvent(new Event("input", { bubbles: true })); el.dispatchEvent(new Event("change", { bubbles: true }));
      } else {
        // BUG 3 FIX: Use native setter for every character so React/Vue detect the change
        let currentText = "";
        for (let i = 0; i < value.length; i++) {
          currentText += value[i];
          try { if (setter) setter.call(el, currentText); else el.value = currentText; } catch (_) { el.value = currentText; }
          el.dispatchEvent(new Event("input", { bubbles: true }));
          el.dispatchEvent(new Event("change", { bubbles: true }));
          await sleep(opts.typingDelay * (0.5 + Math.random()));
        }
      }
      try { el.blur(); } catch (_) {}
      return true;
    }
    if (el.isContentEditable) {
      try { el.focus(); el.innerText = value; el.dispatchEvent(new InputEvent("input", { bubbles: true, data: value, inputType: "insertText" })); } catch (_) { el.textContent = value; el.dispatchEvent(new Event("input", { bubbles: true })); }
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
    const pauseOnCaptcha = opts.pauseOnCaptcha !== false;
    let el = opts.actionRef ? getByRef(opts.actionRef) : null;
    if (!el) {
      const cands = Array.from(document.querySelectorAll(BUTTON_SELECTOR)).map(b => ({ el: b, score: scoreButton(b) })).filter(x => x.score >= 100).sort((a, b) => b.score - a.score);
      el = cands[0] ? cands[0].el : null;
    }
    if (!el) return { verdict: "no_button_found" };
    const urlBefore = location.href, titleBefore = document.title, nodesBefore = document.querySelectorAll("*").length;
    simulateClick(el);
    const start = Date.now();
    let urlChanged = false, nodesChanged = false, captchaAppeared = false, errorAppeared = false;
    while (Date.now() - start < settleTimeoutMs) {
      await sleep(CFG.CLICK_SETTLE_POLL_MS);
      if (location.href !== urlBefore) { urlChanged = true; break; }
      if (document.title !== titleBefore) nodesChanged = true;
      if (Math.abs(document.querySelectorAll("*").length - nodesBefore) > 15) nodesChanged = true;
      if (pauseOnCaptcha && detectCaptcha(document)) { captchaAppeared = true; break; }
      for (const e of document.querySelectorAll('[role=alert], .error, [class*="error" i]:not([class*="no-error"])')) {
        if (isVisible(e) && txt(e).length > 2) { errorAppeared = true; break; }
      }
      if (errorAppeared) break;
    }
    if (captchaAppeared) return { verdict: "captcha_blocked" };
    if (urlChanged) return { verdict: "navigated" };
    if (errorAppeared) return { verdict: "validation_error" };
    if (nodesChanged) return { verdict: "same_page" };
    return { verdict: "clicked_no_change" };
  }

  function dismissCookieBanner() {
    const banner = detectCookieBanner(document);
    if (!banner) return { dismissed: false };
    try { simulateClick(banner.acceptBtn); return { dismissed: true }; } catch (_) { return { dismissed: false }; }
  }

  let throttleTimer = null;
  let lastScanAt = 0;
  function scheduleScan() {
    const now = Date.now();
    const elapsed = now - lastScanAt;
    if (elapsed >= CFG.THROTTLE_MS) {
      lastScanAt = now;
      try { scanPage(); } catch (_) {}
    } else if (!throttleTimer) {
      throttleTimer = setTimeout(() => {
        throttleTimer = null;
        lastScanAt = Date.now();
        try { scanPage(); } catch (_) {}
      }, CFG.THROTTLE_MS - elapsed);
    }
  }

  try {
    new MutationObserver(scheduleScan).observe(document.documentElement || document, { subtree: true, childList: true });
  } catch (_) {}

  try {
    window.addEventListener("popstate", scheduleScan);
    window.addEventListener("hashchange", scheduleScan);
    document.addEventListener("focusin", scheduleScan, true);
    document.addEventListener("click", () => setTimeout(scheduleScan, 300), true);
  } catch (_) {}

  g.__automationScan = scanPage;
  g.__automationFill = fillFields;
  g.__automationClickNextAndSettle = clickNextAndSettle;
  g.__automationDismissCookieBanner = dismissCookieBanner;

  try { scanPage(); } catch (_) {}
})();