/*
 * Copyright (c) 2025 DuckDuckGo
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

(function () {
  "use strict";

  const g = globalThis;

  let mutationCount = 0;
  let blockCount = 0;
  let blockObserver = null;
  let refCounter = 0;
  const refMap = new Map();

  const BLOCKS =
    "form, dialog, [role=dialog], [role=alertdialog], [role=menu], [role=listbox], article, section, li, tr, table, main, nav";
  const UNQUOTED = new Set(["SELECT", "TEXTAREA", "DATALIST", "OPTION", "OPTGROUP", "SCRIPT", "STYLE", "TEMPLATE", "NOSCRIPT", "SVG"]);

  try {
    new MutationObserver((records) => {
      mutationCount += records.length;
    }).observe(document.documentElement || document, {
      subtree: true,
      childList: true,
      attributes: true,
      characterData: true,
    });
  } catch (_) {}

  const disabledByAncestor = (el) => {
    for (let node = el; node; ) {
      if (node.inert === true) return true;
      if (node.getAttribute && node.getAttribute("aria-disabled") === "true") return true;
      if (node.tagName === "FIELDSET" && node !== el) {
        if (node.disabled) {
          const legend = node.querySelector(":scope > legend");
          if (!legend || !legend.contains(el)) return true;
        }
      } else if (node.disabled === true && "disabled" in node) {
        return true;
      }
      const parent = node.parentNode;
      node = parent instanceof ShadowRoot ? parent.host : parent;
      if (node && node.nodeType !== 1) node = null;
    }
    return false;
  };

  const isElementVisible = (el) => {
    if (!el || !el.isConnected) return false;
    if (el.getAttribute("aria-hidden") === "true") return false;
    const style = window.getComputedStyle(el);
    if (!style || style.display === "none" || style.visibility === "hidden") {
      return false;
    }
    const rect = el.getBoundingClientRect();
    if (rect.width <= 0 && rect.height <= 0 && el.offsetParent === null) {
      return false;
    }
    return true;
  };

  const unquoted = (el) => UNQUOTED.has(el.tagName) || (el.getAttribute && el.getAttribute("role") === "option");

  const shownText = (el, max) => {
    if (!el || unquoted(el)) return "";
    const limit = max || 100;
    const walker = document.createTreeWalker(el, NodeFilter.SHOW_ELEMENT | NodeFilter.SHOW_TEXT, {
      acceptNode: (n) => {
        if (n.nodeType !== Node.ELEMENT_NODE) return NodeFilter.FILTER_ACCEPT;
        if (unquoted(n)) return NodeFilter.FILTER_REJECT;
        return NodeFilter.FILTER_SKIP;
      },
    });
    let out = "";
    for (let n = walker.nextNode(); n && out.length <= limit * 2; n = walker.nextNode()) {
      out += " " + (n.nodeValue || "");
    }
    out = out.replace(/\s+/g, " ").trim();
    return out.length > limit ? out.slice(0, limit - 1) + "…" : out;
  };

  const holdsValue = (e) =>
    e.tagName === "TEXTAREA" ||
    e.tagName === "SELECT" ||
    (e.tagName === "INPUT" && !["checkbox", "radio", "button", "submit", "reset", "image"].includes((e.type || "text").toLowerCase()));

  const findAssociatedLabelText = (el) => {
    const parts = [];
    if (el.labels && el.labels.length) {
      for (let i = 0; i < el.labels.length; i++) {
        const t = shownText(el.labels[i], 100);
        if (t) parts.push(t);
      }
    }
    if (el.id) {
      try {
        const explicit = document.querySelector(`label[for="${CSS.escape(el.id)}"]`);
        if (explicit) {
          const t = shownText(explicit, 100);
          if (t && !parts.includes(t)) parts.push(t);
        }
      } catch (_) {}
    }
    const wrappingLabel = el.closest && el.closest("label");
    if (wrappingLabel) {
      const t = shownText(wrappingLabel, 100);
      if (t && !parts.includes(t)) parts.push(t);
    }
    const labelledBy = el.getAttribute && el.getAttribute("aria-labelledby");
    if (labelledBy) {
      labelledBy.split(/\s+/).forEach((id) => {
        const node = document.getElementById(id);
        if (node) {
          const t = shownText(node, 100);
          if (t && !parts.includes(t)) parts.push(t);
        }
      });
    }
    // Check preceding sibling or parent label/heading text (common in modern card forms)
    if (parts.length === 0) {
      const prev = el.previousElementSibling;
      if (prev && ["LABEL", "SPAN", "DIV", "P", "H4", "H5", "H6"].includes(prev.tagName)) {
        const t = shownText(prev, 80);
        if (t) parts.push(t);
      } else if (el.parentElement) {
        const parentPrev = el.parentElement.previousElementSibling;
        if (parentPrev && ["LABEL", "SPAN", "DIV", "P"].includes(parentPrev.tagName)) {
          const t = shownText(parentPrev, 80);
          if (t) parts.push(t);
        }
      }
    }
    return parts.join(" ").trim();
  };

  const nameOf = (e) => {
    if (!e) return "";
    const aria = e.getAttribute("aria-label") || "";
    if (aria.trim()) return aria.trim();
    if (holdsValue(e)) {
      const lbl = findAssociatedLabelText(e);
      if (lbl) return lbl;
      const ph = e.getAttribute("placeholder") || "";
      if (ph.trim()) return ph.trim();
    } else {
      const inner = (e.innerText || e.value || "").replace(/\s+/g, " ").trim();
      if (inner) return inner;
    }
    return (e.getAttribute("alt") || e.getAttribute("title") || e.getAttribute("name") || e.id || "").trim();
  };

  const formProp = (form, name) => {
    try {
      const desc = Object.getOwnPropertyDescriptor(HTMLFormElement.prototype, name);
      return desc && desc.get ? desc.get.call(form) : form[name];
    } catch (_) {
      return form[name];
    }
  };

  const isSubmitterEl = (e) => {
    if (!e) return false;
    const tag = e.tagName;
    if (tag === "BUTTON" && ((e.type || "submit").toLowerCase() === "submit")) return true;
    if (tag === "INPUT" && ["submit", "image"].includes((e.type || "").toLowerCase())) return true;
    return false;
  };

  const submittersOf = (owner) => {
    if (!owner) return [];
    const root = owner.getRootNode ? owner.getRootNode() : document;
    const images = Array.from(root.querySelectorAll("input[type=image]")).filter((i) => i.form === owner);
    const elements = Array.from(formProp(owner, "elements") || []);
    const all = Array.from(new Set([...elements, ...images])).filter(isSubmitterEl);
    return all.sort((a, b) => (a.compareDocumentPosition(b) & Node.DOCUMENT_POSITION_FOLLOWING ? -1 : 1));
  };

  function scrollElementToCenter(el) {
    if (!el) return;
    try {
      const rect = el.getBoundingClientRect();
      const targetY = rect.top + window.scrollY - window.innerHeight * 0.35;
      window.scrollTo({ top: Math.max(0, targetY), behavior: "smooth" });
    } catch (_) {}
    try {
      el.scrollIntoView({ behavior: "smooth", block: "center", inline: "nearest" });
    } catch (_) {
      try {
        el.scrollIntoView(true);
      } catch (_) {}
    }
  }

  const probe = {
    version: 1,

    state(el) {
      if (!el) return null;
      const tag = (el.tagName || "").toLowerCase();
      const type = tag === "input" ? (el.type || "text").toLowerCase() : "";
      const textInput =
        tag === "textarea" ||
        (tag === "input" &&
          !["checkbox", "radio", "button", "submit", "reset", "image", "file", "hidden", "range", "color"].includes(type));
      const contentEditable = el.isContentEditable === true;
      const enabled = !disabledByAncestor(el);
      const readOnly = textInput && (el.readOnly === true || el.getAttribute("aria-readonly") === "true");
      const editable = enabled && ((textInput && !readOnly) || contentEditable);
      let checked = null;
      if (tag === "input" && (type === "checkbox" || type === "radio")) {
        checked = el.indeterminate ? "mixed" : !!el.checked;
      } else if (el.hasAttribute && el.hasAttribute("aria-checked")) {
        const a = el.getAttribute("aria-checked");
        checked = a === "mixed" ? "mixed" : a === "true";
      }
      let value = null;
      if (tag === "input" || tag === "textarea" || tag === "select") {
        value = el.value != null ? String(el.value) : "";
      } else if (contentEditable) {
        value = el.innerText || "";
      }
      const visible = isElementVisible(el);
      return {
        connected: !!el.isConnected,
        tag,
        type,
        role: (el.getAttribute && el.getAttribute("role")) || "",
        visible,
        enabled,
        editable,
        contentEditable,
        checked,
        value,
        inShadow: (el.getRootNode && el.getRootNode()) instanceof ShadowRoot,
        autocomplete: ((el.getAttribute && el.getAttribute("autocomplete")) || "").toLowerCase(),
        name: (el.getAttribute && el.getAttribute("name")) || "",
        id: el.id || "",
        multiple: tag === "select" && !!el.multiple,
        focused: document.activeElement === el,
      };
    },

    setNativeValue(el, value) {
      if (!el) return "";
      const strVal = value == null ? "" : String(value);
      try {
        el.focus();
      } catch (_) {}

      try {
        const proto = Object.getPrototypeOf(el);
        const protoSetter = Object.getOwnPropertyDescriptor(proto, "value")?.set;
        const fallbackSetter =
          el.tagName === "TEXTAREA"
            ? Object.getOwnPropertyDescriptor(HTMLTextAreaElement.prototype, "value")?.set
            : Object.getOwnPropertyDescriptor(HTMLInputElement.prototype, "value")?.set;
        const setter = protoSetter || fallbackSetter;
        if (setter) {
          setter.call(el, strVal);
        } else {
          el.value = strVal;
        }
      } catch (_) {
        el.value = strVal;
      }

      // Reset React _valueTracker if present so React 16/17/18/19 sees the change
      try {
        const tracker = el._valueTracker;
        if (tracker) {
          tracker.setValue("");
        }
      } catch (_) {}

      el.dispatchEvent(new Event("focus", { bubbles: true }));
      el.dispatchEvent(new InputEvent("beforeinput", { bubbles: true, cancelable: true, data: strVal, inputType: "insertText" }));
      el.dispatchEvent(new InputEvent("input", { bubbles: true, composed: true, data: strVal, inputType: "insertText" }));
      el.dispatchEvent(new Event("change", { bubbles: true }));
      return el.value != null ? String(el.value) : "";
    },

    selectOption(el, rawValue) {
      const select = el;
      const options = Array.from(select.options || []);
      if (options.length === 0) return { ok: false, error: "NO_OPTIONS" };
      const norm = (t) => String(t || "").replace(/\s+/g, " ").trim().toLowerCase();
      const target = norm(rawValue);

      let picked =
        options.find((o) => norm(o.value) === target) ||
        options.find((o) => norm(o.label) === target) ||
        options.find((o) => norm(o.text) === target) ||
        options.find((o) => norm(o.text).includes(target) || target.includes(norm(o.text)));

      if (!picked) {
        picked = options.find((o, idx) => idx > 0 && !o.disabled && o.value !== "") || options.find((o) => !o.disabled);
      }

      if (!picked) {
        return { ok: false, error: "NO_SUCH_OPTION" };
      }

      options.forEach((o) => {
        o.selected = o === picked;
      });
      select.value = picked.value;
      select.dispatchEvent(new Event("input", { bubbles: true, composed: true }));
      select.dispatchEvent(new Event("change", { bubbles: true }));
      return {
        ok: true,
        value: picked.value,
        label: picked.label || picked.text,
      };
    },

    watchBlock(el) {
      if (blockObserver) {
        try {
          blockObserver.disconnect();
        } catch (_) {}
      }
      blockCount = 0;
      let block = el && el.closest ? el.closest(BLOCKS) : null;
      if (!block) {
        block = document.body || document.documentElement;
      }
      try {
        blockObserver = new MutationObserver((records) => {
          blockCount += records.length;
        });
        blockObserver.observe(block, {
          subtree: true,
          childList: true,
          attributes: true,
          characterData: true,
        });
      } catch (_) {}
      return probe.describe(block);
    },

    blockMutations() {
      return blockCount;
    },

    pageFacts() {
      const text = (el) => shownText(el, 120);
      const vis = (el) => isElementVisible(el);
      const alerts = Array.from(
        document.querySelectorAll("[role=alert], [role=status], [aria-live=assertive], [aria-live=polite], output, .error, .alert-danger, .invalid-feedback")
      )
        .filter(vis)
        .map(text)
        .filter(Boolean);
      const invalid = Array.from(document.querySelectorAll(":invalid"))
        .filter((el) => "validationMessage" in el && el.validationMessage && el.tagName !== "FORM")
        .map((el) => el.validationMessage);
      const layers = Array.from(
        document.querySelectorAll("dialog[open], [role=dialog], [role=alertdialog], [aria-modal=true]")
      )
        .filter(vis)
        .map((el) => `${el.getAttribute("role") || el.tagName.toLowerCase()} "${el.getAttribute("aria-label") || shownText(el, 60)}"`);
      return { alerts, invalid, layers };
    },

    describe(node) {
      const el = node && node.nodeType === 1 ? node : node ? node.parentElement : null;
      if (!el) return "document";
      let out = el.tagName.toLowerCase();
      if (el.id) out += `#${el.id}`;
      const text = shownText(el, 50);
      if (text) out += ` "${text}"`;
      return out;
    },
  };

  g.__nbProbe = probe;

  function classifyField(el, passwordIndexInForm) {
    const tag = (el.tagName || "").toLowerCase();
    const type = (el.type || "text").toLowerCase();
    const auto = (el.getAttribute("autocomplete") || "").toLowerCase();
    const name = (el.getAttribute("name") || "").toLowerCase();
    const id = (el.id || "").toLowerCase();
    const placeholder = (el.getAttribute("placeholder") || "").toLowerCase();
    const ariaLabel = (el.getAttribute("aria-label") || "").toLowerCase();
    const labelText = findAssociatedLabelText(el).toLowerCase();

    const combined = `${name} ${id} ${placeholder} ${ariaLabel} ${labelText} ${auto}`;

    if (type === "checkbox" || el.getAttribute("role") === "checkbox") {
      if (/\b(agree|terms|privacy|policy|accept|consent|condition|age|18)\b/i.test(combined)) {
        return "termsCheckbox";
      }
      return "checkbox";
    }

    if (type === "radio" || el.getAttribute("role") === "radio") {
      if (/\b(gender|sex|male|female)\b/i.test(combined)) return "gender";
      return "radio";
    }

    if (type === "password" || auto === "new-password" || auto === "current-password") {
      if (
        /\b(confirm|retype|repeat|verify|again|match|pass2|password2|password_confirmation)\b/i.test(combined) ||
        passwordIndexInForm >= 1
      ) {
        return "confirmPassword";
      }
      return "password";
    }

    if (type === "email" || auto === "email" || /\b(e-?mail)\b/i.test(combined)) {
      if (/\b(confirm|retype|repeat|verify|again)\b/i.test(combined)) {
        return "confirmEmail";
      }
      return "email";
    }

    if (
      auto === "one-time-code" ||
      /\b(otp|verification\s*code|security\s*code|confirm\s*code|auth\s*code|2fa|6-digit|pin\s*code)\b/i.test(combined)
    ) {
      return "otp";
    }

    if (/\b(country\s*code|dial\s*code|isd|phone\s*code)\b/i.test(combined)) {
      return "countryCode";
    }

    if (type === "tel" || auto.includes("tel") || /\b(phone|mobile|cell|whatsapp|contact\s*no|contact\s*number|tel)\b/i.test(combined)) {
      if (/\b(confirm|retype|repeat|verify)\b/i.test(combined)) {
        return "confirmPhone";
      }
      return "phone";
    }

    if (auto === "username" || /\b(user\s*name|userid|user_id|login|handle|screen\s*name)\b/i.test(combined)) {
      return "username";
    }

    if (auto === "given-name" || /\b(first\s*name|fname|given\s*name|forename)\b/i.test(combined)) {
      return "firstName";
    }
    if (auto === "additional-name" || /\b(middle\s*name|mname)\b/i.test(combined)) {
      return "middleName";
    }
    if (auto === "family-name" || /\b(last\s*name|lname|surname|family\s*name)\b/i.test(combined)) {
      return "lastName";
    }
    if (/\b(nick\s*name|display\s*name|alias)\b/i.test(combined)) {
      return "nickname";
    }
    if (auto === "name" || /\b(full\s*name|your\s*name|customer\s*name|applicant\s*name|\bname\b)\b/i.test(combined)) {
      return "fullName";
    }

    if (/\b(subject|topic|reason|title)\b/i.test(combined)) {
      return "subject";
    }

    if (tag === "textarea" || /\b(message|your\s*message|comment|comments|inquiry|feedback|description|bio)\b/i.test(combined)) {
      return "message";
    }

    if (
      type === "date" ||
      auto.includes("bday") ||
      /\b(dob|date\s*of\s*birth|birth\s*date|birthday|dd\/mm|mm\/dd|yyyy)\b/i.test(combined)
    ) {
      return "dob";
    }

    if (auto === "sex" || /\b(gender|sex)\b/i.test(combined)) {
      return "gender";
    }

    if (
      auto === "postal-code" ||
      /\b(zip|zip\s*code|postal|postal\s*code|pin\s*code|pincode)\b/i.test(combined)
    ) {
      return "zip";
    }
    if (auto === "address-level2" || /\b(city|town|locality|district)\b/i.test(combined)) {
      return "city";
    }
    if (auto === "address-level1" || /\b(state|province|region|county)\b/i.test(combined)) {
      return "state";
    }
    if (auto.includes("country") || /\b(country|nation|nationality)\b/i.test(combined)) {
      return "country";
    }
    if (
      auto.includes("address") ||
      auto.includes("street") ||
      /\b(address|street|apartment|apt|suite|house\s*no|flat|building)\b/i.test(combined)
    ) {
      return "address";
    }

    if (auto === "organization" || /\b(company|organization|organisation|employer|business\s*name)\b/i.test(combined)) {
      return "company";
    }
    if (auto === "organization-title" || /\b(job\s*title|designation|position|occupation|role)\b/i.test(combined)) {
      return "jobTitle";
    }

    return "text";
  }

  function detectCaptcha() {
    const selectors = [
      'iframe[src*="recaptcha" i]',
      'iframe[src*="hcaptcha" i]',
      'iframe[src*="challenges.cloudflare.com" i]',
      'iframe[src*="turnstile" i]',
      'iframe[src*="arkoselabs" i]',
      'iframe[src*="funcaptcha" i]',
      ".g-recaptcha",
      ".h-captcha",
      ".cf-turnstile",
    ];
    for (let i = 0; i < selectors.length; i++) {
      const nodes = document.querySelectorAll(selectors[i]);
      for (let j = 0; j < nodes.length; j++) {
        const el = nodes[j];
        if (!isElementVisible(el)) continue;
        const responseToken = el.querySelector && el.querySelector('textarea[name*="captcha-response" i]');
        if (responseToken && responseToken.value && responseToken.value.trim().length > 10) {
          continue;
        }
        return {
          detected: true,
          type: selectors[i],
          description: probe.describe(el),
        };
      }
    }
    return { detected: false, type: "", description: "" };
  }

  function findPrimaryActionButton(filledElements) {
    const POSITIVE_REGEX =
      /\b(next|continue|submit|sign\s*up|signup|register|create\s*account|join|proceed|verify|confirm|finish|done|save|send|send\s*message|log\s*in|login|sign\s*in|get\s*started)\b/i;
    const NEGATIVE_REGEX =
      /\b(back|previous|prev|cancel|close|reset|clear|delete|remove|log\s*out|sign\s*out|skip|forgot|resend|google|facebook|apple|github|twitter)\b/i;

    for (let i = 0; i < filledElements.length; i++) {
      const el = filledElements[i];
      if (el && el.form) {
        const submitters = submittersOf(el.form).filter(
          (b) => isElementVisible(b) && !disabledByAncestor(b)
        );
        if (submitters.length > 0) {
          const best = submitters.find((b) => !NEGATIVE_REGEX.test(nameOf(b))) || submitters[0];
          if (best) return best;
        }
      }
    }

    const candidates = Array.from(
      document.querySelectorAll(
        'button, input[type="submit"], input[type="button"], input[type="image"], [role="button"], a[class*="btn" i], a[class*="button" i]'
      )
    ).filter((el) => isElementVisible(el) && !disabledByAncestor(el));

    let bestEl = null;
    let bestScore = -1;

    for (let i = 0; i < candidates.length; i++) {
      const btn = candidates[i];
      const label = `${nameOf(btn)} ${btn.value || ""} ${btn.id || ""} ${btn.className || ""}`.trim();
      if (NEGATIVE_REGEX.test(label)) continue;

      let score = 0;
      if (isSubmitterEl(btn)) score += 50;
      if (POSITIVE_REGEX.test(nameOf(btn))) score += 80;
      else if (POSITIVE_REGEX.test(label)) score += 40;

      if (score > bestScore && score >= 40) {
        bestScore = score;
        bestEl = btn;
      }
    }

    return bestEl;
  }

    function scanPage() {
    refMap.clear();
    refCounter = 0;

    const rawNodes = Array.from(
      document.querySelectorAll(
        'input:not([type="hidden"]):not([type="submit"]):not([type="button"]):not([type="reset"]):not([type="image"]):not([type="file"]), textarea, select, [role="combobox"], [role="listbox"], [contenteditable="true"]'
      )
    );

    const passwordCountPerForm = new WeakMap();
    let detachedPasswordCount = 0;
    const fields = [];

    for (let i = 0; i < rawNodes.length; i++) {
      const el = rawNodes[i];
      const st = probe.state(el);
      if (!st || !st.visible || !st.enabled) continue;
      
      const isCustom = el.getAttribute("role") === "combobox" || el.getAttribute("role") === "listbox" || el.isContentEditable;
      if (!isCustom && st.tag !== "select" && st.type !== "checkbox" && st.type !== "radio" && !st.editable) continue;
      if (st.type === "search" || el.closest('[role="search"], search')) continue;

      let pwdIdx = 0;
      if (st.type === "password") {
        if (el.form) {
          pwdIdx = passwordCountPerForm.get(el.form) || 0;
          passwordCountPerForm.set(el.form, pwdIdx + 1);
        } else {
          pwdIdx = detachedPasswordCount++;
        }
      }

      const looksLike = classifyField(el, pwdIdx);
      if (looksLike === "checkbox") continue;

      refCounter++;
      const ref = `a${refCounter}`;
      el.setAttribute("data-nano-ref", ref);
      refMap.set(ref, el);

      fields.push({
        index: fields.length,
        ref,
        tag: isCustom ? "custom" : st.tag,
        type: st.type || st.tag,
        name: st.name || nameOf(el),
        id: st.id,
        looksLike,
        currentValue: st.value || "",
        checked: st.checked === true,
      });
    }

    const captcha = detectCaptcha();
    const actionBtn = findPrimaryActionButton(fields.map((f) => refMap.get(f.ref)).filter(Boolean));
    let actionRef = null;
    let actionName = "";
    if (actionBtn) {
      refCounter++;
      actionRef = `a${refCounter}`;
      actionBtn.setAttribute("data-nano-ref", actionRef);
      refMap.set(actionRef, actionBtn);
      actionName = nameOf(actionBtn) || "Next / Submit";
    }

    const payload = {
      url: window.location.href,
      title: document.title || "",
      fields,
      captchaDetected: captcha.detected,
      captchaDescription: captcha.description,
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

  const sleep = (ms) => new Promise((resolve) => setTimeout(resolve, Math.max(0, ms)));

  async function fillFieldItem(el, wantedValue, typingDelayMs) {
    if (!el || !el.isConnected) {
      return { ok: false, reason: "element detached" };
    }

    // Scroll both window and element into the visible viewport center
    scrollElementToCenter(el);
    await sleep(220);

    const st = probe.state(el);
    if (!st) {
      return { ok: false, reason: "element state unreadable" };
    }

    if (st.type === "checkbox" || st.type === "radio") {
      const wantChecked = wantedValue !== "false" && wantedValue !== "0";
      if (st.checked !== wantChecked) {
        const lbl = el.labels && el.labels[0] ? el.labels[0] : el;
        lbl.click();
        if (el.checked !== wantChecked) {
          el.checked = wantChecked;
          el.dispatchEvent(new Event("input", { bubbles: true, composed: true }));
          el.dispatchEvent(new Event("change", { bubbles: true }));
        }
      }
      return { ok: true, mechanism: "toggle", readBack: String(el.checked) };
    }

    if (st.tag === "select") {
      const res = probe.selectOption(el, wantedValue || "__first_valid__");
      return { ok: res.ok, mechanism: "select-option", readBack: res.label || res.value || "" };
    }

    const text = String(wantedValue ?? "");
    if (typingDelayMs > 0 && text.length <= 64 && !["date", "month", "time", "color", "range"].includes(st.type)) {
      probe.setNativeValue(el, "");
      let built = "";
      const perCharMs = Math.min(80, Math.max(10, Math.floor(typingDelayMs / Math.max(1, text.length))));
      for (let c = 0; c < text.length; c++) {
        const ch = text[c];
        built += ch;
        el.dispatchEvent(new KeyboardEvent("keydown", { key: ch, bubbles: true, composed: true }));
        probe.setNativeValue(el, built);
        el.dispatchEvent(new KeyboardEvent("keyup", { key: ch, bubbles: true, composed: true }));
        await sleep(perCharMs);
      }
    } else {
      probe.setNativeValue(el, text);
    }

    el.dispatchEvent(new Event("blur", { bubbles: true }));
    return {
      ok: true,
      mechanism: "set-value",
      readBack: el.value != null ? String(el.value) : text,
    };
  }

  g.__automationFill = async function (rawListJson, rawOptionsJson) {
    const list = typeof rawListJson === "string" ? JSON.parse(rawListJson) : rawListJson || [];
    const options = typeof rawOptionsJson === "string" ? JSON.parse(rawOptionsJson) : rawOptionsJson || {};
    const typingDelayMs = typeof options.typingDelayMs === "number" ? options.typingDelayMs : 60;
    const fieldDelayMs = typeof options.fieldDelayMs === "number" ? options.fieldDelayMs : 120;

    if (refMap.size === 0) {
      scanPage();
    }

    const domInputs = Array.from(
      document.querySelectorAll(
        'input:not([type="hidden"]):not([type="submit"]):not([type="button"]):not([type="reset"]):not([type="image"]):not([type="file"]), textarea, select'
      )
    ).filter((el) => isElementVisible(el) && !disabledByAncestor(el));

    const receipts = [];
    const filledElements = [];

    for (let i = 0; i < list.length; i++) {
      const item = list[i];
      let targetEl = null;
      if (item.ref && refMap.has(item.ref)) {
        targetEl = refMap.get(item.ref);
      } else if (item.ref) {
        targetEl = document.querySelector(`[data-nano-ref="${item.ref}"]`);
      }
      if (!targetEl && typeof item.index === "number" && domInputs[item.index]) {
        targetEl = domInputs[item.index];
      }
      if (!targetEl) continue;

      const res = await fillFieldItem(targetEl, item.value, typingDelayMs);
      receipts.push({
        ref: item.ref || String(item.index),
        ok: res.ok,
        mechanism: res.mechanism,
      });
      if (res.ok) filledElements.push(targetEl);
      if (fieldDelayMs > 0 && i < list.length - 1) {
        await sleep(fieldDelayMs);
      }
    }

    return {
      filledCount: filledElements.length,
      totalRequested: list.length,
      receipts,
      captcha: detectCaptcha(),
    };
  };

  g.__automationClickNextAndSettle = async function (rawOptionsJson) {
    const options = typeof rawOptionsJson === "string" ? JSON.parse(rawOptionsJson) : rawOptionsJson || {};
    const settleTimeoutMs = typeof options.settleTimeoutMs === "number" ? options.settleTimeoutMs : 2000;

    const preCaptcha = detectCaptcha();
    if (preCaptcha.detected && options.pauseOnCaptcha !== false) {
      return { clicked: false, captchaDetected: true, verdict: "captcha_blocked" };
    }

    const filledEls = Array.from(refMap.values()).filter((el) => el && el.isConnected && holdsValue(el));
    let btn = options.actionRef ? refMap.get(options.actionRef) : null;
    if (!btn || !btn.isConnected || !isElementVisible(btn)) {
      btn = findPrimaryActionButton(filledEls);
    }

    if (!btn) {
      return { clicked: false, captchaDetected: false, verdict: "no_button_found" };
    }

    const urlBefore = window.location.href;
    const factsBefore = probe.pageFacts();
    probe.watchBlock(btn);
    const blockBefore = probe.blockMutations();

    scrollElementToCenter(btn);
    await sleep(250);

    const rect = btn.getBoundingClientRect();
    const cx = rect.left + rect.width / 2;
    const cy = rect.top + rect.height / 2;
    const eventInit = { bubbles: true, cancelable: true, composed: true, clientX: cx, clientY: cy, button: 0 };
    btn.dispatchEvent(new PointerEvent("pointerdown", eventInit));
    btn.dispatchEvent(new MouseEvent("mousedown", eventInit));
    btn.dispatchEvent(new PointerEvent("pointerup", eventInit));
    btn.dispatchEvent(new MouseEvent("mouseup", eventInit));
    btn.click();

    const start = performance.now();
    let lastSignalKey = "";
    let quietPolls = 0;

    while (performance.now() - start < settleTimeoutMs) {
      await sleep(100);
      const urlNow = window.location.href;
      const btnRemoved = !btn.isConnected || !isElementVisible(btn);
      const muts = probe.blockMutations() - blockBefore;
      const sig = `${urlNow}|${btnRemoved}|${muts}`;
      if (sig === lastSignalKey && (urlNow !== urlBefore || btnRemoved || muts > 0)) {
        quietPolls++;
        if (quietPolls >= 3) break;
      } else {
        quietPolls = 0;
      }
      lastSignalKey = sig;
    }

    const urlAfter = window.location.href;
    const urlChanged = urlAfter !== urlBefore;
    const targetRemoved = !btn.isConnected || !isElementVisible(btn);
    const blockMutations = probe.blockMutations() - blockBefore;
    const factsAfter = probe.pageFacts();
    const newAlerts = factsAfter.alerts.filter((a) => !factsBefore.alerts.includes(a));
    const newInvalid = factsAfter.invalid.filter((v) => !factsBefore.invalid.includes(v));
    const postScan = scanPage();

    const unfilledNewFields = postScan.fields.filter(
      (f) => f.looksLike !== "termsCheckbox" && (!f.currentValue || f.currentValue.trim() === "")
    );

    return {
      clicked: true,
      urlChanged,
      targetRemoved,
      blockMutations,
      alerts: newAlerts,
      invalidMessages: newInvalid,
      captchaDetected: postScan.captchaDetected,
      remainingUnfilledCount: unfilledNewFields.length,
      verdict:
        newInvalid.length > 0
          ? "validation_error"
          : postScan.captchaDetected
            ? "captcha_blocked"
            : urlChanged || targetRemoved || blockMutations > 0
              ? "changed"
              : "none",
    };
  };

  g.__automationScan = scanPage;
  scanPage();
})();
