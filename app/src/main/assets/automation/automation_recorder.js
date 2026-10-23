(function () {
  "use strict";
  const g = globalThis;

  if (typeof g.__automationRecorderStop === "function" && g.__automationRecorderActive) {
    try { g.__automationRecorderStop(); } catch (_) {}
  }

  // Time-based dedup: prevents double-capture (focus+click) but allows retry after 800 ms
  const recentlySent = new WeakMap();
  const DEDUP_MS = 800;

  function shouldSkip(el) {
    try {
      const now = Date.now();
      const last = recentlySent.get(el);
      if (last && now - last < DEDUP_MS) return true;
      return false;
    } catch (_) { return false; }
  }

  function markSent(el) {
    try { recentlySent.set(el, Date.now()); } catch (_) {}
  }

  function isVisible(el) {
    if (!el || !el.isConnected) return false;
    try {
      if (el.getAttribute("aria-hidden") === "true") return false;
      const st = window.getComputedStyle(el);
      if (!st || st.display === "none" || st.visibility === "hidden" || st.opacity === "0") return false;
      const r = el.getBoundingClientRect();
      if (r.width <= 0 && r.height <= 0 && el.offsetParent === null) return false;
      return true;
    } catch (_) { return false; }
  }

  function shownText(el, max) {
    if (!el) return "";
    try {
      const limit = max || 100;
      const t = (el.innerText || el.textContent || "").replace(/\s+/g, " ").trim();
      return t.length > limit ? t.slice(0, limit - 1) : t;
    } catch (_) { return ""; }
  }

  function findLabel(el) {
    const parts = [];
    try {
      if (el.labels && el.labels.length) {
        for (let i = 0; i < el.labels.length; i++) {
          const t = shownText(el.labels[i], 100);
          if (t) parts.push(t);
        }
      }
      if (el.id) {
        try {
          const explicit = document.querySelector('label[for="' + CSS.escape(el.id) + '"]');
          if (explicit) {
            const t = shownText(explicit, 100);
            if (t && !parts.includes(t)) parts.push(t);
          }
        } catch (_) {}
      }
      const wrapping = el.closest && el.closest("label");
      if (wrapping) {
        const t = shownText(wrapping, 100);
        if (t && !parts.includes(t)) parts.push(t);
      }
    } catch (_) {}
    return parts.join(" ").trim().slice(0, 100);
  }

  function classify(el) {
    try {
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
    } catch (_) { return ""; }
  }

  function cssPath(el) {
    try {
      if (!el || el.nodeType !== 1) return "";
      if (el.id) return ("#" + CSS.escape(el.id)).slice(0, 500);
      const parts = [];
      let cur = el;
      let depth = 0;
      while (cur && cur.nodeType === 1 && depth < 6) {
        let sel = cur.tagName.toLowerCase();
        const nameAttr = cur.getAttribute && cur.getAttribute("name");
        if (nameAttr) { sel += '[name="' + String(nameAttr).slice(0, 60) + '"]'; }
        else {
          const parent = cur.parentElement;
          if (parent) {
            const sameTag = Array.from(parent.children).filter(c => c.tagName === cur.tagName);
            if (sameTag.length > 1) sel += ':nth-of-type(' + (sameTag.indexOf(cur) + 1) + ')';
          }
        }
        parts.unshift(sel);
        cur = cur.parentElement;
        depth++;
      }
      return parts.join(" > ").slice(0, 500);
    } catch (_) { return ""; }
  }

  function xpath(el) {
    try {
      if (!el || el.nodeType !== 1) return "";
      if (el.id) return ('//*[@id="' + String(el.id).slice(0, 60) + '"]').slice(0, 500);
      const parts = [];
      let cur = el;
      while (cur && cur.nodeType === 1) {
        const tag = cur.tagName.toLowerCase();
        const nameAttr = cur.getAttribute && cur.getAttribute("name");
        if (nameAttr) { parts.unshift(tag + '[@name="' + String(nameAttr).slice(0, 60) + '"]'); break; }
        let idx = 1;
        let sib = cur.previousElementSibling;
        while (sib) { if (sib.tagName === cur.tagName) idx++; sib = sib.previousElementSibling; }
        parts.unshift(tag + "[" + idx + "]");
        cur = cur.parentElement;
      }
      return ("/" + parts.join("/")).slice(0, 500);
    } catch (_) { return ""; }
  }

  function send(payload) {
    try {
      if (window.AutomationBridge && typeof window.AutomationBridge.onElementRecorded === "function") {
        window.AutomationBridge.onElementRecorded(JSON.stringify(payload));
        return true;
      }
    } catch (e) {
      console.warn("[Automation] send failed:", e);
    }
    return false;
  }

  function captureField(el) {
    try {
      if (!el || el.nodeType !== 1) return;

      let target = el;
      if (target.tagName === "DIV" || target.tagName === "SPAN" || target.tagName === "LABEL") {
        try {
          const inner = target.querySelector('input, textarea, select, [role="combobox"]');
          if (inner) target = inner;
        } catch (_) {}
      }

      const tag = (target.tagName || "").toLowerCase();
      const role = (target.getAttribute("role") || "").toLowerCase();
      const isEditable = target.isContentEditable === true;
      if (tag !== "input" && tag !== "textarea" && tag !== "select" && !isEditable && role !== "combobox") return;

      if (tag === "input") {
        const t = (target.type || "text").toLowerCase();
        if (["hidden", "submit", "button", "reset", "image", "file", "search"].indexOf(t) >= 0) return;
      }
      if (!isVisible(target)) return;
      if (shouldSkip(target)) return;

      // Each sub-step is isolated so partial failure still yields a step
      let looksLike = "";
      try { looksLike = classify(target); } catch (_) {}

      let label = "";
      try {
        label = findLabel(target)
          || target.getAttribute("aria-label")
          || target.getAttribute("placeholder")
          || target.getAttribute("name")
          || target.id
          || "";
      } catch (_) {}

      let css = "";
      try { css = cssPath(target); } catch (_) {}

      let xpathVal = "";
      try { xpathVal = xpath(target); } catch (_) {}

      let absIndex = -1;
      try {
        const allInputs = Array.from(document.querySelectorAll('input:not([type="hidden"]), textarea, select, [role="combobox"], [contenteditable="true"]'));
        absIndex = allInputs.indexOf(target);
      } catch (_) {}

      const payload = {
        kind: "Field",
        looksLike: String(looksLike || "").slice(0, 50),
        tag: String(tag || "").slice(0, 30),
        domType: String(target.type || "").slice(0, 30),
        name: String(target.getAttribute("name") || "").slice(0, 100),
        domId: String(target.id || "").slice(0, 100),
        placeholder: String(target.getAttribute("placeholder") || "").slice(0, 100),
        ariaLabel: String(target.getAttribute("aria-label") || "").slice(0, 100),
        displayLabel: String(label || "").slice(0, 100),
        cssSelector: css,
        xpath: xpathVal,
        index: absIndex
      };

      const ok = send(payload);
      if (ok) markSent(target);
    } catch (e) {
      console.warn("[Automation] captureField error:", e);
    }
  }

  function isNavigationTrigger(el) {
    try {
      const tag = el.tagName.toLowerCase();
      const type = (el.type || "").toLowerCase();
      const role = (el.getAttribute("role") || "").toLowerCase();
      const href = el.getAttribute("href");
      const ariaHaspopup = el.getAttribute("aria-haspopup");
      const ariaExpanded = el.getAttribute("aria-expanded");

      if (ariaHaspopup !== null) return false;
      if (ariaExpanded !== null) return false;
      if (role === "combobox" || role === "listbox" || role === "tab") return false;
      if (tag === "a" && href && !href.startsWith("#") && !href.startsWith("javascript:")) return true;
      if (tag === "button" && (type === "submit" || type === "")) return !!el.form;
      if (tag === "input" && (type === "submit" || type === "image")) return true;
      return false;
    } catch (_) { return false; }
  }

  function onClick(e) {
    try {
      const el = e.target.closest('button, a, input[type="submit"], input[type="button"], input[type="image"], [role="button"]');
      if (!el) return;

      if (isNavigationTrigger(el)) {
        e.preventDefault();
        e.stopPropagation();
        e.stopImmediatePropagation();
      }

      if (shouldSkip(el)) return;

      const label = ((el.innerText || el.value || "") + "").trim().slice(0, 60);
      const payload = {
        kind: "ClickNext",
        buttonLabel: label,
        cssSelector: cssPath(el),
        xpath: xpath(el),
      };
      const ok = send(payload);
      if (ok) markSent(el);
    } catch (e) {
      console.warn("[Automation] onClick error:", e);
    }
  }

  function onFormSubmit(e) {
    try {
      e.preventDefault();
      e.stopPropagation();
      e.stopImmediatePropagation();
    } catch (_) {}
  }

  function onKeyDown(e) {
    try {
      if (e.key === "Enter") {
        const tag = (e.target.tagName || "").toLowerCase();
        if (tag === "input" || tag === "textarea") {
          e.preventDefault();
          e.stopPropagation();
        }
      }
    } catch (_) {}
  }

  function attach() {
    try {
      document.addEventListener("focusin", onFocusIn, true);
      document.addEventListener("click", onClick, true);
      document.addEventListener("submit", onFormSubmit, true);
      document.addEventListener("keydown", onKeyDown, true);
    } catch (_) {}
  }

  function onFocusIn(e) {
    try { captureField(e.target); } catch (_) {}
  }

  function detach() {
    try {
      document.removeEventListener("focusin", onFocusIn, true);
      document.removeEventListener("click", onClick, true);
      document.removeEventListener("submit", onFormSubmit, true);
      document.removeEventListener("keydown", onKeyDown, true);
    } catch (_) {}
  }

  g.__automationRecorderActive = true;
  g.__automationRecorderPaused = false;
  attach();

  // Health check: log if bridge disappears while recording
  try {
    setInterval(() => {
      if (g.__automationRecorderActive && !g.__automationRecorderPaused) {
        if (!window.AutomationBridge || typeof window.AutomationBridge.onElementRecorded !== "function") {
          console.warn("[Automation] bridge missing during recording — will auto-recover on next page load");
        }
      }
    }, 5000);
  } catch (_) {}

  g.__automationRecorderStop = function () {
    detach();
    g.__automationRecorderActive = false;
    g.__automationRecorderPaused = false;
  };

  g.__automationRecorderPause = function () {
    if (!g.__automationRecorderActive || g.__automationRecorderPaused) return;
    detach();
    g.__automationRecorderPaused = true;
  };

  g.__automationRecorderResume = function () {
    if (!g.__automationRecorderActive || !g.__automationRecorderPaused) return;
    attach();
    g.__automationRecorderPaused = false;
  };
})();