(function () {
  "use strict";

  const g = globalThis;
  let refCounter = 0;
  const refMap = new Map();
  let scanDebounceTimer = null;

  // --- UTILS ---

  // Recursively gather all elements, piercing Shadow DOM
  function getAllElements(root, result) {
    if (!root) return result;
    try {
      const walker = document.createTreeWalker(root, NodeFilter.SHOW_ELEMENT, null, false);
      let node = walker.nextNode();
      while (node) {
        result.push(node);
        if (node.shadowRoot) {
          getAllElements(node.shadowRoot, result);
        }
        node = walker.nextNode();
      }
    } catch (_) {}
    return result;
  }

  const isElementVisible = (el) => {
    if (!el || !el.isConnected) return false;
    if (el.getAttribute && el.getAttribute("aria-hidden") === "true") return false;
    const style = window.getComputedStyle(el);
    if (!style || style.display === "none" || style.visibility === "hidden" || style.opacity === "0") return false;
    const rect = el.getBoundingClientRect();
    if (rect.width <= 0 && rect.height <= 0 && el.offsetParent === null) return false;
    return true;
  };

  const isDisabled = (el) => {
    if (!el) return false;
    if (el.disabled === true || el.inert === true) return true;
    if (el.getAttribute && el.getAttribute("aria-disabled") === "true") return true;
    const parentFieldset = el.closest && el.closest("fieldset[disabled]");
    if (parentFieldset) {
      const legend = parentFieldset.querySelector(":scope > legend");
      if (!legend || !legend.contains(el)) return true;
    }
    return false;
  };

  const findLabel = (el) => {
    if (!el) return "";
    let labels = [];
    try {
      if (el.labels && el.labels.length) labels = Array.from(el.labels);
      else if (el.id) {
        const explicit = document.querySelector('label[for="' + CSS.escape(el.id) + '"]');
        if (explicit) labels.push(explicit);
      }
      const wrapping = el.closest && el.closest("label");
      if (wrapping && !labels.includes(wrapping)) labels.push(wrapping);
    } catch (_) {}
    const text = labels.map(l => (l.innerText || l.textContent || "").replace(/\s+/g, " ").trim()).join(" ");
    if (text) return text;
    
    // Check aria-labelledby
    const labelledBy = el.getAttribute && el.getAttribute("aria-labelledby");
    if (labelledBy) {
      try {
        return labelledBy.split(/\s+/).map(id => {
          const n = document.getElementById(id);
          return n ? (n.innerText || "").trim() : "";
        }).join(" ");
      } catch (_) {}
    }
    return "";
  };

  // --- CLASSIFICATION (Multi-lingual & Custom) ---
  
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

    // Checkboxes / Radio
    if (type === "checkbox" || role === "checkbox") {
      if (/\b(agree|terms|privacy|policy|accept|consent|condition|age|acepto|términos|conditions|datenschutz)\b/i.test(combined)) return "termsCheckbox";
      return "checkbox";
    }
    if (type === "radio" || role === "radio") {
      if (/\b(gender|sex|male|female|género|sexo|homme|femme|geschlecht)\b/i.test(combined)) return "gender";
      return "radio";
    }

    // Passwords
    if (type === "password" || auto === "new-password" || auto === "current-password") {
      if (/\b(confirm|retype|repeat|verify|again|pass2|password2|contraseña|mot de passe|senha|wiederholen)\b/i.test(combined)) return "confirmPassword";
      return "password";
    }

    // Emails
    if (type === "email" || auto === "email" || /\b(e-?mail|correo|mail|courriel)\b/i.test(combined)) {
      if (/\b(confirm|retype|repeat|verify|again)\b/i.test(combined)) return "confirmEmail";
      return "email";
    }

    // OTP / Codes
    if (auto === "one-time-code" || /\b(otp|verification\s*code|security\s*code|2fa|pin\s*code|código|codice)\b/i.test(combined)) return "otp";

    // Phones
    if (type === "tel" || auto.indexOf("tel") >= 0 || /\b(phone|mobile|cell|whatsapp|tel|téléphone|telefono|celular|telefon)\b/i.test(combined)) {
      if (/\b(confirm|retype|repeat|verify)\b/i.test(combined)) return "confirmPhone";
      return "phone";
    }

    // Names
    if (auto === "given-name" || /\b(first\s*name|fname|given\s*name|prénom|nombre|vorname)\b/i.test(combined)) return "firstName";
    if (auto === "family-name" || /\b(last\s*name|lname|surname|family|nom|apellido|nachname)\b/i.test(combined)) return "lastName";
    if (auto === "name" || /\b(full\s*name|your\s*name|name|nom|nombre|name)\b/i.test(combined)) return "fullName";
    if (auto === "username" || /\b(user\s*name|userid|login|handle|utilisateur|usuario|benutzer)\b/i.test(combined)) return "username";

    // Text areas
    if (tag === "textarea" || role === "textbox" || /\b(message|comment|feedback|description|bio|mensaje|message|nachricht)\b/i.test(combined)) return "message";
    if (/\b(subject|topic|reason|sujet|asunto|betreff)\b/i.test(combined)) return "subject";

    // Dates
    if (type === "date" || auto.indexOf("bday") >= 0 || /\b(dob|date\s*of\s*birth|birth\s*date|birthday|fecha|naissance|geburtstag)\b/i.test(combined)) return "dob";

    // Addresses
    if (auto === "postal-code" || /\b(zip|postal|pin\s*code|pincode|code postal|código postal|postleitzahl)\b/i.test(combined)) return "zip";
    if (auto === "address-level2" || /\b(city|town|ville|ciudad|stadt)\b/i.test(combined)) return "city";
    if (auto === "address-level1" || /\b(state|province|region|état|estado|bundesland)\b/i.test(combined)) return "state";
    if (auto.indexOf("country") >= 0 || /\b(country|nation|pays|país|land)\b/i.test(combined)) return "country";
    if (auto.indexOf("address") >= 0 || auto.indexOf("street") >= 0 || /\b(address|street|apartment|apt|building|adresse|dirección|endereço|straße)\b/i.test(combined)) return "address";

    // Credit Cards (Huge for checkouts)
    if (auto.indexOf("cc-") >= 0 || /\b(card\s*number|cc-num|creditcard|tarjeta|kreditkarte)\b/i.test(combined)) return "cc-number";
    if (/\b(expiry|expiration|cvv|cvc|security\s*code|fecha\s*vencimiento)\b/i.test(combined)) return "cc-exp";

    // Custom components often have text-like tags
    if (tag === "div" || tag === "span" || tag === "custom-element") {
      if (role === "combobox" || role === "listbox" || el.isContentEditable) return "text";
    }

    return ""; // Unrecognized
  }

  // --- CAPTCHA DETECTION ---
  function detectCaptcha() {
    const selectors = [
      'iframe[src*="recaptcha" i]', 'iframe[src*="hcaptcha" i]', 'iframe[src*="challenges.cloudflare.com" i]',
      'iframe[src*="turnstile" i]', 'iframe[src*="arkoselabs" i]', 'iframe[src*="funcaptcha" i]',
      ".g-recaptcha", ".h-captcha", ".cf-turnstile", ".grecaptcha-badge"
    ];
    for (let i = 0; i < selectors.length; i++) {
      try {
        const nodes = document.querySelectorAll(selectors[i]);
        for (let j = 0; j < nodes.length; j++) {
          if (!isElementVisible(nodes[j])) continue;
          const token = nodes[j].querySelector && nodes[j].querySelector('textarea[name*="captcha-response" i]');
          if (token && token.value && token.value.trim().length > 10) continue; // Already solved
          return { detected: true, type: selectors[i], description: "Captcha Frame Detected" };
        }
      } catch (_) {}
    }
    return { detected: false, type: "", description: "" };
  }

  // --- PRIMARY ACTION BUTTON ---
  function findPrimaryActionButton(elements) {
    const POSITIVE = /\b(next|continue|submit|sign\s*up|signup|register|create|join|proceed|verify|confirm|finish|done|save|send|login|log\s*in|suivant|enviar|siguiente|weiter)\b/i;
    const NEGATIVE = /\b(back|previous|prev|cancel|close|reset|clear|delete|remove|log\s*out|sign\s*out|skip|forgot|resend)\b/i;

    let bestEl = null;
    let bestScore = -1;

    for (let i = 0; i < elements.length; i++) {
      const el = elements[i];
      if (!isElementVisible(el) || isDisabled(el)) continue;
      
      const tag = el.tagName.toLowerCase();
      const type = (el.type || "").toLowerCase();
      const role = (el.getAttribute("role") || "").toLowerCase();
      
      const isButton = tag === "button" || (tag === "input" && (type === "submit" || type === "button")) || role === "button";
      if (!isButton) continue;

      let label = ((el.innerText || el.value || el.getAttribute("aria-label") || "") + "").trim();
      if (NEGATIVE.test(label)) continue;

      let score = 0;
      if (type === "submit") score += 50;
      if (POSITIVE.test(label)) score += 100;
      if (el.form) score += 20;

      if (score > bestScore) {
        bestScore = score;
        bestEl = el;
      }
    }
    
    // If no POSITIVE match, and we are on a form, grab any submit button
    if (!bestEl && elements.length > 0) {
       const forms = document.querySelectorAll("form");
       if (forms.length > 0) {
           const submitBtn = forms[0].querySelector('button[type="submit"], input[type="submit"]');
           if (submitBtn && isElementVisible(submitBtn)) return submitBtn;
       }
    }

    return bestEl;
  }

  // --- MAIN SCAN ---
  function scanPage() {
    refMap.clear();
    refCounter = 0;

    const allElements = getAllElements(document.body, []);
    const fields = [];
    const interactiveElements = [];

    for (let i = 0; i < allElements.length; i++) {
      const el = allElements[i];
      
      try {
        const tag = (el.tagName || "").toLowerCase();
        const type = (el.type || "text").toLowerCase();
        const role = (el.getAttribute("role") || "").toLowerCase();
        
        // 1. Check if it's a field
        const isField = 
          (tag === "input" && !["hidden", "submit", "button", "reset", "image", "file", "search"].includes(type)) ||
          tag === "textarea" ||
          tag === "select" ||
          role === "combobox" ||
          el.isContentEditable;

        if (isField && isElementVisible(el) && !isDisabled(el)) {
           // Ignore search bars
           if (type === "search" || el.closest('[role="search"], search')) continue;

           const looksLike = classify(el);
           // Skip plain checkboxes unless they are terms, but we still track them in elements
           if (looksLike !== "checkbox") {
             refCounter++;
             const ref = `a${refCounter}`;
             refMap.set(ref, el);
             fields.push({
               index: fields.length,
               ref,
               tag: role === "combobox" ? "custom" : tag,
               type: type,
               name: el.getAttribute("name") || findLabel(el) || el.id || "",
               id: el.id || "",
               looksLike: looksLike || "text",
               currentValue: el.value || el.innerText || "",
               checked: el.checked || false
             });
           }
        }

        // 2. Collect all possible buttons/links for the action finder
        if (tag === "button" || tag === "a" || (tag === "input" && ["submit", "button"].includes(type)) || role === "button") {
           if (isElementVisible(el) && !isDisabled(el)) {
               interactiveElements.push(el);
           }
        }
      } catch (_) {}
    }

    const captcha = detectCaptcha();
    const actionBtn = findPrimaryActionButton(interactiveElements);
    let actionRef = null;
    let actionName = "";
    if (actionBtn) {
      refCounter++;
      actionRef = `a${refCounter}`;
      refMap.set(actionRef, actionBtn);
      actionName = (actionBtn.innerText || actionBtn.value || actionBtn.id || "Submit").trim().slice(0, 40);
    }

    const payload = {
      url: window.location.href,
      title: document.title || "",
      fields: fields,
      captchaDetected: captcha.detected,
      actionButtonRef: actionRef,
      actionButtonName: actionName,
    };

    try {
      if (window.AutomationBridge && typeof window.AutomationBridge.onFieldsDetected === "function") {
        window.AutomationBridge.onFieldsDetected(JSON.stringify(fields));
      }
    } catch (_) {}

    return payload;
  }

  // --- OBSERVER & EXPORTS ---
  
  // Watch for dynamic DOM changes (SPAs), ignoring our own data attributes
  try {
    new MutationObserver(() => {
      if (scanDebounceTimer) clearTimeout(scanDebounceTimer);
      scanDebounceTimer = setTimeout(() => {
          if (g.__automationScan) g.__automationScan();
      }, 400); // 400ms debounce for speed
    }).observe(document.documentElement || document, {
      subtree: true,
      childList: true,
      attributes: true,
      attributeFilter: ["class", "style", "disabled", "type", "name", "id"]
    });
  } catch (_) {}

  g.__automationScan = scanPage;
  scanPage();
})();