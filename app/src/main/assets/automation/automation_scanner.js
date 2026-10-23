(function () {
  "use strict";
  const g = globalThis;

  // === SINGLETON GUARD: prevent duplicate installations ===
  if (g.__automationScannerInstalled) {
    try { if (g.__automationScan) g.__automationScan(); } catch (_) {}
    return;
  }
  g.__automationScannerInstalled = true;

  let stableRefCounter = 0;
  const elementRefs = new WeakMap();   // element -> stable ref (never changes)
  let scanThrottleTimer = null;
  let lastScanTime = 0;
  const SCAN_THROTTLE_MS = 800;
  let consecutiveEmptyCount = 0;

  // ============ DOM HELPERS ============

  const isVisible = (el) => {
    if (!el || !el.isConnected) return false;
    try {
      if (el.getAttribute && el.getAttribute("aria-hidden") === "true") return false;
      const st = window.getComputedStyle(el);
      if (!st || st.display === "none" || st.visibility === "hidden") return false;
      const r = el.getBoundingClientRect();
      if (r.width <= 0 && r.height <= 0 && el.offsetParent === null) return false;
      return true;
    } catch (_) { return false; }
  };

  const isDisabled = (el) => {
    if (!el) return false;
    if (el.disabled === true || el.inert === true) return true;
    if (el.getAttribute && el.getAttribute("aria-disabled") === "true") return true;
    const fs = el.closest && el.closest("fieldset[disabled]");
    if (fs) {
      const legend = fs.querySelector(":scope > legend");
      if (!legend || !legend.contains(el)) return true;
    }
    return false;
  };

  const getText = (el) => {
    if (!el) return "";
    return ((el.innerText || el.textContent || "").replace(/\s+/g, " ").trim());
  };

  const findLabel = (el) => {
    if (!el) return "";
    const parts = [];
    try {
      if (el.labels && el.labels.length) {
        for (const l of el.labels) {
          const t = getText(l).slice(0, 100);
          if (t) parts.push(t);
        }
      }
      if (el.id) {
        try {
          const explicit = document.querySelector('label[for="' + CSS.escape(el.id) + '"]');
          if (explicit) {
            const t = getText(explicit).slice(0, 100);
            if (t && !parts.includes(t)) parts.push(t);
          }
        } catch (_) {}
      }
      const wrapping = el.closest && el.closest("label");
      if (wrapping) {
        const t = getText(wrapping).slice(0, 100);
        if (t && !parts.includes(t)) parts.push(t);
      }
      const byId = el.getAttribute && el.getAttribute("aria-labelledby");
      if (byId) {
        byId.split(/\s+/).forEach(id => {
          const n = document.getElementById(id);
          if (n) {
            const t = getText(n).slice(0, 100);
            if (t && !parts.includes(t)) parts.push(t);
          }
        });
      }
    } catch (_) {}
    return parts.join(" ").trim();
  };

  const getRef = (el) => {
    if (elementRefs.has(el)) return elementRefs.get(el);
    stableRefCounter++;
    const ref = "r" + stableRefCounter;
    elementRefs.set(el, ref);
    // Also set attribute for legacy fill functions
    try { el.setAttribute("data-nano-ref", ref); } catch (_) {}
    return ref;
  };

  // ============ CLASSIFICATION ============

  function classify(el) {
    const tag = (el.tagName || "").toLowerCase();
    const type = (el.type || "text").toLowerCase();
    const auto = (el.getAttribute("autocomplete") || "").toLowerCase();
    const name = (el.getAttribute("name") || "").toLowerCase();
    const id = (el.id || "").toLowerCase();
    const ph = (el.getAttribute("placeholder") || "").toLowerCase();
    const aria = (el.getAttribute("aria-label") || "").toLowerCase();
    const lbl = findLabel(el).toLowerCase();
    const role = (el.getAttribute("role") || "").toLowerCase();
    const combined = name + " " + id + " " + ph + " " + aria + " " + lbl + " " + auto + " " + role;

    if (type === "checkbox" || role === "checkbox") {
      if (/\b(agree|terms|privacy|policy|accept|consent|condition|age|acepto|términos|conditions|datenschutz)\b/i.test(combined)) return "termsCheckbox";
      return "checkbox";
    }
    if (type === "radio" || role === "radio") {
      if (/\b(gender|sex|male|female|género|sexo|homme|femme|geschlecht)\b/i.test(combined)) return "gender";
      return "radio";
    }
    if (type === "password" || auto === "new-password" || auto === "current-password") {
      if (/\b(confirm|retype|repeat|verify|again|pass2|password2|contraseña|mot de passe|senha|wiederholen)\b/i.test(combined)) return "confirmPassword";
      return "password";
    }
    if (type === "email" || auto === "email" || /\b(e-?mail|correo|mail|courriel)\b/i.test(combined)) {
      if (/\b(confirm|retype|repeat|verify|again)\b/i.test(combined)) return "confirmEmail";
      return "email";
    }
    if (auto === "one-time-code" || /\b(otp|verification\s*code|security\s*code|2fa|pin\s*code|código|codice)\b/i.test(combined)) return "otp";
    if (type === "tel" || auto.indexOf("tel") >= 0 || /\b(phone|mobile|cell|whatsapp|tel|téléphone|telefono|celular|telefon)\b/i.test(combined)) {
      if (/\b(confirm|retype|repeat|verify)\b/i.test(combined)) return "confirmPhone";
      return "phone";
    }
    if (auto === "given-name" || /\b(first\s*name|fname|given\s*name|prénom|nombre|vorname)\b/i.test(combined)) return "firstName";
    if (auto === "family-name" || /\b(last\s*name|lname|surname|family|nom|apellido|nachname)\b/i.test(combined)) return "lastName";
    if (auto === "name" || /\b(full\s*name|your\s*name|name|nom|nombre)\b/i.test(combined)) return "fullName";
    if (auto === "username" || /\b(user\s*name|userid|login|handle|utilisateur|usuario|benutzer)\b/i.test(combined)) return "username";
    if (tag === "textarea" || role === "textbox" || /\b(message|comment|feedback|description|bio|mensaje|message|nachricht)\b/i.test(combined)) return "message";
    if (/\b(subject|topic|reason|sujet|asunto|betreff)\b/i.test(combined)) return "subject";
    if (type === "date" || auto.indexOf("bday") >= 0 || /\b(dob|date\s*of\s*birth|birth\s*date|birthday|fecha|naissance|geburtstag)\b/i.test(combined)) return "dob";
    if (auto === "postal-code" || /\b(zip|postal|pin\s*code|pincode|code postal|código postal|postleitzahl)\b/i.test(combined)) return "zip";
    if (auto === "address-level2" || /\b(city|town|ville|ciudad|stadt)\b/i.test(combined)) return "city";
    if (auto === "address-level1" || /\b(state|province|region|état|estado|bundesland)\b/i.test(combined)) return "state";
    if (auto.indexOf("country") >= 0 || /\b(country|nation|pays|país|land)\b/i.test(combined)) return "country";
    if (auto.indexOf("address") >= 0 || auto.indexOf("street") >= 0 || /\b(address|street|apartment|apt|building|adresse|dirección|endereço|straße)\b/i.test(combined)) return "address";
    if (auto.indexOf("cc-") >= 0 || /\b(card\s*number|cc-num|creditcard|tarjeta|kreditkarte)\b/i.test(combined)) return "cc-number";
    if (/\b(expiry|expiration|cvv|cvc|security\s*code|fecha\s*vencimiento)\b/i.test(combined)) return "cc-exp";

    // Custom components
    if ((tag === "div" || tag === "span" || tag === "custom-element") && (role === "combobox" || role === "listbox" || el.isContentEditable)) return "text";
    return "";
  }

  // ============ CAPTCHA DETECTION ============

  function detectCaptcha() {
    const sel = [
      'iframe[src*="recaptcha" i]', 'iframe[src*="hcaptcha" i]', 'iframe[src*="challenges.cloudflare.com" i]',
      'iframe[src*="turnstile" i]', 'iframe[src*="arkoselabs" i]', '.g-recaptcha', '.h-captcha', '.cf-turnstile'
    ];
    for (const s of sel) {
      try {
        const nodes = document.querySelectorAll(s);
        for (const el of nodes) {
          if (!isVisible(el)) continue;
          const token = el.querySelector && el.querySelector('textarea[name*="captcha-response" i]');
          if (token && token.value && token.value.trim().length > 10) continue;
          return true;
        }
      } catch (_) {}
    }
    return false;
  }

  // ============ MAIN SCAN ============

  function scanPage() {
    // Do not scan if DOM isn't ready
    if (document.readyState === "loading") return { fields: [], captchaDetected: false };

    // Fast selector-based collection
    const fieldSelector = 'input:not([type="hidden"]):not([type="submit"]):not([type="button"]):not([type="reset"]):not([type="image"]):not([type="file"]), textarea, select, [role="combobox"], [role="listbox"], [contenteditable="true"]';
    const buttonSelector = 'button, a[href], input[type="submit"], input[type="button"], [role="button"]';

    let rawFields = [];
    let rawButtons = [];
    try { rawFields = Array.from(document.querySelectorAll(fieldSelector)); } catch (_) {}
    try { rawButtons = Array.from(document.querySelectorAll(buttonSelector)); } catch (_) {}

    // Pierce shadow roots
    try {
      const allEls = document.querySelectorAll("*");
      for (const el of allEls) {
        if (el.shadowRoot) {
          try { rawFields.push(...Array.from(el.shadowRoot.querySelectorAll(fieldSelector))); } catch (_) {}
          try { rawButtons.push(...Array.from(el.shadowRoot.querySelectorAll(buttonSelector))); } catch (_) {}
        }
      }
    } catch (_) {}

    const fields = [];
    const refMap = new Map();

    for (const el of rawFields) {
      try {
        if (!isVisible(el) || isDisabled(el)) continue;
        const tag = (el.tagName || "").toLowerCase();
        const type = (el.type || "text").toLowerCase();
        if (type === "search" || el.closest('[role="search"], search')) continue;
        const looksLike = classify(el);
        if (looksLike === "checkbox") continue;
        const ref = getRef(el);
        refMap.set(ref, el);
        fields.push({
          index: fields.length,
          ref,
          tag,
          type,
          name: el.getAttribute("name") || findLabel(el) || el.id || "",
          id: el.id || "",
          looksLike: looksLike || "text",
          currentValue: el.value || el.innerText || "",
          checked: el.checked || false
        });
      } catch (_) {}
    }

    // Find best action button
    let bestBtn = null;
    let bestScore = -1;
    const POSITIVE = /\b(next|continue|submit|sign\s*up|signup|register|create|join|proceed|verify|confirm|finish|done|save|send|login|log\s*in|suivant|enviar|siguiente|weiter)\b/i;
    const NEGATIVE = /\b(back|previous|prev|cancel|close|reset|clear|delete|remove|log\s*out|sign\s*out|skip|forgot|resend)\b/i;

    for (const btn of rawButtons) {
      try {
        if (!isVisible(btn) || isDisabled(btn)) continue;
        const type = (btn.type || "").toLowerCase();
        const label = ((btn.innerText || btn.value || btn.getAttribute("aria-label") || "") + "").trim();
        if (NEGATIVE.test(label)) continue;
        let score = 0;
        if (type === "submit") score += 50;
        if (POSITIVE.test(label)) score += 100;
        if (btn.form) score += 20;
        if (score > bestScore) { bestScore = score; bestBtn = btn; }
      } catch (_) {}
    }

    let actionRef = null;
    let actionName = "";
    if (bestBtn) {
      actionRef = getRef(bestBtn);
      refMap.set(actionRef, bestBtn);
      actionName = (bestBtn.innerText || bestBtn.value || bestBtn.id || "Submit").trim().slice(0, 40);
    }

    const captchaDetected = detectCaptcha();

    // === SEND TO BRIDGE (with retry) ===
    sendToBridge(fields, captchaDetected);

    return {
      url: window.location.href,
      title: document.title || "",
      fields: fields,
      captchaDetected: captchaDetected,
      actionButtonRef: actionRef,
      actionButtonName: actionName,
    };
  }

  function sendToBridge(fields, captchaDetected) {
    const payload = JSON.stringify(fields);
    let attempts = 0;
    const maxAttempts = 5;

    function trySend() {
      attempts++;
      try {
        if (window.AutomationBridge && typeof window.AutomationBridge.onFieldsDetected === "function") {
          window.AutomationBridge.onFieldsDetected(payload);
          return;
        }
      } catch (_) {}
      if (attempts < maxAttempts) {
        setTimeout(trySend, 200);
      }
    }
    trySend();
  }

  // ============ THROTTLE SCANNING ============

  function scheduleScan() {
    const now = Date.now();
    const elapsed = now - lastScanTime;

    if (elapsed >= SCAN_THROTTLE_MS) {
      lastScanTime = now;
      try { scanPage(); } catch (_) {}
    } else if (!scanThrottleTimer) {
      scanThrottleTimer = setTimeout(() => {
        scanThrottleTimer = null;
        lastScanTime = Date.now();
        try { scanPage(); } catch (_) {}
      }, SCAN_THROTTLE_MS - elapsed);
    }
  }

  // ============ MUTATION OBSERVER (childList only, NOT attributes) ============

  try {
    new MutationObserver((records) => {
      for (const r of records) {
        if (r.addedNodes && r.addedNodes.length > 0) {
          scheduleScan();
          return;
        }
      }
    }).observe(document.documentElement || document, {
      subtree: true,
      childList: true
    });
  } catch (_) {}

  // ============ FALLBACK TRIGGERS ============

  // SPA route changes
  try {
    window.addEventListener("popstate", scheduleScan);
    window.addEventListener("hashchange", scheduleScan);
  } catch (_) {}

  // User interactions
  try {
    document.addEventListener("focusin", scheduleScan, true);
    document.addEventListener("click", () => setTimeout(scheduleScan, 300), true);
  } catch (_) {}

  // Heartbeat (every 2s while page visible)
  try {
    setInterval(() => {
      if (document.visibilityState === "visible" && !document.hidden) {
        scheduleScan();
      }
    }, 2000);
  } catch (_) {}

  // Initial scan when ready
  function initialScan() {
    if (document.readyState === "loading") {
      document.addEventListener("DOMContentLoaded", () => {
        lastScanTime = Date.now();
        try { scanPage(); } catch (_) {}
      }, { once: true });
    } else {
      lastScanTime = Date.now();
      try { scanPage(); } catch (_) {}
    }
  }

  // ============ LEGACY SUPPORT (used by runAutomationLoop) ============

  g.__automationFill = async function (rawListJson, rawOptionsJson) {
    try {
      const list = typeof rawListJson === "string" ? JSON.parse(rawListJson) : (rawListJson || []);
      const options = typeof rawOptionsJson === "string" ? JSON.parse(rawOptionsJson) : (rawOptionsJson || {});
      const typingDelay = typeof options.typingDelayMs === "number" ? options.typingDelayMs : 60;
      const fieldDelay = typeof options.fieldDelayMs === "number" ? options.fieldDelayMs : 120;
      const receipts = [];
      for (const item of list) {
        try {
          let el = document.querySelector('[data-nano-ref="' + item.ref + '"]');
          if (!el) {
            const all = document.querySelectorAll(fieldSelector);
            if (typeof item.index === "number" && all[item.index]) el = all[item.index];
          }
          if (!el) { receipts.push({ ref: item.ref, ok: false }); continue; }
          el.focus();
          const desc = Object.getOwnPropertyDescriptor(window.HTMLInputElement.prototype, "value");
          if (desc && desc.set) desc.set.call(el, item.value);
          else el.value = item.value;
          el.dispatchEvent(new Event("input", { bubbles: true }));
          el.dispatchEvent(new Event("change", { bubbles: true }));
          el.dispatchEvent(new Event("blur", { bubbles: true }));
          receipts.push({ ref: item.ref, ok: true });
        } catch (_) { receipts.push({ ref: item.ref, ok: false }); }
        if (fieldDelay > 0) await new Promise(r => setTimeout(r, fieldDelay));
      }
      return { filledCount: receipts.filter(r => r.ok).length, receipts };
    } catch (e) {
      return { filledCount: 0, receipts: [], error: e.message };
    }
  };

  g.__automationClickNextAndSettle = async function (rawOptionsJson) {
    try {
      const options = typeof rawOptionsJson === "string" ? JSON.parse(rawOptionsJson) : (rawOptionsJson || {});
      const settleTimeout = typeof options.settleTimeoutMs === "number" ? options.settleTimeoutMs : 2000;
      let btn = options.actionRef ? document.querySelector('[data-nano-ref="' + options.actionRef + '"]') : null;
      if (!btn) btn = document.querySelector('button[type="submit"], input[type="submit"]');
      if (!btn) return { clicked: false, verdict: "no_button_found" };
      const urlBefore = window.location.href;
      btn.click();
      await new Promise(r => setTimeout(r, settleTimeout));
      return {
        clicked: true,
        urlChanged: window.location.href !== urlBefore,
        verdict: "changed"
      };
    } catch (e) {
      return { clicked: false, verdict: "error", error: e.message };
    }
  };

  // ============ EXPORT ============
  g.__automationScan = scanPage;
  initialScan();
})();