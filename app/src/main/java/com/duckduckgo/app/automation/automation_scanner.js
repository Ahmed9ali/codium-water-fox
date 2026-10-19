(function() {
    if (window.__automationScannerInstalled) return;
    window.__automationScannerInstalled = true;

    function findLabelText(el) {
        var aria = el.getAttribute('aria-label');
        if (aria) return aria;
        var id = el.id;
        if (id) {
            try {
                var label = document.querySelector('label[for="' + CSS.escape(id) + '"]');
                if (label) return label.textContent.trim();
            } catch (e) {}
        }
        var parent = el.closest && el.closest('label');
        if (parent) return parent.textContent.trim();
        return el.placeholder || '';
    }

    function classifyField(el) {
        var hint = ((el.name || '') + ' ' + (el.id || '') + ' ' +
                    (el.placeholder || '') + ' ' +
                    (el.getAttribute('aria-label') || '') + ' ' +
                    findLabelText(el)).toLowerCase();
        if (el.type === 'email') return 'email';
        if (el.type === 'tel') return 'phone';
        if (el.type === 'password') return 'password';
        if (el.type === 'date') return 'dob';
        if (/email|mail/.test(hint)) return 'email';
        if (/confirm.*password|password.*confirm|retype.*password|repeat.*password/.test(hint)) return 'confirmPassword';
        if (/password|pwd/.test(hint)) return 'password';
        if (/confirm.*email|email.*confirm|retype.*email|repeat.*email/.test(hint)) return 'confirmEmail';
        if (/confirm.*phone|phone.*confirm|retype.*phone|repeat.*phone/.test(hint)) return 'confirmPhone';
        if (/phone|mobile|contact|tel/.test(hint)) return 'phone';
        if (/first.*name|fname|given/.test(hint)) return 'firstName';
        if (/last.*name|lname|surname|family/.test(hint)) return 'lastName';
        if (/middle.*name|mname/.test(hint)) return 'middleName';
        if (/nick|alias/.test(hint)) return 'nickname';
        if (/user.*name|username|user.*id|handle|login/.test(hint)) return 'username';
        if (/full.*name|^name$|your.*name/.test(hint)) return 'fullName';
        if (/dob|birth|date.*birth/.test(hint)) return 'dob';
        if (/gender|sex/.test(hint)) return 'gender';
        if (/address|street|addr|house/.test(hint)) return 'address';
        if (/city|town/.test(hint)) return 'city';
        if (/zip|postal|pin.*code|pincode/.test(hint)) return 'zip';
        if (/country/.test(hint)) return 'country';
        if (/state|province|region/.test(hint)) return 'state';
        if (/company|organization|organisation/.test(hint)) return 'company';
        if (/job.*title|designation|position/.test(hint)) return 'jobTitle';
        return 'unknown';
    }

    function scan() {
        try {
            var fields = document.querySelectorAll('input, select, textarea');
            var detected = [];
            fields.forEach(function(el, index) {
                if (el.type === 'hidden') return;
                var rect = el.getBoundingClientRect();
                if (rect.width === 0 && rect.height === 0) return;
                detected.push({
                    index: index,
                    type: el.type || el.tagName.toLowerCase(),
                    name: el.name || '',
                    id: el.id || '',
                    looksLike: classifyField(el)
                });
            });
            if (window.AutomationBridge && window.AutomationBridge.onFieldsDetected) {
                window.AutomationBridge.onFieldsDetected(JSON.stringify(detected));
            }
        } catch (e) {}
    }

    function setValueAndNotify(el, value) {
        // Handles both native inputs and React/Vue controlled components
        try {
            var proto = Object.getPrototypeOf(el);
            var descriptor = Object.getOwnPropertyDescriptor(proto, 'value');
            if (descriptor && descriptor.set) {
                descriptor.set.call(el, value);
            } else {
                el.value = value;
            }
        } catch (e) {
            el.value = value;
        }
        el.dispatchEvent(new Event('input', { bubbles: true }));
        el.dispatchEvent(new Event('change', { bubbles: true }));
    }

    function fillSelect(el, value) {
        // 4.6: native <select> support
        var options = el.options;
        var lv = (value || '').toLowerCase();
        for (var i = 0; i < options.length; i++) {
            var optText = (options[i].textContent || '').trim().toLowerCase();
            var optValue = (options[i].value || '').toLowerCase();
            if (optText === lv || optValue === lv) {
                el.selectedIndex = i;
                setValueAndNotify(el, options[i].value);
                return true;
            }
        }
        // Partial match fallback
        for (var j = 0; j < options.length; j++) {
            var t = (options[j].textContent || '').trim().toLowerCase();
            if (t.indexOf(lv) !== -1 && lv.length > 1) {
                el.selectedIndex = j;
                setValueAndNotify(el, options[j].value);
                return true;
            }
        }
        return false;
    }

    function sleep(ms) {
        return new Promise(function(resolve) { setTimeout(resolve, ms); });
    }

    // 4.9: human-like delays between fills
    window.__automationFill = async function(dataJson) {
        try {
            var data = JSON.parse(dataJson);
            var fields = document.querySelectorAll('input, select, textarea');
            for (var i = 0; i < data.length; i++) {
                var item = data[i];
                var el = fields[item.index];
                if (!el) continue;

                var tag = el.tagName.toLowerCase();
                if (tag === 'select') {
                    fillSelect(el, item.value);
                } else {
                    el.focus();
                    setValueAndNotify(el, item.value);
                    el.blur();
                }
                // 30-150ms random delay between fields
                var delay = 30 + Math.floor(Math.random() * 120);
                await sleep(delay);
            }
        } catch (e) {}
    };

    if (document.readyState === 'complete' || document.readyState === 'interactive') {
        setTimeout(scan, 600);
    } else {
        document.addEventListener('DOMContentLoaded', function() { setTimeout(scan, 600); });
    }

    var observer = new MutationObserver(function() {
        clearTimeout(window.__automationScanTimeout);
        window.__automationScanTimeout = setTimeout(scan, 1000);
    });
    if (document.body) observer.observe(document.body, { childList: true, subtree: true });
})();