/*
 * Automation Recorder (v3)
 * Captures clicks, maps to inputs, blocks navigation while active.
 */
(function () {
  "use strict";
  const g = globalThis;

  // Clean up previous instance if re-injected
  if (g.__automationRecorderStop) {
    try { g.__automationRecorderStop(); } catch (_) {}
  }
  g.__automationRecorderInstalled = true;

  let isPaused = false;
  let lastTarget = null;
  let lastClickTime = 0;

  function isVisible(el) {
    if (!el || !el.isConnected) return false;
    try {
      const st = window.getComputedStyle(el);
      if (!st || st.display === "none" || st.visibility === "hidden" || st.opacity === "0") return false;
      const r = el.getBoundingClientRect();
      return r.width > 0 || r.height > 0 || el.offsetParent !== null;
    } catch (_) { return false; }
  }

  function getCssSelector(el) {
    if (!el || el.nodeType !== Node.ELEMENT_NODE) return "";
    if (el.id) return "#" + CSS.escape(el.id);
    
    let path = [];
    let current = el;
    
    while (current && current.nodeType === Node.ELEMENT_NODE && current !== document.body) {
      let name = current.nodeName.toLowerCase();
      if (current.id) {
        path.unshift("#" + CSS.escape(current.id));
        break;
      }
      
      let parent = current.parentElement;
      if (!parent && current.getRootNode() instanceof ShadowRoot) {
        parent = current.getRootNode().host;
      }
      if (!parent) break;
      
      const siblings = Array.from(parent.children).filter(c => c.nodeName === current.nodeName);
      if (siblings.length > 1) {
        const idx = siblings.indexOf(current) + 1;
        name += `:nth-of-type(${idx})`;
      }
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
      let idx = 1;
      let sib = current.previousSibling;
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
    if (el.labels && el.labels.length) {
      label = el.labels[0].innerText || el.labels[0].textContent;
    } else if (el.closest && el.closest("label")) {
      label = el.closest("label").innerText || el.closest("label").textContent;
    } else {
      label = el.innerText || el.textContent || el.getAttribute("aria-label") || el.getAttribute("placeholder") || el.getAttribute("name") || el.id;
    }
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
    
    // Ignore clicks on the DuckDuckGo HUD
    if (e.target && e.target.closest && e.target.closest('[data-automation-hud]')) return;

    const path = e.composedPath();
    let target = path[0];
    if (!target || target.nodeType !== Node.ELEMENT_NODE || !isVisible(target)) return;

    // Resolve label clicks to inputs
    const label = target.closest("label");
    if (label) {
      const innerInput = label.querySelector("input, select, textarea, [role='radio'], [role='checkbox']");
      if (innerInput) target = innerInput;
    }

    // Climb up if we clicked an icon inside a button/link
    if (!['input', 'textarea', 'select', 'button', 'a'].includes(target.tagName.toLowerCase())) {
      const parentInteractive = target.closest('button, a, [role="button"], [role="radio"], [role="checkbox"], [onclick]');
      if (parentInteractive) target = parentInteractive;
    }

    const now = Date.now();
    if (target === lastTarget && now - lastClickTime < 500) return;
    lastTarget = target;
    lastClickTime = now;

    const tag = target.tagName.toLowerCase();
    const type = (target.type || '').toLowerCase();
    let kind = "Field";
    let displayLabel = getLabel(target);

    // Detect navigation elements
    if (tag === "button" || tag === "a" || type === "submit" || type === "button" || target.getAttribute("role") === "button") {
      kind = "ClickNext";
      
      // BUG 3 FIX: Block navigation/action while recording.
      // Only block links and actual submits, so dropdowns still open.
      if (tag === "a" && target.hasAttribute("href")) {
        e.preventDefault();
        e.stopPropagation();
      } else if (type === "submit") {
        e.preventDefault();
        e.stopPropagation();
      }
    }

    // Visual feedback
    const oldOutline = target.style.outline;
    target.style.outline = "3px solid #06b6d4";
    setTimeout(() => { target.style.outline = oldOutline; }, 600);

    const payload = {
      kind: kind,
      tag: tag,
      domType: type || "",
      name: target.getAttribute("name") || "",
      domId: target.id || "",
      placeholder: target.getAttribute("placeholder") || "",
      ariaLabel: target.getAttribute("aria-label") || "",
      cssSelector: getCssSelector(target),
      xpath: getXPath(target),
      looksLike: classify(target),
      displayLabel: displayLabel || "Element",
      buttonLabel: kind === "ClickNext" ? (displayLabel || "Button") : "",
      verified: true
    };
    sendToBridge(payload);
  }

  document.addEventListener("click", handleEvent, true);

  g.__automationRecorderPause = () => { isPaused = true; };
  g.__automationRecorderResume = () => { isPaused = false; };
  g.__automationRecorderStop = () => {
    document.removeEventListener("click", handleEvent, true);
    g.__automationRecorderInstalled = false;
  };
})();