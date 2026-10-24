/*
 * Automation scanner + filler + clicker
 * Installs globalThis.__automationScan / __automationFill /
 * __automationClickNextAndSettle / __automationDismissCookieBanner
 *
 * Designed for WebView use by DuckDuckGo's AutomationEngine.
 */
(function () {
  "use strict";
  const g = globalThis;

  // Re-injection guard — idempotent.
  if (g.__automationScannerInstalled) {
    try { if (g.__automationScan) g.__automationScan(); } catch (_) {}
    return;
  }
  g.__automationScannerInstalled = true;

  // ===========================================================================
  // CONFIG
  // ===========================================================================
  const CFG = {
    MAX_IFRAME_DEPTH: 2,
    MAX_SHADOW_DEPTH: 5,
    MAX_ELEMENTS: 5000,
    THROTTLE_MS: 800,
    MIN_SIZE_PX: 2,
    MAX_BUTTONS_OUT: 25,
    MAX_LINKS_OUT: 20,
    FILL_DELAY_MS: 50,
    CLICK_SETTLE_POLL_MS: 100,
  };

  // ===========================================================================
  // I18N LABEL PATTERNS
  // Order matters: more specific patterns first.
  // ===========================================================================
  const RX = {
    // very specific first
    otp: /\b(otp|one[-\s]?time\s*(code|password)|2fa|verification\s*code|security\s*code|sms\s*code|auth\s*code|код\s*подтверждения|인증\s*번호|验证码|認証コード|رمز\s*التحقق)\b/i,
    confirm_password: /\b(confirm|repeat|re-?enter|verify)\b.*\b(password|pass)\b|\b(password|pass)\b.*\b(confirm|repeat)\b|повтор|подтверд.*пароль|비밀번호\s*확인|确认密码/i,
    confirm_email: /\b(confirm|repeat|re-?enter|verify)\b.*\b(e-?mail)\b|\b(e-?mail)\b.*\b(confirm|repeat)\b|подтверд.*почт|이메일\s*확인|确认邮箱/i,
    password: /\b(password|passwd|passcode|pwd)\b|пароль|비밀번호|密码|パスワード|كلمة\s*المرور|contrasena|mot\s*de\s*passe|passwort|senha|parola/i,
    email: /\b(e-?mail|mail)\b|почт|электр|이메일|邮箱|メール|بريد|correo|courriel|email|e-?mail/i,
    phone: /\b(phone|mobile|cell|tel|whatsapp|contact\s*number)\b|телефон|моб|핸드폰|전화|휴대폰|手机|电话|電話|هاتف|جوال|tel[eé]fono|t[eé]l[eé]phone|telefon|telefone|cellulare/i,
    firstname: /\b(first|given)\s*name\b|\bfname\b|\bforename\b|имя|이름|名|nombre|pr[eé]nom|vorname|nome/i,
    lastname: /\b(last|family|sur)\s*name\b|\blname\b|\bsurname\b|фамил|성|姓|apellido|nom\s*de\s*famille|nachname|sobrenome|cognome/i,
    middlename: /\b(middle|mid)\s*name\b|отчество|가운데\s*이름/i,
    fullname: /\b(full\s*name|your\s*name|display\s*name|name)\b|полное\s*имя|이름|姓名|名前|nombre\s*completo|nom\s*complet|vollständiger\s*name|nome\s*completo/i,
    username: /\b(user[\s_-]?name|user[\s_-]?id|login|handle|screen[\s_-]?name)\b|логин|아이디|用户名|ユーザー名|اسم\s*المستخدم|usuario|identifiant|benutzername|usuário|nome\s*utente/i,
    message: /\b(message|comment|feedback|description|bio|notes?|details?)\b|сообщени|коммент|메시지|댓글|내용|消息|评论|メッセージ|رسالة|mensaje|commentaire|nachricht|mensagem|messaggio/i,
    dob: /\b(dob|date\s*of\s*birth|birth[\s_-]?date|birthday|bday)\b|дата\s*рождения|생년월일|생일|出生|生日|生年月日|تاريخ\s*الميلاد|fecha\s*de\s*nacimiento|date\s*de\s*naissance|geburtsdatum|data\s*de\s*nascimento|data\s*di\s*nascita/i,
    zip: /\b(zip|postal|post[\s_-]?code|pin\s*code|pincode)\b|почтов|индекс|우편번호|邮政编码|郵便番号|الرمز\s*البريدي|c[oó]digo\s*postal|code\s*postal|postleitzahl|cep|cap/i,
    city: /\b(city|town|ville)\b|город|도시|城市|市|مدينة|ciudad|ville|stadt|cidade|città/i,
    state: /\b(state|province|region|county)\b|област|주|省|州|منطقة|provincia|région|bundesland|estado|regione/i,
    country: /\b(country|nation)\b|стран|국가|国家|国|بلد|pa[ií]s|pays|land|paese/i,
    address: /\b(address|street|line\s*1|line\s*2|apartment|apt)\b|адрес|улиц|주소|地址|住所|عنوان|direcci[oó]n|adresse|adresse|endereço|indirizzo/i,
    company: /\b(company|organi[sz]ation|employer|business)\b|компани|организац|회사|公司|会社|شركة|empresa|entreprise|unternehmen/i,
    jobtitle: /\b(job\s*title|position|role|occupation|designation)\b|должност|직책|직업|职位|職業|منصب|puesto|cargo|poste|beruf|cargo|ruolo/i,
    gender: /\b(gender|sex)\b|пол|성별|性别|性別|جنس|g[eé]nero|sexe|geschlecht|g[eê]nero|sesso/i,
    terms: /\b(terms|agree|consent|privacy|policy|accept|conditions)\b|соглас|принима|동의|약관|同意|条款|規約|أوافق|acepto|j'accepte|akzeptiere|aceito|accetto/i,
  };

  // ===========================================================================
  // BUTTON KEYWORDS (positive = likely "next"; negative = never)
  // ===========================================================================
  const BTN_POS =
    /\b(next|continue|submit|sign\s*up|signup|register|create|join|proceed|verify|confirm|finish|done|save|send|login|log\s*in|sign\s*in|accept|agree|i\s*agree|i\s*accept|allow|approve|apply|checkout|pay)\b|далее|продолж|отправ|войти|вход|зарегистр|принять|соглас|다음|계속|제출|로그인|가입|동의|下一步|继续|提交|登录|注册|同意|次へ|続ける|送信|ログイン|登録|同意する|التالي|متابعة|إرسال|تسجيل\s*الدخول|تسجيل|أوافق|siguiente|continuar|enviar|iniciar\s*sesi[oó]n|registrar|aceptar|suivant|continuer|envoyer|connexion|s\\'inscrire|accepter|weiter|absenden|anmelden|registrieren|akzeptieren|avancar|enviar|entrar|registrar|aceitar|avanti|invia|accedi|registrati|accetta/i;
  const BTN_NEG =
    /\b(back|previous|prev|cancel|close|dismiss|reset|clear|delete|remove|log\s*out|sign\s*out|logout|skip|forgot|resend|no\s*thanks|not\s*now|decline|reject|deny)\b|назад|отмена|закрыть|удал|выход|이전|취소|닫기|삭제|로그아웃|返回|取消|关闭|删除|退出|戻る|キャンセル|閉じる|削除|ログアウト|رجوع|إلغاء|إغلاق|حذف|atr[aá]s|cancelar|cerrar|eliminar|salir|retour|annuler|fermer|supprimer|d[eé]connexion|zur[uü]ck|abbrechen|schlie[ßs]en|l[oö]schen|voltar|cancelar|fechar|excluir|sair|indietro|annulla|chiudi|elimina|esci/i;

  // ===========================================================================
  // ELEMENT REF REGISTRY
  // ===========================================================================
  let refCounter = 0;
  const elToRef = new WeakMap();
  const refToEl = new Map(); // value: WeakRef<Element> or {deref: () => Element}

  function getRef(el) {
    if (!el) return null;
    let r = elToRef.get(el);
    if (r) return r;
    r = "r" + (++refCounter);
    elToRef.set(el, r);
    try {
      refToEl.set(r, new WeakRef(el));
    } catch (_) {
      refToEl.set(r, { deref: () => el });
    }
    try { el.setAttribute("data-dd-ref", r); } catch (_) {}
    return r;
  }

  function getByRef(ref) {
    const w = refToEl.get(ref);
    if (!w) return null;
    const el = w.deref ? w.deref() : null;
    return el && el.isConnected ? el : null;
  }

  // ===========================================================================
  // BASIC HELPERS
  // ===========================================================================
  const sleep = (ms) => new Promise((r) => setTimeout(r, ms));
  const txt = (el) => el ? ((el.innerText || el.textContent || "").replace(/\s+/g, " ").trim()) : "";
  const attr = (el, n) => (el && el.getAttribute ? el.getAttribute(n) || "" : "");

  function isVisible(el) {
    if (!el || !el.isConnected) return false;
    try {
      if (attr(el, "aria-hidden") === "true") return false;
      const st = el.ownerDocument.defaultView.getComputedStyle(el);
      if (!st) return false;
      if (st.display === "none" || st.visibility === "hidden" || st.visibility === "collapse") return false;
      if (parseFloat(st.opacity) === 0) return false;
      const r = el.getBoundingClientRect();
      if (r.width < CFG.MIN_SIZE_PX && r.height < CFG.MIN_SIZE_PX) return false;
      // off-screen by >2000px is almost certainly invisible
      if (r.right < -2000 || r.bottom < -2000) return false;
      return true;
    } catch (_) {
      return false;
    }
  }

  function isDisabled(el) {
    if (!el) return false;
    if (el.disabled === true || el.inert === true) return true;
    if (attr(el, "aria-disabled") === "true") return true;
    if (el.closest && el.closest("[inert]")) return true;
    return false;
  }

  function findLabel(el) {
    if (!el) return "";
    const out = [];
    try {
      if (el.labels && el.labels.length) {
        for (const l of el.labels) {
          const t = txt(l).slice(0, 120);
          if (t && !out.includes(t)) out.push(t);
        }
      }
      if (el.id) {
        try {
          const l = el.ownerDocument.querySelector('label[for="' + CSS.escape(el.id) + '"]');
          if (l) {
            const t = txt(l).slice(0, 120);
            if (t && !out.includes(t)) out.push(t);
          }
        } catch (_) {}
      }
      const wrap = el.closest && el.closest("label");
      if (wrap) {
        const t = txt(wrap).slice(0, 120);
        if (t && !out.includes(t)) out.push(t);
      }
      // aria-labelledby → look up elements
      const lblBy = attr(el, "aria-labelledby");
      if (lblBy) {
        for (const id of lblBy.split(/\s+/)) {
          const n = el.ownerDocument.getElementById(id);
          if (n) {
            const t = txt(n).slice(0, 120);
            if (t && !out.includes(t)) out.push(t);
          }
        }
      }
    } catch (_) {}
    return out.join(" ").trim();
  }

  function combinedLabel(el) {
    return [
      attr(el, "placeholder"),
      attr(el, "aria-label"),
      attr(el, "title"),
      attr(el, "name"),
      el.id,
      attr(el, "autocomplete"),
      attr(el, "inputmode"),
      attr(el, "data-testid"),
      attr(el, "data-name"),
      findLabel(el),
    ].join(" ").toLowerCase();
  }

  // ===========================================================================
  // CLASSIFICATION
  // ===========================================================================
  const AUTOCOMPLETE_MAP = {
    "given-name": "firstname",
    "family-name": "lastname",
    "additional-name": "middlename",
    name: "fullname",
    email: "email",
    username: "username",
    "current-password": "password",
    "new-password": "password",
    "one-time-code": "otp",
    tel: "phone",
    "tel-national": "phone",
    "street-address": "address",
    "address-line1": "address",
    "address-line2": "address",
    "address-level1": "state",
    "address-level2": "city",
    "postal-code": "zip",
    country: "country",
    "country-name": "country",
    bday: "dob",
    "bday-day": "dob",
    "bday-month": "dob",
    "bday-year": "dob",
    organization: "company",
    "organization-title": "jobtitle",
    sex: "gender",
  };

  function classify(el) {
    // 1. Autocomplete attribute is the most reliable
    const ac = attr(el, "autocomplete").toLowerCase().split(/\s+/)[0];
    if (ac && AUTOCOMPLETE_MAP[ac]) return AUTOCOMPLETE_MAP[ac];

    // 2. Input type
    const tag = (el.tagName || "").toLowerCase();
    const type = (el.type || "").toLowerCase();
    const role = attr(el, "role").toLowerCase();

    if (type === "password") return "password";
    if (type === "email") return "email";
    if (type === "tel") return "phone";
    
    // Define 'c' here so we can use it for the checkbox/radio checks below
    const c = combinedLabel(el);
    
    if (type === "checkbox" || role === "checkbox" || role === "switch") {
        return "termscheckbox";
    }
    if (type === "radio" || role === "radio") {
        if (RX.gender.test(c)) return "gender";
        return "termscheckbox"; // Fallback for Terms/Consent radios
    }

    // 3. Keyword matching against combined label
    // (Do NOT declare 'const c' again here, just use the one from above)
    
    // order matters — most specific first
    if (RX.confirm_password.test(c)) return "confirmpassword";
    if (RX.confirm_email.test(c)) return "confirmemail";
    if (RX.otp.test(c)) return "otp";
    if (RX.password.test(c)) return "password";
    if (RX.email.test(c)) return "email";
    if (RX.phone.test(c)) return "phone";
    if (RX.firstname.test(c)) return "firstname";
    if (RX.middlename.test(c)) return "middlename";
    if (RX.lastname.test(c)) return "lastname";
    if (RX.dob.test(c)) return "dob";
    if (RX.fullname.test(c)) return "fullname";
    if (RX.username.test(c)) return "username";
    if (RX.zip.test(c)) return "zip";
    if (RX.city.test(c)) return "city";
    if (RX.state.test(c)) return "state";
    if (RX.country.test(c)) return "country";
    if (RX.address.test(c)) return "address";
    if (RX.company.test(c)) return "company";
    if (RX.jobtitle.test(c)) return "jobtitle";
    if (RX.gender.test(c)) return "gender";
    if (RX.terms.test(c) && (type === "checkbox" || role === "checkbox")) return "termscheckbox";
    if (tag === "textarea" || role === "textbox" || RX.message.test(c)) return "message";

    return ""; // unknown → caller decides (usually "text")
  }

  // ===========================================================================
  // CUSTOM WIDGET DETECTION
  // ===========================================================================
  function isCustomSelectTrigger(el) {
    if (!el) return false;
    const role = attr(el, "role").toLowerCase();
    if (role === "combobox" || role === "listbox") return true;
    if (attr(el, "aria-haspopup") === "listbox" || attr(el, "aria-haspopup") === "menu") return true;
    if (attr(el, "aria-expanded") === "false" || attr(el, "aria-expanded") === "true") {
      // Has an expanding behaviour
      const cls = (el.className || "").toString().toLowerCase();
      const id = (el.id || "").toLowerCase();
      if (/select|dropdown|combobox|picker|autocomplete|menu|choices|vselect/.test(cls + " " + id)) return true;
    }
    const cls = (el.className || "").toString().toLowerCase();
    const id = (el.id || "").toLowerCase();
    const parentCls = (el.parentElement && (el.parentElement.className || "").toString().toLowerCase()) || "";
    const combined = cls + " " + id + " " + parentCls;
    // Common library patterns
    if (/\b(mui-?select|ant-?select|chakra-?select|react-?select|rs-?select|v-?select|select2|choices|dd-?trigger|dd-?toggle)\b/.test(combined)) return true;
    // Generic: element is a BUTTON inside a container whose class contains select/dropdown/picker/dd
    if (el.tagName.toLowerCase() === "button") {
      let p = el.parentElement;
      let depth = 0;
      while (p && depth < 3) {
        const pc = (p.className || "").toString().toLowerCase();
        if (/\b(dd|select|dropdown|picker|combobox|autocomplete)\b/.test(pc)) return true;
        // Also check if sibling of the button exists and matches a popup pattern
        const sib = el.parentElement && el.parentElement.querySelector(
          ".dd-p, .dropdown-menu, [role=listbox], [role=menu], .MuiMenu-paper, .ant-select-dropdown, .chakra-menu__menu-list"
        );
        if (sib) return true;
        p = p.parentElement;
        depth++;
      }
    }
    return false;
  }

  function isDatePicker(el) {
    const tag = (el.tagName || "").toLowerCase();
    const type = (el.type || "").toLowerCase();
    if (tag === "input" && ["date", "datetime-local", "month", "time"].includes(type)) return true;
    const c = combinedLabel(el);
    if (/\b(date|calendar|birthday|dob)\b/.test(c)) {
      // Custom wrapper or input with date semantics
      const cls = (el.className || "").toString().toLowerCase();
      if (/date|calendar|picker/.test(cls)) return true;
      if (attr(el, "aria-haspopup") === "dialog" || attr(el, "aria-haspopup") === "grid") return true;
    }
    return false;
  }

  function hasCountryCode(el) {
    if (!el || (el.type || "").toLowerCase() !== "tel") return false;
    const root = el.closest("[class*=iti], .react-tel-input, [class*=phone-input], [class*=PhoneInput]");
    if (root) return true;
    // Sibling select containing country codes
    const par = el.parentElement;
    if (par) {
      const sel = par.querySelector("select");
      if (sel) {
        const first = (sel.options[0] && sel.options[0].textContent || "").toLowerCase();
        if (/\+\d|country|code/.test(first)) return true;
        const opts = Array.from(sel.options).slice(0, 5).map(o => o.textContent || "").join(" ");
        if (/\+\d{1,4}/.test(opts)) return true;
      }
    }
    return false;
  }

  // ===========================================================================
  // CAPTCHA / COOKIE DETECTION
  // ===========================================================================
  const CAPTCHA_SELECTORS = {
    recaptcha: ['iframe[src*="recaptcha" i]', '.g-recaptcha'],
    hcaptcha: ['iframe[src*="hcaptcha" i]', '.h-captcha'],
    turnstile: ['iframe[src*="challenges.cloudflare.com" i]', '.cf-turnstile'],
    funcaptcha: ['iframe[src*="arkoselabs" i]', 'iframe[src*="funcaptcha" i]'],
    friendly: ['iframe[src*="friendlycaptcha" i]'],
    unknown: ['[class*="captcha" i]:not([class*="captcha-hidden"])', '[id*="captcha" i]'],
  };

  function detectCaptcha(doc) {
    doc = doc || document;
    for (const [type, sels] of Object.entries(CAPTCHA_SELECTORS)) {
      for (const s of sels) {
        try {
          const nodes = doc.querySelectorAll(s);
          for (const n of nodes) if (isVisible(n)) return type;
        } catch (_) {}
      }
    }
    return null;
  }

  const COOKIE_SELECTORS = [
    '[id*="onetrust" i]', '[class*="onetrust" i]',
    '[id*="cookie" i][role="dialog"]', '[class*="cookie" i][role="dialog"]',
    '[id*="consent" i]', '[class*="consent" i]',
    '[id*="gdpr" i]', '[class*="gdpr" i]',
    '#CybotCookiebotDialog', '.cc-window', '.cookiebanner', '.cookie-banner',
    '[aria-label*="cookie" i]', '[aria-label*="consent" i]',
  ];

  function detectCookieBanner(doc) {
    doc = doc || document;
    for (const s of COOKIE_SELECTORS) {
      try {
        const nodes = doc.querySelectorAll(s);
        for (const n of nodes) {
          if (!isVisible(n)) continue;
          const btns = n.querySelectorAll('button, [role=button], a');
          for (const b of btns) {
            const t = txt(b).toLowerCase();
            if (/^(accept|agree|allow|ok|got it|i agree|continue|accept all|accept cookies)$/i.test(t) ||
                /принять|соглас|приемам|수락|동의|同意|接受|同意する|أوافق|aceptar|accepter|akzeptieren|aceitar|accetta/i.test(t)) {
              return { node: n, acceptBtn: b };
            }
          }
        }
      } catch (_) {}
    }
    return null;
  }

  // ===========================================================================
  // DOM TRAVERSAL (Shadow + same-origin iframe aware)
  // ===========================================================================
  function collectAll(doc, fieldSelector, buttonSelector, linkSelector) {
    const out = { fields: [], buttons: [], links: [], sources: [] };
    const seen = new Set();
    let count = 0;

    function walk(root, depth) {
      if (!root || depth > CFG.MAX_SHADOW_DEPTH || count >= CFG.MAX_ELEMENTS) return;
      let nodeList;
      try {
        nodeList = root.querySelectorAll("*");
      } catch (_) {
        return;
      }
      for (const el of nodeList) {
        count++;
        if (count >= CFG.MAX_ELEMENTS) break;
        // Shadow DOM
        if (el.shadowRoot && !seen.has(el.shadowRoot)) {
          seen.add(el.shadowRoot);
          walk(el.shadowRoot, depth + 1);
        }
      }
    }

    try { out.fields = Array.from(doc.querySelectorAll(fieldSelector)); } catch (_) {}
    try { out.buttons = Array.from(doc.querySelectorAll(buttonSelector)); } catch (_) {}
    try { out.links = Array.from(doc.querySelectorAll(linkSelector)); } catch (_) {}
    walk(doc, 0);
    return out;
  }

  // Gather fields/buttons from same-origin iframes (recursive).
  function collectFromIframes(fieldSelector, buttonSelector, linkSelector) {
    const collected = { fields: [], buttons: [], links: [], iframeCount: 0, crossOriginCount: 0 };

    function recurse(doc, depth) {
      if (depth > CFG.MAX_IFRAME_DEPTH) return;
      let iframes;
      try { iframes = doc.querySelectorAll("iframe"); } catch (_) { return; }
      for (const fr of iframes) {
        collected.iframeCount++;
        try {
          const cd = fr.contentDocument;
          if (!cd) { collected.crossOriginCount++; continue; }
          // Recurse into same-origin iframe
          try { collected.fields.push(...Array.from(cd.querySelectorAll(fieldSelector))); } catch (_) {}
          try { collected.buttons.push(...Array.from(cd.querySelectorAll(buttonSelector))); } catch (_) {}
          try { collected.links.push(...Array.from(cd.querySelectorAll(linkSelector))); } catch (_) {}
          recurse(cd, depth + 1);
        } catch (_) {
          collected.crossOriginCount++;
        }
      }
    }
    recurse(doc, 0);
    return collected;
  }

  // ===========================================================================
  // FIELD SCAN
  // ===========================================================================
  const FIELD_SELECTOR =
    'input:not([type=hidden]):not([type=submit]):not([type=button]):not([type=reset]):not([type=image]):not([type=file]),' +
    'textarea,' +
    'select,' +
    '[role=combobox],' +
    '[role=textbox],' +
    '[contenteditable="true"],' +
    '[contenteditable=""]';

  const BUTTON_SELECTOR =
    'button,' +
    'a[role=button],' +
    'input[type=submit],' +
    'input[type=button],' +
    'input[type=image],' +
    '[role=button]:not(a),' +
    '[onclick]';

  const LINK_SELECTOR = 'a[href]';

  function buildFieldRecord(el, idx) {
    const tag = (el.tagName || "").toLowerCase();
    const type = (el.type || "").toLowerCase();
    const ref = getRef(el);
    const looksLike = classify(el) || (tag === "select" ? "" : "text");
    const kind = detectFieldKind(el, tag, type);

    return {
      index: idx,
      ref,
      tag,
      type,
      kind, // native | custom-select | date | phone-cc | contenteditable
      name: attr(el, "name") || findLabel(el) || el.id || "",
      id: el.id || "",
      placeholder: attr(el, "placeholder"),
      ariaLabel: attr(el, "aria-label"),
      looksLike: looksLike || "text",
      currentValue: readValue(el),
      checked: !!(el.checked),
      required: !!(el.required || attr(el, "aria-required") === "true"),
      disabled: isDisabled(el),
      readonly: !!(el.readOnly),
      hasCountryCode: hasCountryCode(el),
      isInsideShadow: isInsideShadow(el),
      isInsideIframe: isInsideIframe(el),
    };
  }

  function detectFieldKind(el, tag, type) {
    if (tag === "select") return "native";
    if (tag === "input" && ["date", "datetime-local", "month", "time"].includes(type)) return "date";
    if (tag === "input" && type === "tel" && hasCountryCode(el)) return "phone-cc";
    if (el.isContentEditable) return "contenteditable";
    if (isCustomSelectTrigger(el)) return "custom-select";
    if (isDatePicker(el)) return "date";
    return "native";
  }

  function readValue(el) {
    const tag = (el.tagName || "").toLowerCase();
    const type = (el.type || "").toLowerCase();
    if (tag === "select") {
      const opt = el.options[el.selectedIndex];
      return opt ? opt.textContent.trim() : "";
    }
    if (type === "checkbox" || type === "radio") return el.checked ? "true" : "";
    if (el.isContentEditable) return (el.innerText || "").trim();
    return el.value != null ? String(el.value) : "";
  }

  function isInsideShadow(el) {
    let n = el;
    while (n) {
      if (n.parentNode && n.parentNode instanceof ShadowRoot) return true;
      n = n.parentNode;
    }
    return false;
  }

  function isInsideIframe(el) {
    return el.ownerDocument !== document;
  }

  // ===========================================================================
  // BUTTON SCAN
  // ===========================================================================
  function scoreButton(el) {
    let score = 0;
    const label = (txt(el) || attr(el, "value") || attr(el, "aria-label") || "").trim();
    const type = (el.type || "").toLowerCase();

    if (type === "submit") score += 50;
    if (el.tagName.toLowerCase() === "button" && !type) score += 10;
    if (el.form) score += 20;
    if (BTN_POS.test(label)) score += 100;
    if (BTN_NEG.test(label)) score -= 150;
    // hidden/disabled → skip
    if (!isVisible(el)) return -9999;
    if (isDisabled(el)) return -9999;
    return score;
  }

  function buildButtonRecord(el) {
    const label = (txt(el) || attr(el, "value") || attr(el, "aria-label") || "").trim().slice(0, 60);
    return {
      ref: getRef(el),
      label,
      type: (el.type || el.tagName || "").toLowerCase(),
      role: attr(el, "role"),
      isSubmit: (el.type || "").toLowerCase() === "submit",
      score: scoreButton(el),
      isNextCandidate: scoreButton(el) >= 100,
      disabled: isDisabled(el),
      isInsideShadow: isInsideShadow(el),
      isInsideIframe: isInsideIframe(el),
    };
  }

  function buildLinkRecord(el) {
    return {
      ref: getRef(el),
      text: txt(el).slice(0, 60),
      href: attr(el, "href"),
      isExternal: /^https?:/i.test(attr(el, "href")) && !attr(el, "href").startsWith(location.origin),
      isInsideShadow: isInsideShadow(el),
      isInsideIframe: isInsideIframe(el),
    };
  }

  // ===========================================================================
  // MAIN SCAN
  // ===========================================================================
  function scanPage() {
    if (document.readyState === "loading") {
      return emptyResult();
    }

    const fromDoc = collectAll(document, FIELD_SELECTOR, BUTTON_SELECTOR, LINK_SELECTOR);
    const fromFrames = collectFromIframes(FIELD_SELECTOR, BUTTON_SELECTOR, LINK_SELECTOR);

    const allFields = [...fromDoc.fields, ...fromFrames.fields];
    const allButtons = [...fromDoc.buttons, ...fromFrames.buttons];
    const allLinks = [...fromDoc.links, ...fromFrames.links];

    // Filter + record
    const fields = [];
    const seenFields = new Set();
    for (const el of allFields) {
      try {
        if (!el || !el.isConnected) continue;
        if (!isVisible(el)) continue;
        if (isDisabled(el)) continue;
        if (el.type === "search") continue;
        if (el.closest && el.closest('[role=search]')) continue;
        if (seenFields.has(el)) continue;
        seenFields.add(el);
        fields.push(buildFieldRecord(el, fields.length));
      } catch (_) {}
    }

    // Buttons: score, sort, cap
    const buttonsScored = [];
    const seenButtons = new Set();
    for (const el of allButtons) {
      try {
        if (!el || !el.isConnected) continue;
        if (seenButtons.has(el)) continue;
        seenButtons.add(el);
        const r = buildButtonRecord(el);
        if (r.score > -9999) buttonsScored.push(r);
      } catch (_) {}
    }
    buttonsScored.sort((a, b) => b.score - a.score);
    const buttons = buttonsScored.slice(0, CFG.MAX_BUTTONS_OUT);

    // Links: cap
    const links = [];
    const seenLinks = new Set();
    for (const el of allLinks) {
      try {
        if (!el || !el.isConnected || !isVisible(el)) continue;
        if (seenLinks.has(el)) continue;
        seenLinks.add(el);
        links.push(buildLinkRecord(el));
        if (links.length >= CFG.MAX_LINKS_OUT) break;
      } catch (_) {}
    }

    const bestBtn = buttons[0] || null;
    const bestIdx = bestBtn ? bestBtn.ref : "";
    const bestName = bestBtn ? bestBtn.label : "";

    const captchaType = detectCaptcha(document);
    const cookieBanner = detectCookieBanner(document);

    // Report visible error messages (for post-click verdict)
    const errorMessages = [];
    try {
      const errEls = document.querySelectorAll(
        '.error, [class*="error" i], [class*="invalid" i], [role=alert], .Mui-error, .ant-form-item-explain-error, [aria-invalid="true"]'
      );
      for (const e of errEls) {
        if (isVisible(e)) {
          const t = txt(e).slice(0, 120);
          if (t) errorMessages.push(t);
        }
        if (errorMessages.length >= 5) break;
      }
    } catch (_) {}

    sendToBridge(fields);

    return {
      url: location.href,
      title: document.title || "",
      readyState: document.readyState,
      fields,
      buttons,
      links,
      actionButtonRef: bestIdx,
      actionButtonName: bestName,
      captchaDetected: !!captchaType,
      captchaType: captchaType || "",
      cookieBannerPresent: !!cookieBanner,
      hasIframes: fromFrames.iframeCount > 0,
      iframeCount: fromFrames.iframeCount,
      crossOriginIframeCount: fromFrames.crossOriginIframeCount,
      errorMessages,
      timestamp: Date.now(),
    };
  }

  function emptyResult() {
    return {
      url: location.href,
      fields: [],
      buttons: [],
      links: [],
      actionButtonRef: "",
      actionButtonName: "",
      captchaDetected: false,
      captchaType: "",
      cookieBannerPresent: false,
      hasIframes: false,
      iframeCount: 0,
      crossOriginIframeCount: 0,
      errorMessages: [],
    };
  }

  // ===========================================================================
  // BRIDGE CALLBACK
  // ===========================================================================
  function sendToBridge(fields) {
    const payload = JSON.stringify(fields);
    let attempts = 0;
    (function trySend() {
      attempts++;
      try {
        if (window.AutomationBridge && typeof window.AutomationBridge.onFieldsDetected === "function") {
          window.AutomationBridge.onFieldsDetected(payload);
          return;
        }
      } catch (_) {}
      if (attempts < 5) setTimeout(trySend, 200);
    })();
  }

  // ===========================================================================
  // FILL
  // ===========================================================================
  async function fillFields(fieldsJson, optsJson) {
    const items = safeJsonParse(fieldsJson, []);
    const opts = safeJsonParse(optsJson, {});
    const typingDelay = typeof opts.typingDelayMs === "number" ? opts.typingDelayMs : CFG.FILL_DELAY_MS;
    const fieldDelay = typeof opts.fieldDelayMs === "number" ? opts.fieldDelayMs : 100;
    const humanize = !!opts.humanize;

    const results = [];
    for (const item of items) {
      try {
        const el = getByRef(item.ref);
        if (!el) {
          results.push({ ref: item.ref, ok: false, err: "not_found" });
          continue;
        }
        const ok = await fillOne(el, item.value, { typingDelay, humanize });
        results.push({ ref: item.ref, ok });
      } catch (e) {
        results.push({ ref: item.ref, ok: false, err: String((e && e.message) || e) });
      }
      if (fieldDelay) await sleep(fieldDelay);
    }
    return {
      filled: results.filter(r => r.ok).length,
      failed: results.filter(r => !r.ok).length,
      results,
    };
  }

  async function fillOne(el, value, opts) {
    const tag = (el.tagName || "").toLowerCase();
    const type = (el.type || "").toLowerCase();

    // Native select
    if (tag === "select") {
      return fillNativeSelect(el, value);
    }
    // Checkbox / radio
    if (tag === "input" && (type === "checkbox" || type === "radio")) {
      return fillCheckbox(el, value);
    }
    // Native date/time
    if (tag === "input" && ["date", "datetime-local", "month", "time"].includes(type)) {
      return fillDateInput(el, value);
    }
    // Native text-like
    if (tag === "input" || tag === "textarea") {
      return fillNativeText(el, value, opts);
    }
    // Contenteditable
    if (el.isContentEditable) {
      return fillContentEditable(el, value, opts);
    }
    // Custom select wrapper
    if (isCustomSelectTrigger(el)) {
      return fillCustomSelect(el, value, opts);
    }
    // Fallback: descend into inner editable
    const inner = el.querySelector("input, textarea, select, [contenteditable]");
    if (inner && inner !== el) return fillOne(inner, value, opts);

    return false;
  }

  function fillNativeSelect(el, value) {
    if (!el.options || !el.options.length) return false;
    const target = value.trim();
    let matched = null;
    // 1. exact value match
    for (const o of el.options) if ((o.value || "").trim() === target) { matched = o; break; }
    // 2. exact text match
    if (!matched) for (const o of el.options) if ((o.textContent || "").trim() === target) { matched = o; break; }
    // 3. case-insensitive text
    if (!matched) for (const o of el.options) if ((o.textContent || "").trim().toLowerCase() === target.toLowerCase()) { matched = o; break; }
    // 4. partial
    if (!matched) for (const o of el.options) if ((o.textContent || "").toLowerCase().includes(target.toLowerCase())) { matched = o; break; }

    if (!matched) return false;
    el.value = matched.value;
    el.dispatchEvent(new Event("input", { bubbles: true }));
    el.dispatchEvent(new Event("change", { bubbles: true }));
    return true;
  }

  function fillCheckbox(el, value) {
    const want = /^(1|true|yes|on|checked)$/i.test(value.trim());
    if (el.checked !== want) {
      try { el.click(); } catch (_) {
        el.checked = want;
        el.dispatchEvent(new Event("change", { bubbles: true }));
      }
    }
    return true;
  }

  function fillDateInput(el, value) {
    // value is expected in the input's own format (YYYY-MM-DD, etc.)
    try {
      const setter = Object.getOwnPropertyDescriptor(HTMLInputElement.prototype, "value").set;
      setter.call(el, value);
      el.dispatchEvent(new Event("input", { bubbles: true }));
      el.dispatchEvent(new Event("change", { bubbles: true }));
      el.dispatchEvent(new Event("blur", { bubbles: true }));
      return true;
    } catch (_) { return false; }
  }

  async function fillNativeText(el, value, opts) {
    try {
      el.focus();
    } catch (_) {}

    const proto = el.tagName.toLowerCase() === "textarea"
      ? HTMLTextAreaElement.prototype
      : HTMLInputElement.prototype;
    const setterDesc = Object.getOwnPropertyDescriptor(proto, "value");
    const setter = setterDesc && setterDesc.set;

    if (!opts.humanize) {
      try {
        if (setter) setter.call(el, value);
        else el.value = value;
      } catch (_) { el.value = value; }
      el.dispatchEvent(new Event("input", { bubbles: true }));
      el.dispatchEvent(new Event("change", { bubbles: true }));
    } else {
      // Type character by character (for humanized mode)
      el.value = "";
      const typingDelay = opts.typingDelay || 60;
      for (let i = 0; i < value.length; i++) {
        el.value += value[i];
        el.dispatchEvent(new Event("input", { bubbles: true }));
        await sleep(typingDelay * (0.5 + Math.random()));
      }
      el.dispatchEvent(new Event("change", { bubbles: true }));
    }
    try { el.blur(); } catch (_) {}
    return true;
  }

  async function fillContentEditable(el, value, opts) {
    try { el.focus(); } catch (_) {}
    try {
      el.innerText = value;
      el.dispatchEvent(new InputEvent("input", { bubbles: true, data: value, inputType: "insertText" }));
    } catch (_) {
      el.textContent = value;
      el.dispatchEvent(new Event("input", { bubbles: true }));
    }
    return true;
  }

  async function fillCustomSelect(el, value, opts) {
    // Find the actual clickable trigger
    const trigger =
      (el.matches && el.matches("button,[role=combobox],[aria-haspopup]") ? el : null) ||
      el.querySelector("button, [role=combobox], [aria-haspopup], .ctl, .select-trigger, .MuiSelect-select, .ant-select-selector, .chakra-select__trigger");

    if (!trigger) return false;
    simulateClick(trigger);
    await sleep(300);

    const target = value.trim().toLowerCase();
    // Search across the document for visible option elements
    const candidates = findVisibleOptions(el.ownerDocument || document);
    let chosen = null;
    // exact text
    for (const c of candidates) if ((txt(c) || "").toLowerCase() === target) { chosen = c; break; }
    // partial
    if (!chosen) for (const c of candidates) if ((txt(c) || "").toLowerCase().includes(target)) { chosen = c; break; }
    // value attribute
    if (!chosen) for (const c of candidates) if (attr(c, "data-value").toLowerCase() === target) { chosen = c; break; }

    if (!chosen) {
      // Close the popup
      simulateClick(trigger);
      await sleep(150);
      return false;
    }
    simulateClick(chosen);
    await sleep(250);
    return true;
  }

  function findVisibleOptions(doc) {
    const sels = [
      '[role=option]',
      '[role=menuitem]',
      '.MuiMenuItem-root',
      '.ant-select-item-option',
      '.chakra-menu__menuitem',
      '[data-radix-collection-item]',
      '.dd-i',
      '.dropdown-item',
      '.select-option',
    ];
    const out = [];
    for (const s of sels) {
      try {
        for (const n of doc.querySelectorAll(s)) {
          if (isVisible(n)) out.push(n);
        }
      } catch (_) {}
    }
    return out;
  }

  function safeJsonParse(s, fallback) {
    if (typeof s !== "string") return fallback;
    try { return JSON.parse(s); } catch (_) { return fallback; }
  }

  // ===========================================================================
  // CLICK
  // ===========================================================================
  function simulateClick(el) {
    if (!el) return false;
    try { el.scrollIntoView({ block: "center", behavior: "instant" }); } catch (_) {
      try { el.scrollIntoView(); } catch (_) {}
    }
    try {
      const r = el.getBoundingClientRect();
      const cx = r.left + r.width / 2;
      const cy = r.top + r.height / 2;
      const opts = { bubbles: true, cancelable: true, clientX: cx, clientY: cy, button: 0, view: window };
      try { el.dispatchEvent(new PointerEvent("pointerdown", opts)); } catch (_) {}
      try { el.dispatchEvent(new MouseEvent("mousedown", opts)); } catch (_) {}
      try { el.dispatchEvent(new PointerEvent("pointerup", opts)); } catch (_) {}
      try { el.dispatchEvent(new MouseEvent("mouseup", opts)); } catch (_) {}
      el.click();
      return true;
    } catch (_) {
      try { el.click(); return true; } catch (_) {}
      return false;
    }
  }

  async function clickNextAndSettle(optsJson) {
    const opts = safeJsonParse(optsJson, {});
    const settleTimeoutMs = typeof opts.settleTimeoutMs === "number" ? opts.settleTimeoutMs : 2000;
    const pauseOnCaptcha = opts.pauseOnCaptcha !== false;

    let el = opts.actionRef ? getByRef(opts.actionRef) : null;
    if (!el) {
      // Find a "next" candidate
      const cands = Array.from(document.querySelectorAll(BUTTON_SELECTOR))
        .map(b => ({ el: b, score: scoreButton(b) }))
        .filter(x => x.score >= 100)
        .sort((a, b) => b.score - a.score);
      el = cands[0] ? cands[0].el : null;
    }
    if (!el) {
      return { verdict: "no_button_found" };
    }

    const urlBefore = location.href;
    const titleBefore = document.title;
    const nodesBefore = document.querySelectorAll("*").length;

    simulateClick(el);

    const start = Date.now();
    let urlChanged = false;
    let nodesChanged = false;
    let captchaAppeared = false;
    let errorAppeared = false;

    while (Date.now() - start < settleTimeoutMs) {
      await sleep(CFG.CLICK_SETTLE_POLL_MS);

      if (location.href !== urlBefore) { urlChanged = true; break; }
      if (document.title !== titleBefore) { nodesChanged = true; }
      const nodesNow = document.querySelectorAll("*").length;
      if (Math.abs(nodesNow - nodesBefore) > 15) { nodesChanged = true; }

      if (pauseOnCaptcha && detectCaptcha(document)) {
        captchaAppeared = true;
        break;
      }
      const errs = document.querySelectorAll('[role=alert], .error, [class*="error" i]:not([class*="no-error"])');
      for (const e of errs) {
        if (isVisible(e) && txt(e).length > 2) { errorAppeared = true; break; }
      }
      if (errorAppeared) break;
    }

    // Final verdict
    if (captchaAppeared) return { verdict: "captcha_blocked" };
    if (urlChanged) return { verdict: "navigated" };
    if (errorAppeared) return { verdict: "validation_error" };
    if (nodesChanged) return { verdict: "same_page" };
    return { verdict: "clicked_no_change" };
  }

  // ===========================================================================
  // COOKIE BANNER DISMISS
  // ===========================================================================
  function dismissCookieBanner() {
    const banner = detectCookieBanner(document);
    if (!banner) return { dismissed: false };
    try {
      simulateClick(banner.acceptBtn);
      return { dismissed: true };
    } catch (_) {
      return { dismissed: false };
    }
  }

  // ===========================================================================
  // SCHEDULING
  // ===========================================================================
  let throttleTimer = null;
  let lastScanAt = 0;
  function scheduleScan() {
    const now = Date.now();
    const elapsed = now - lastScanAt;
    if (elapsed >= CFG.THROTTLE_MS) {
      lastScanAt = now;
      try { scanPage(); } catch (_) {}
    } else if (!throttleTimer) {
      throttleTimer = setTimeout(() => {
        throttleTimer = null;
        lastScanAt = Date.now();
        try { scanPage(); } catch (_) {}
      }, CFG.THROTTLE_MS - elapsed);
    }
  }

  try {
    new MutationObserver(scheduleScan).observe(document.documentElement || document, {
      subtree: true, childList: true, attributes: true, attributeFilter: ["style", "class", "hidden", "disabled"],
    });
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

  // ===========================================================================
  // MOUNT GLOBALS
  // ===========================================================================
  g.__automationScan = scanPage;
  g.__automationFill = fillFields;
  g.__automationClickNextAndSettle = clickNextAndSettle;
  g.__automationDismissCookieBanner = dismissCookieBanner;

  // Initial scan
  try { scanPage(); } catch (_) {}
})();