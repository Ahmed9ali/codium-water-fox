(function () {
  "use strict";
  const g = globalThis;

  if (g.__automationScannerInstalled) {
    try { if (g.__automationScan) g.__automationScan(); } catch (_) {}
    return;
  }
  g.__automationScannerInstalled = true;

  let stableRefCounter = 0;
  const elementRefs = new WeakMap(); 
  let scanThrottleTimer = null;
  let lastScanTime = 0;
  const SCAN_THROTTLE_MS = 800;

  const isVisible = (el) => {
    if (!el || !el.isConnected) return false;
    try {
      if (el.getAttribute && el.getAttribute("aria-hidden") === "true") return false;
      const st = window.getComputedStyle(el);
      if (!st || st.display === "none" || st.visibility === "hidden" || st.opacity === "0") return false;
      const r = el.getBoundingClientRect();
      if (r.width <= 0 && r.height <= 0 && el.offsetParent === null) return false;
      return true;
    } catch (_) { return false; }
  };

  const isDisabled = (el) => {
    if (!el) return false;
    if (el.disabled === true || el.inert === true) return true;
    if (el.getAttribute && el.getAttribute("aria-disabled") === "true") return true;
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
    } catch (_) {}
    return parts.join(" ").trim();
  };

  const getRef = (el) => {
    if (elementRefs.has(el)) return elementRefs.get(el);
    stableRefCounter++;
    const ref = "r" + stableRefCounter;
    elementRefs.set(el, ref);
    try { el.setAttribute("data-nano-ref", ref); } catch (_) {}
    return ref;
  };

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
    
    // Prioritize explicit types or matching placeholders
    const combined = name + " " + id + " " + ph + " " + aria + " " + lbl + " " + auto + " " + role;

    if (type === "checkbox" || role === "checkbox") return "termsCheckbox";
    if (type === "radio" || role === "radio") return "gender";
    if (type === "password" || auto === "new-password" || auto === "current-password") return "password";
    if (type === "email" || auto === "email" || /\b(e-?mail|correo)\b/i.test(combined)) return "email";
    if (auto === "one-time-code" || /\b(otp|verification\s*code|security\s*code|2fa|pin\s*code|código)\b/i.test(combined)) return "otp";
    if (type === "tel" || auto.indexOf("tel") >= 0 || /\b(phone|mobile|cell|whatsapp|tel|telefono|celular)\b/i.test(combined)) return "phone";
    if (auto === "given-name" || /\b(first\s*name|fname|given\s*name|nombre)\b/i.test(combined)) return "firstName";
    if (auto === "family-name" || /\b(last\s*name|lname|surname|nom|apellido)\b/i.test(combined)) return "lastName";
    if (auto === "name" || /\b(full\s*name|your\s*name|name|nom|nombre)\b/i.test(combined)) return "fullName";
    if (auto === "username" || /\b(user\s*name|userid|login|handle|usuario)\b/i.test(combined)) return "username";
    if (tag === "textarea" || role === "textbox" || /\b(message|comment|feedback|description|bio|mensaje)\b/i.test(combined)) return "message";
    if (type === "date" || auto.indexOf("bday") >= 0 || /\b(dob|date\s*of\s*birth|birth\s*date|birthday|fecha)\b/i.test(combined)) return "dob";
    if (auto === "postal-code" || /\b(zip|postal|pin\s*code|pincode|postal|código postal)\b/i.test(combined)) return "zip";
    if (auto === "address-level2" || /\b(city|town|ville|ciudad)\b/i.test(combined)) return "city";
    if (auto === "address-level1" || /\b(state|province|region|estado)\b/i.test(combined)) return "state";
    if (auto.indexOf("country") >= 0 || /\b(country|nation|pays|país)\b/i.test(combined)) return "country";
    if (auto.indexOf("address") >= 0 || auto.indexOf("street") >= 0 || /\b(address|street|apartment|adresse|dirección)\b/i.test(combined)) return "address";
    return "";
  }

  function detectCaptcha() {
    const sel = ['iframe[src*="recaptcha" i]', 'iframe[src*="hcaptcha" i]', 'iframe[src*="challenges.cloudflare.com" i]', '.g-recaptcha', '.h-captcha', '.cf-turnstile'];
    for (const s of sel) {
      try {
        const nodes = document.querySelectorAll(s);
        for (const el of nodes) {
          if (!isVisible(el)) continue;
          return true;
        }
      } catch (_) {}
    }
    return false;
  }

  function scanPage() {
    if (document.readyState === "loading") return { fields: [], captchaDetected: false };

    const fieldSelector = 'input:not([type="hidden"]):not([type="submit"]):not([type="button"]):not([type="reset"]):not([type="image"]):not([type="file"]), textarea, select, [role="combobox"], [role="listbox"], [contenteditable="true"]';
    const buttonSelector = 'button, a, input[type="submit"], input[type="button"], input[type="image"], [role="button"], [onclick]';

    let rawFields = [];
    let rawButtons = [];
    try { rawFields = Array.from(document.querySelectorAll(fieldSelector)); } catch (_) {}
    try { rawButtons = Array.from(document.querySelectorAll(buttonSelector)); } catch (_) {}

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
        
        // Fallback for custom generic inputs with placeholders
        let finalLooksLike = looksLike;
        if (!finalLooksLike && (el.getAttribute("placeholder") || el.getAttribute("aria-label"))) {
            finalLooksLike = "text";
        }

        fields.push({
          index: fields.length,
          ref,
          tag,
          type,
          name: el.getAttribute("name") || findLabel(el) || el.id || "",
          id: el.id || "",
          looksLike: finalLooksLike || "text",
          currentValue: el.value || el.innerText || "",
          checked: el.checked || false
        });
      } catch (_) {}
    }

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
    function trySend() {
      attempts++;
      try {
        if (window.AutomationBridge && typeof window.AutomationBridge.onFieldsDetected === "function") {
          window.AutomationBridge.onFieldsDetected(payload);
          return;
        }
      } catch (_) {}
      if (attempts < 5) setTimeout(trySend, 200);
    }
    trySend();
  }

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

  try {
    new MutationObserver(() => scheduleScan()).observe(document.documentElement || document, { subtree: true, childList: true });
  } catch (_) {}

  try {
    window.addEventListener("popstate", scheduleScan);
    window.addEventListener("hashchange", scheduleScan);
    document.addEventListener("focusin", scheduleScan, true);
    document.addEventListener("click", () => setTimeout(scheduleScan, 300), true);
  } catch (_) {}

  try {
    setInterval(() => { if (document.visibilityState === "visible") scheduleScan(); }, 2000);
  } catch (_) {}

  g.__automationScan = scanPage;
  scanPage();
})();