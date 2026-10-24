/*
 * Automation Recorder (v8 - Disabled Button Fix + First Load Fix)
 */
(function () {
  "use strict";
  const g = globalThis;

  if (g.__automationRecorderStop) {
    try { g.__automationRecorderStop(); } catch (_) {}
  }
  g.__automationRecorderInstalled = true;

  let isPaused = false;
  let lastTarget = null;
  let lastClickTime = 0;
  let justRecorded = false;

  // --- BUG 2 FIX: Force disabled buttons to be clickable while recording ---
  const styleEl = document.createElement('style');
  styleEl.id = '__automation_recorder_style';
  styleEl.textContent = `
    button:disabled, input:disabled, select:disabled, textarea:disabled, [aria-disabled="true"] {
      pointer-events: auto !important;
      cursor: crosshair !important;
      opacity: 0.7 !important;
    }
  `;
  document.head.appendChild(styleEl);

  // BUG 1 FIX: Override focus to prevent keyboard
  const originalFocus = HTMLElement.prototype.focus;
  HTMLElement.prototype.focus = function(...args) {
    if (!isPaused && g.__automationRecorderInstalled) return;
    return originalFocus.apply(this, args);
  };

  function isVisible(el) {
    if (!el || !el.isConnected) return false;
    try {
      const st = window.getComputedStyle(el);
      if (!st || st.display === "none" || st.visibility === "hidden") return false;
      const r = el.getBoundingClientRect();
      // Relaxed visibility for mobile layouts
      return r.width > 0 || r.height > 0 || el.offsetParent !== null || el.getClientRects().length > 0;
    } catch (_) { return false; }
  }

  function getCssSelector(el) {
    if (!el || el.nodeType !== Node.ELEMENT_NODE) return "";
    if (el.id) return "#" + CSS.escape(el.id);
    let path = [];
    let current = el;
    while (current && current.nodeType === Node.ELEMENT_NODE && current !== document.body) {
      let name = current.nodeName.toLowerCase();
      if (current.id) { path.unshift("#" + CSS.escape(current.id)); break; }
      let parent = current.parentElement;
      if (!parent && current.getRootNode() instanceof ShadowRoot) parent = current.getRootNode().host;
      if (!parent) break;
      const siblings = Array.from(parent.children).filter(c => c.nodeName === current.nodeName);
      if (siblings.length > 1) name += `:nth-of-type(${siblings.indexOf(current) + 1})`;
      path.unshift(name);
      current = parent;
    }
    return path.length ? path.join(" > ") : "";
  }

  function getXPath(el) {
    if (!el || el.nodeType !== Node.ELEMENT_NODE) return "";
    if (el.id) return `//*[@id="${el.id}"]`;
    let path = [];
    let current = el;
    while (current && current.nodeType === Node.ELEMENT_NODE) {
      let idx = 1, sib = current.previousSibling;
      while (sib) {
        if (sib.nodeType === Node.ELEMENT_NODE && sib.nodeName === current.nodeName) idx++;
        sib = sib.previousSibling;
      }
      path.unshift(`${current.nodeName.toLowerCase()}[${idx}]`);
      current = current.parentElement;
    }
    return "/" + path.join("/");
  }

  function getLabel(el) {
    if (!el) return "";
    let label = "";
    if (el.labels && el.labels.length) label = el.labels[0].innerText || el.labels[0].textContent;
    else if (el.closest && el.closest("label")) label = el.closest("label").innerText || el.closest("label").textContent;
    else label = el.innerText || el.textContent || el.getAttribute("aria-label") || el.getAttribute("placeholder") || el.getAttribute("name") || el.id;
    return (label || "").replace(/\s+/g, " ").trim().slice(0, 100);
  }

  function classify(el) {
    const tag = (el.tagName || "").toLowerCase();
    const type = (el.type || "").toLowerCase();
    const role = (el.getAttribute("role") || "").toLowerCase();
    const ac = (el.getAttribute("autocomplete") || "").toLowerCase();
    const combined = `${getLabel(el)} ${el.name || ""} ${el.id || ""}`.toLowerCase();

    if (type === "checkbox" || type === "radio" || role === "checkbox" || role === "radio" || role === "switch") {
      if (/\b(gender|sex|male|female)\b/i.test(combined)) return "gender";
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
    
    if (/\b(first|given)\s*name\b|\bfname\b/.test(combined)) return "firstName";
    if (/\b(last|family|sur)\s*name\b|\blname\b/.test(combined)) return "lastName";
    if (/\b(full\s*name|your\s*name|name)\b/.test(combined)) return "fullName";
    if (/\b(e-?mail)\b/.test(combined)) return "email";
    if (/\b(phone|mobile|tel)\b/.test(combined)) return "phone";
    if (/\b(password|pass)\b/.test(combined)) return "password";
    if (/\b(city)\b/.test(combined)) return "city";
    if (/\b(state)\b/.test(combined)) return "state";
    if (/\b(zip|postal)\b/.test(combined)) return "zip";
    if (/\b(country)\b/.test(combined)) return "country";
    if (tag === "textarea" || /\b(message|comment|description)\b/.test(combined)) return "message";
    return "text";
  }

  function sendToBridge(data) {
    if (window.AutomationBridge && window.AutomationBridge.onElementRecorded) {
      try { window.AutomationBridge.onElementRecorded(JSON.stringify(data)); } catch (_) {}
    }
  }

  function handleEvent(e) {
    if (isPaused) return;
    if (e.target && e.target.closest && e.target.closest('[data-automation-hud]')) return;
    
    // Prevent double recording (mousedown -> click)
    if (e.type === 'click' && justRecorded) {
      justRecorded = false;
      return;
    }

    const path = e.composedPath ? e.composedPath() : [e.target];
    let target = path[0];
    if (!target || target.nodeType !== Node.ELEMENT_NODE) return;

    // Target may be an SVG or span inside a button
    let actualTarget = target;
    if (!['input', 'textarea', 'select', 'button', 'a'].includes(actualTarget.tagName.toLowerCase())) {
      const parentInteractive = actualTarget.closest('button, a, [role="button"], [role="radio"], [role="checkbox"], [onclick], [aria-disabled="true"]');
      if (parentInteractive) actualTarget = parentInteractive;
    }

    if (!isVisible(actualTarget)) return;

    const label = actualTarget.closest("label");
    if (label) {
      const innerInput = label.querySelector("input, select, textarea, [role='radio'], [role='checkbox']");
      if (innerInput) actualTarget = innerInput;
    }

    const now = Date.now();
    if (actualTarget === lastTarget && now - lastClickTime < 500) return;
    lastTarget = actualTarget;
    lastClickTime = now;
    justRecorded = true;

    const tag = actualTarget.tagName.toLowerCase();
    const type = (actualTarget.type || '').toLowerCase();
    let kind = "Field";
    let displayLabel = getLabel(actualTarget);

    const isLink = tag === "a" || (tag === "button") || type === "submit" || actualTarget.getAttribute("role") === "button";
    if (isLink) {
      kind = "ClickNext";
      // Block navigation if it's not disabled
      if (!actualTarget.disabled && actualTarget.getAttribute("aria-disabled") !== "true") {
        if (e.cancelable) {
          e.preventDefault();
          e.stopPropagation();
          e.stopImmediatePropagation();
        }
      }
    } else {
      try { actualTarget.blur(); } catch (_) {}
    }

    const oldOutline = actualTarget.style.outline;
    actualTarget.style.outline = "3px solid #06b6d4";
    setTimeout(() => { actualTarget.style.outline = oldOutline; }, 600);

    const payload = {
      kind: kind,
      tag: tag,
      domType: type || "",
      name: actualTarget.getAttribute("name") || "",
      domId: actualTarget.id || "",
      placeholder: actualTarget.getAttribute("placeholder") || "",
      ariaLabel: actualTarget.getAttribute("aria-label") || "",
      cssSelector: getCssSelector(actualTarget),
      xpath: getXPath(actualTarget),
      looksLike: classify(actualTarget),
      displayLabel: displayLabel || "Element",
      buttonLabel: kind === "ClickNext" ? (displayLabel || "Button") : "",
      verified: true
    };
    sendToBridge(payload);

    // If this was mousedown/touchstart, prevent the subsequent click just in case
    if (e.type === 'mousedown' || e.type === 'touchstart') {
      setTimeout(() => { justRecorded = false; }, 400);
    }
  }

  // BUG 2 FIX: Listen to mousedown and touchstart to catch disabled buttons that swallow 'click'
  document.addEventListener("mousedown", handleEvent, true);
  document.addEventListener("touchstart", handleEvent, true);
  document.addEventListener("click", handleEvent, true);

  g.__automationRecorderPause = () => { isPaused = true; };
  g.__automationRecorderResume = () => { isPaused = false; };
  g.__automationRecorderStop = () => {
    document.removeEventListener("mousedown", handleEvent, true);
    document.removeEventListener("touchstart", handleEvent, true);
    document.removeEventListener("click", handleEvent, true);
    if (styleEl && styleEl.parentNode) styleEl.parentNode.removeChild(styleEl);
    HTMLElement.prototype.focus = originalFocus;
    g.__automationRecorderInstalled = false;
  };
})();