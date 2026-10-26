/*
 * Automation Recorder v11 - tap detection fixes for Android WebView
 */
(function () {
  "use strict";
  const g = globalThis;

  if (g.__automationRecorderInstalled) {
    // Already running — just resume. Do NOT tear down and rebuild,
    // which created a window where taps were lost.
    if (typeof g.__automationRecorderResume === "function") g.__automationRecorderResume();
    return;
  }
  g.__automationRecorderInstalled = true;

  let isPaused = false;
  let lastTarget = null;
  let lastClickTime = 0;
  let justRecorded = false;

  // Disabled elements clickable
  const styleEl = document.createElement("style");
  styleEl.id = "__automation_recorder_style";
  styleEl.textContent = `
    button:disabled, input:disabled, [aria-disabled="true"] {
      pointer-events: auto !important; cursor: crosshair !important; opacity: 0.7 !important;
    }`;
  document.head.appendChild(styleEl);

  // Block keyboard
  const originalFocus = HTMLElement.prototype.focus;
  HTMLElement.prototype.focus = function (...args) {
    if (!isPaused && g.__automationRecorderInstalled) return;
    return originalFocus.apply(this, args);
  };

  function isVisible(el) {
    if (!el || !el.isConnected) return false;
    try {
      const st = window.getComputedStyle(el);
      if (!st || st.display === "none" || st.visibility === "hidden") return false;
      const r = el.getBoundingClientRect();
      return r.width > 0 || r.height > 0 || el.offsetParent !== null || el.getClientRects().length > 0;
    } catch (_) { return false; }
  }

  function getCssSelector(el) {
    if (!el || el.nodeType !== Node.ELEMENT_NODE) return "";
    if (el.id) return "#" + CSS.escape(el.id);
    let path = [], cur = el;
    while (cur && cur.nodeType === Node.ELEMENT_NODE && cur !== document.body) {
      let name = cur.nodeName.toLowerCase();
      if (cur.id) { path.unshift("#" + CSS.escape(cur.id)); break; }
      let parent = cur.parentElement;
      if (!parent && cur.getRootNode() instanceof ShadowRoot) parent = cur.getRootNode().host;
      if (!parent) break;
      const sibs = Array.from(parent.children).filter(c => c.nodeName === cur.nodeName);
      if (sibs.length > 1) name += `:nth-of-type(${sibs.indexOf(cur) + 1})`;
      path.unshift(name); cur = parent;
    }
    return path.length ? path.join(" > ") : "";
  }

  function getXPath(el) {
    if (!el || el.nodeType !== Node.ELEMENT_NODE) return "";
    if (el.id) return `//*[@id="${el.id}"]`;
    let path = [], cur = el;
    while (cur && cur.nodeType === Node.ELEMENT_NODE) {
      let i = 1, sib = cur.previousSibling;
      while (sib) { if (sib.nodeType === Node.ELEMENT_NODE && sib.nodeName === cur.nodeName) i++; sib = sib.previousSibling; }
      path.unshift(`${cur.nodeName.toLowerCase()}[${i}]`); cur = cur.parentElement;
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
    const c = `${getLabel(el)} ${el.name || ""} ${el.id || ""}`.toLowerCase();
    if (type === "checkbox" || type === "radio" || role === "checkbox" || role === "radio" || role === "switch") {
      if (/\b(gender|sex|male|female)\b/i.test(c)) return "gender";
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
    if (/\b(first|given)\s*name\b|\bfname\b/.test(c)) return "firstName";
    if (/\b(last|family|sur)\s*name\b|\blname\b/.test(c)) return "lastName";
    if (/\b(full\s*name|your\s*name|name)\b/.test(c)) return "fullName";
    if (/\b(e-?mail)\b/.test(c)) return "email";
    if (/\b(phone|mobile|tel)\b/.test(c)) return "phone";
    if (/\b(password|pass)\b/.test(c)) return "password";
    if (/\b(city)\b/.test(c)) return "city";
    if (/\b(state)\b/.test(c)) return "state";
    if (/\b(zip|postal)\b/.test(c)) return "zip";
    if (/\b(country)\b/.test(c)) return "country";
    if (tag === "textarea" || /\b(message|comment|description)\b/.test(c)) return "message";
    return "text";
  }

  function sendToBridge(data) {
    if (window.AutomationBridge && window.AutomationBridge.onElementRecorded) {
      try { window.AutomationBridge.onElementRecorded(JSON.stringify(data)); }
      catch (e) { console.error("Recorder: bridge error", e); }
    } else {
      console.warn("Recorder: AutomationBridge missing");
    }
  }

  function processTarget(target) {
    if (isPaused) return;
    if (target && target.closest && target.closest("[data-automation-hud]")) return;

    let actualTarget = target;
    if (!["input", "textarea", "select", "button", "a"].includes(actualTarget.tagName.toLowerCase())) {
      const parent = actualTarget.closest('button, a, [role="button"], [role="radio"], [role="checkbox"], [onclick], [aria-disabled="true"]');
      if (parent) actualTarget = parent;
    }
    if (!isVisible(actualTarget)) return;

    const label = actualTarget.closest("label");
    if (label) {
      const inner = label.querySelector("input, select, textarea, [role='radio'], [role='checkbox']");
      if (inner) actualTarget = inner;
    }

    const now = Date.now();
    if (actualTarget === lastTarget && now - lastClickTime < 300) return;
    lastTarget = actualTarget;
    lastClickTime = now;
    justRecorded = true;

    const tag = actualTarget.tagName.toLowerCase();
    const type = (actualTarget.type || "").toLowerCase();
    let kind = "Field";
    const isLink = tag === "a" || tag === "button" || type === "submit" || actualTarget.getAttribute("role") === "button";
    if (isLink) kind = "ClickNext";

    // Visual feedback
    const old = actualTarget.style.outline;
    actualTarget.style.outline = "3px solid #06b6d4";
    setTimeout(() => { actualTarget.style.outline = old; }, 600);

    const payload = {
      kind, tag, domType: type,
      name: actualTarget.getAttribute("name") || "",
      domId: actualTarget.id || "",
      placeholder: actualTarget.getAttribute("placeholder") || "",
      ariaLabel: actualTarget.getAttribute("aria-label") || "",
      cssSelector: getCssSelector(actualTarget),
      xpath: getXPath(actualTarget),
      looksLike: classify(actualTarget),
      displayLabel: getLabel(actualTarget) || "Element",
      buttonLabel: kind === "ClickNext" ? (getLabel(actualTarget) || "Button") : "",
      verified: true,
    };
    console.log("Recorder: captured", tag, payload.displayLabel);
    sendToBridge(payload);
    setTimeout(() => { justRecorded = false; }, 300);
  }

  // Tap tracking — thresholds raised so slightly wobbly taps are NOT dropped
  let tX = 0, tY = 0, tT = 0, tMoved = false;
  document.addEventListener("touchstart", (e) => {
    if (e.touches.length === 1) {
      tX = e.touches[0].clientX; tY = e.touches[0].clientY;
      tT = Date.now(); tMoved = false;
    }
  }, { capture: true, passive: true });

  document.addEventListener("touchmove", (e) => {
    if (e.touches.length === 1) {
      if (Math.abs(e.touches[0].clientX - tX) > 30 || Math.abs(e.touches[0].clientY - tY) > 30) tMoved = true;
    }
  }, { capture: true, passive: true });

  // Touchend — primary capture path
  document.addEventListener("touchend", (e) => {
    if (e.changedTouches.length === 1 && !tMoved) {
      const time = Date.now() - tT;
      if (time < 800) {
        processTarget(e.changedTouches[0].target);
      }
    }
  }, { capture: true });

  // pointerup fallback — survives preventDefault on touchstart, fires before click
  document.addEventListener("pointerup", (e) => {
    if (e.pointerType !== "touch") return;
    if (tMoved) return;
    if (Date.now() - tT > 800) return;
    if (justRecorded) return; // touchend already handled it
    processTarget(e.target);
  }, { capture: true });

  // Click fallback (mouse / desktop)
  document.addEventListener("click", (e) => {
    if (justRecorded) return;
    processTarget(e.target);
  }, { capture: true });

  // Focusin fallback — only for input/textarea (not blocked by justRecorded so late focus still captures)
  document.addEventListener("focusin", (e) => {
    if (e.target && (e.target.tagName === "INPUT" || e.target.tagName === "TEXTAREA")) {
      if (justRecorded) return;
      processTarget(e.target);
    }
  }, { capture: true });

  g.__automationRecorderPause = () => { isPaused = true; };
  g.__automationRecorderResume = () => { isPaused = false; };
  g.__automationRecorderStop = () => {
    document.removeEventListener("touchend", processTarget, true);
    document.removeEventListener("pointerup", processTarget, true);
    document.removeEventListener("click", processTarget, true);
    document.removeEventListener("focusin", processTarget, true);
    if (styleEl.parentNode) styleEl.parentNode.removeChild(styleEl);
    HTMLElement.prototype.focus = originalFocus;
    g.__automationRecorderInstalled = false;
  };
  console.log("Recorder: installed");
})();