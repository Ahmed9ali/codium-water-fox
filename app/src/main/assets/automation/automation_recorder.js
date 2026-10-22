/*
 * WaterFire Automation Recorder
 *
 * Injected only while the user is in Recording mode. Attaches focusin/click
 * listeners to the current document, classifies each interactable element,
 * and reports it to native via AutomationBridge.onElementRecorded.
 *
 * Self-contained: does NOT depend on automation_scanner.js being present.
 */
(function () {
  "use strict";
  const g = globalThis;

  if (g.__automationRecorderActive) return;
  g.__automationRecorderActive = true;

  const recordedElements = new WeakSet();

  // ---------- helpers ----------

  function isVisible(el) {
    if (!el || !el.isConnected) return false;
    if (el.getAttribute("aria-hidden") === "true") return false;
    const st = window.getComputedStyle(el);
    if (!st) return false;
    if (st.display === "none" || st.visibility === "hidden") return false;
    const r = el.getBoundingClientRect();
    if (r.width <= 0 && r.height <= 0 && el.offsetParent === null) return false;
    return true;
  }

  function shownText(el, max) {
    if (!el) return "";
    const limit = max || 100;
    const t = (el.innerText || "").replace(/\s+/g, " ").trim();
    return t.length > limit ? t.slice(0, limit - 1) + "…" : t;
  }

  function findLabel(el) {
    const parts = [];
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
    const by = el.getAttribute && el.getAttribute("aria-labelledby");
    if (by) {
      by.split(/\s+/).forEach(function (id) {
        const n = document.getElementById(id);
        if (n) {
          const t = shownText(n, 100);
          if (t && !parts.includes(t)) parts.push(t);
        }
      });
    }
    return parts.join(" ").trim();
  }

  // Mirrors automation_scanner.js classifyField + VaultRepository.normalizeFieldKey.
  function classify(el) {
    const tag = (el.tagName || "").toLowerCase();
    const type = (el.type || "text").toLowerCase();
    const auto = (el.getAttribute("autocomplete") || "").toLowerCase();
    const name = (el.getAttribute("name") || "").toLowerCase();
    const id = (el.id || "").toLowerCase();
    const ph = (el.getAttribute("placeholder") || "").toLowerCase();
    const aria = (el.getAttribute("aria-label") || "").toLowerCase();
    const lbl = findLabel(el).toLowerCase();
    const combined = name + " " + id + " " + ph + " " + aria + " " + lbl + " " + auto;

    if (type === "checkbox" || el.getAttribute("role") === "checkbox") {
      if (/\b(agree|terms|privacy|policy|accept|consent|condition|age)\b/i.test(combined)) return "termsCheckbox";
      return "checkbox";
    }
    if (type === "radio") {
      if (/\b(gender|sex|male|female)\b/i.test(combined)) return "gender";
      return "radio";
    }
    if (type === "password" || auto === "new-password" || auto === "current-password") {
      if (/\b(confirm|retype|repeat|verify|again|pass2|password2)\b/i.test(combined)) return "confirmPassword";
      return "password";
    }
    if (type === "email" || auto === "email" || /\b(e-?mail)\b/i.test(combined)) {
      if (/\b(confirm|retype|repeat|verify|again)\b/i.test(combined)) return "confirmEmail";
      return "email";
    }
    if (auto === "one-time-code" || /\b(otp|verification\s*code|security\s*code|2fa|pin\s*code)\b/i.test(combined)) return "otp";
    if (/\b(country\s*code|dial\s*code|isd|phone\s*code)\b/i.test(combined)) return "countryCode";
    if (type === "tel" || auto.indexOf("tel") >= 0 || /\b(phone|mobile|cell|whatsapp|tel)\b/i.test(combined)) {
      if (/\b(confirm|retype|repeat|verify)\b/i.test(combined)) return "confirmPhone";
      return "phone";
    }
    if (auto === "username" || /\b(user\s*name|userid|login|handle)\b/i.test(combined)) return "username";
    if (auto === "given-name" || /\b(first\s*name|fname|given\s*name)\b/i.test(combined)) return "firstName";
    if (auto === "family-name" || /\b(last\s*name|lname|surname|family)\b/i.test(combined)) return "lastName";
    if (auto === "name" || /\b(full\s*name|your\s*name|name)\b/i.test(combined)) return "fullName";
    if (/\b(subject|topic|reason)\b/i.test(combined)) return "subject";
    if (tag === "textarea" || /\b(message|comment|feedback|description|bio)\b/i.test(combined)) return "message";
    if (type === "date" || auto.indexOf("bday") >= 0 || /\b(dob|date\s*of\s*birth|birth\s*date|birthday)\b/i.test(combined)) return "dob";
    if (auto === "sex" || /\b(gender|sex)\b/i.test(combined)) return "gender";
    if (auto === "postal-code" || /\b(zip|postal|pin\s*code|pincode)\b/i.test(combined)) return "zip";
    if (auto === "address-level2" || /\b(city|town)\b/i.test(combined)) return "city";
    if (auto === "address-level1" || /\b(state|province|region)\b/i.test(combined)) return "state";
    if (auto.indexOf("country") >= 0 || /\b(country|nation)\b/i.test(combined)) return "country";
    if (auto.indexOf("address") >= 0 || auto.indexOf("street") >= 0 ||
        /\b(address|street|apartment|apt|building)\b/i.test(combined)) return "address";
    if (auto === "organization" || /\b(company|organization|business)\b/i.test(combined)) return "company";
    if (auto === "organization-title" || /\b(job\s*title|designation|position|role)\b/i.test(combined)) return "jobTitle";
    return "";
  }

  function cssPath(el) {
    if (!el || el.nodeType !== 1) return "";
    if (el.id) return "#" + CSS.escape(el.id);
    const parts = [];
    let cur = el;
    let depth = 0;
    while (cur && cur.nodeType === 1 && depth < 6) {
      let sel = cur.tagName.toLowerCase();
      if (cur.id) { sel += "#" + CSS.escape(cur.id); parts.unshift(sel); break; }
      const nameAttr = cur.getAttribute && cur.getAttribute("name");
      if (nameAttr && /^[A-Za-z0-9_-]+$/.test(nameAttr)) {
        sel += '[name="' + nameAttr + '"]';
      } else {
        const parent = cur.parentElement;
        if (parent) {
          const sameTag = [];
          for (let i = 0; i < parent.children.length; i++) {
            if (parent.children[i].tagName === cur.tagName) sameTag.push(parent.children[i]);
          }
          if (sameTag.length > 1) sel += ":nth-of-type(" + (sameTag.indexOf(cur) + 1) + ")";
        }
      }
      parts.unshift(sel);
      cur = cur.parentElement;
      depth++;
    }
    return parts.join(" > ");
  }

  function xpath(el) {
    if (!el || el.nodeType !== 1) return "";
    if (el.id) return '//*[@id="' + el.id + '"]';
    const parts = [];
    let cur = el;
    while (cur && cur.nodeType === 1) {
      let idx = 1;
      let sib = cur.previousElementSibling;
      while (sib) { if (sib.tagName === cur.tagName) idx++; sib = sib.previousElementSibling; }
      parts.unshift(cur.tagName.toLowerCase() + "[" + idx + "]");
      cur = cur.parentElement;
    }
    return "/" + parts.join("/");
  }

  // ---------- capture ----------

  function send(payload) {
    try {
      if (window.AutomationBridge && typeof window.AutomationBridge.onElementRecorded === "function") {
        window.AutomationBridge.onElementRecorded(JSON.stringify(payload));
      }
    } catch (_) {}
  }

  function captureField(el) {
    if (!el || el.nodeType !== 1) return;
    const tag = (el.tagName || "").toLowerCase();
    if (tag !== "input" && tag !== "textarea" && tag !== "select") return;
    if (tag === "input") {
      const t = (el.type || "text").toLowerCase();
      if (["hidden", "submit", "button", "reset", "image", "file", "search"].indexOf(t) >= 0) return;
    }
    if (!isVisible(el)) return;
    if (recordedElements.has(el)) return;
    recordedElements.add(el);

    const looksLike = classify(el);
    const label = findLabel(el)
      || el.getAttribute("aria-label")
      || el.getAttribute("placeholder")
      || el.getAttribute("name")
      || el.id
      || "";

    send({
      kind: "Field",
      looksLike: looksLike,
      tag: tag,
      domType: (el.type || "").toLowerCase(),
      name: el.getAttribute("name") || "",
      domId: el.id || "",
      placeholder: el.getAttribute("placeholder") || "",
      ariaLabel: el.getAttribute("aria-label") || "",
      displayLabel: label,
      cssSelector: cssPath(el),
      xpath: xpath(el),
    });
  }

  function onFocusIn(e) {
    captureField(e.target);
  }

  function onClick(e) {
    const el = e.target;
    if (!el) return;
    const tag = (el.tagName || "").toLowerCase();
    const isButton = tag === "button"
      || (tag === "input" && /submit|button/i.test(el.type || ""))
      || el.getAttribute("role") === "button";
    if (!isButton) return;

    const label = ((el.innerText || el.value || "") + "").trim();
    if (!/\b(next|continue|submit|sign\s*up|register|proceed|finish|done|save|send|log\s*in|sign\s*in|get\s*started)\b/i.test(label)) return;
    if (recordedElements.has(el)) return;
    recordedElements.add(el);

    send({
      kind: "ClickNext",
      buttonLabel: label.slice(0, 60),
      cssSelector: cssPath(el),
      xpath: xpath(el),
    });
  }

  document.addEventListener("focusin", onFocusIn, true);
  document.addEventListener("click", onClick, true);

  g.__automationRecorderStop = function () {
    document.removeEventListener("focusin", onFocusIn, true);
    document.removeEventListener("click", onClick, true);
    g.__automationRecorderActive = false;
  };
})();