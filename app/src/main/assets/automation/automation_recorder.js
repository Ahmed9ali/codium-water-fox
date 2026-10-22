(function () {
  "use strict";
  const g = globalThis;
  if (g.__automationRecorderActive) return;
  g.__automationRecorderActive = true;

  const recordedElements = new WeakSet();

  function isVisible(el) {
    if (!el || !el.isConnected) return false;
    if (el.getAttribute("aria-hidden") === "true") return false;
    const st = window.getComputedStyle(el);
    if (!st || st.display === "none" || st.visibility === "hidden") return false;
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
    return parts.join(" ").trim();
  }

  // Multi-language classification
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

    // Terms
    if (type === "checkbox" || el.getAttribute("role") === "checkbox") {
      if (/\b(agree|terms|privacy|policy|accept|consent|condition|age|acepto|términos|conditions)\b/i.test(combined)) return "termsCheckbox";
      return "checkbox";
    }
    // Radio
    if (type === "radio") {
      if (/\b(gender|sex|male|female|género|sexo|homme|femme)\b/i.test(combined)) return "gender";
      return "radio";
    }
    // Password
    if (type === "password" || auto === "new-password" || auto === "current-password") {
      if (/\b(confirm|retype|repeat|verify|again|pass2|password2|contraseña|mot de passe|senha)\b/i.test(combined)) return "confirmPassword";
      return "password";
    }
    // Email
    if (type === "email" || auto === "email" || /\b(e-?mail|correo|mail)\b/i.test(combined)) {
      if (/\b(confirm|retype|repeat|verify|again)\b/i.test(combined)) return "confirmEmail";
      return "email";
    }
    // OTP
    if (auto === "one-time-code" || /\b(otp|verification\s*code|security\s*code|2fa|pin\s*code|código)\b/i.test(combined)) return "otp";
    
    // Phone
    if (type === "tel" || auto.indexOf("tel") >= 0 || /\b(phone|mobile|cell|whatsapp|tel|téléphone|telefono|celular)\b/i.test(combined)) {
      if (/\b(confirm|retype|repeat|verify)\b/i.test(combined)) return "confirmPhone";
      return "phone";
    }
    // Names
    if (auto === "given-name" || /\b(first\s*name|fname|given\s*name|prénom|nombre)\b/i.test(combined)) return "firstName";
    if (auto === "family-name" || /\b(last\s*name|lname|surname|family|nom|apellido)\b/i.test(combined)) return "lastName";
    if (auto === "name" || /\b(full\s*name|your\s*name|name|nom|nombre)\b/i.test(combined)) return "fullName";
    
    // Message/Subject
    if (/\b(subject|topic|reason|sujet|asunto)\b/i.test(combined)) return "subject";
    if (tag === "textarea" || /\b(message|comment|feedback|description|bio|mensaje|message)\b/i.test(combined)) return "message";
    
    // Dates
    if (type === "date" || auto.indexOf("bday") >= 0 || /\b(dob|date\s*of\s*birth|birth\s*date|birthday|fecha|naissance)\b/i.test(combined)) return "dob";
    
    // Location
    if (auto === "postal-code" || /\b(zip|postal|pin\s*code|pincode|code postal|código postal)\b/i.test(combined)) return "zip";
    if (auto === "address-level2" || /\b(city|town|ville|ciudad)\b/i.test(combined)) return "city";
    if (auto === "address-level1" || /\b(state|province|region|état|estado)\b/i.test(combined)) return "state";
    if (auto.indexOf("country") >= 0 || /\b(country|nation|pays|país)\b/i.test(combined)) return "country";
    if (auto.indexOf("address") >= 0 || auto.indexOf("street") >= 0 ||
        /\b(address|street|apartment|apt|building|adresse|dirección|endereço)\b/i.test(combined)) return "address";
        
    return "";
  }

  // Generate stable CSS selector
  function cssPath(el) {
    if (!el || el.nodeType !== 1) return "";
    if (el.id) return "#" + CSS.escape(el.id);
    const parts = [];
    let cur = el;
    let depth = 0;
    while (cur && cur.nodeType === 1 && depth < 6) {
      let sel = cur.tagName.toLowerCase();
      const nameAttr = cur.getAttribute && cur.getAttribute("name");
      const typeAttr = cur.getAttribute && cur.getAttribute("type");
      
      if (nameAttr) {
        sel += `[name="${nameAttr}"]`;
      } else if (typeAttr && (typeAttr === 'email' || typeAttr === 'password' || typeAttr === 'tel')) {
        sel += `[type="${typeAttr}"]`;
      } else {
        const parent = cur.parentElement;
        if (parent) {
          const sameTag = Array.from(parent.children).filter(c => c.tagName === cur.tagName);
          if (sameTag.length > 1) sel += `:nth-of-type(${sameTag.indexOf(cur) + 1})`;
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
      const tag = cur.tagName.toLowerCase();
      const nameAttr = cur.getAttribute && cur.getAttribute("name");
      if (nameAttr) {
        parts.unshift(`${tag}[@name="${nameAttr}"]`);
        break;
      }
      let idx = 1;
      let sib = cur.previousElementSibling;
      while (sib) { if (sib.tagName === cur.tagName) idx++; sib = sib.previousElementSibling; }
      parts.unshift(`${tag}[${idx}]`);
      cur = cur.parentElement;
    }
    return "/" + parts.join("/");
  }

  function send(payload) {
    try {
      if (window.AutomationBridge && typeof window.AutomationBridge.onElementRecorded === "function") {
        window.AutomationBridge.onElementRecorded(JSON.stringify(payload));
      }
    } catch (_) {}
  }

  function captureField(el) {
    if (!el || el.nodeType !== 1) return;
    
    let target = el;
    if (target.tagName === 'DIV' || target.tagName === 'SPAN' || target.tagName === 'LABEL') {
        const inner = target.querySelector('input, textarea, select');
        if (inner) target = inner;
        else if (document.activeElement && document.activeElement.tagName === 'INPUT') target = document.activeElement;
    }

    const tag = (target.tagName || "").toLowerCase();
    const isEditable = target.isContentEditable === true;
    if (tag !== "input" && tag !== "textarea" && tag !== "select" && !isEditable) return;
    
    if (tag === "input") {
      const t = (target.type || "text").toLowerCase();
      if (["hidden", "submit", "button", "reset", "image", "file", "search"].indexOf(t) >= 0) return;
    }
    if (!isVisible(target)) return;
    if (recordedElements.has(target)) return;
    recordedElements.add(target);

    // Absolute index fallback (helps if DOM changes drastically)
    const allInputs = Array.from(document.querySelectorAll('input:not([type="hidden"]), textarea, select'));
    const absIndex = allInputs.indexOf(target);

    const looksLike = classify(target);
    const label = findLabel(target)
      || target.getAttribute("aria-label")
      || target.getAttribute("placeholder")
      || target.getAttribute("name")
      || target.id
      || "";

    send({
      kind: "Field",
      looksLike: looksLike,
      tag: tag,
      domType: (target.type || "").toLowerCase(),
      name: target.getAttribute("name") || "",
      domId: target.id || "",
      placeholder: target.getAttribute("placeholder") || "",
      ariaLabel: target.getAttribute("aria-label") || "",
      displayLabel: label,
      cssSelector: cssPath(target),
      xpath: xpath(target),
      index: absIndex
    });
  }

  function onFocusIn(e) {
    captureField(e.target);
  }

  function onMouseDown(e) {
    const active = document.activeElement;
    if (active && active !== e.target && (active.tagName === 'INPUT' || active.tagName === 'TEXTAREA' || active.isContentEditable)) {
        captureField(active);
    }
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
    // Multi-language button classification
    if (!/\b(next|continue|submit|sign\s*up|register|proceed|finish|done|save|send|log\s*in|sign\s*in|get\s*started|suivant|enviar|siguiente|registrarse|envoyer)\b/i.test(label)) return;
    
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
  document.addEventListener("mousedown", onMouseDown, true);
  document.addEventListener("click", onClick, true);

  g.__automationRecorderStop = function () {
    document.removeEventListener("focusin", onFocusIn, true);
    document.removeEventListener("mousedown", onMouseDown, true);
    document.removeEventListener("click", onClick, true);
    g.__automationRecorderActive = false;
  };
})();