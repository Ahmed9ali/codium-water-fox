package com.duckduckgo.app.automation

import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Gravity
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.WebStorage
import android.webkit.WebView
import android.widget.FrameLayout
import android.widget.Toast
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.webkit.ScriptHandler
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject
import java.lang.ref.WeakReference
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlin.random.Random

data class AutomationConfig(
    val targetUrl: String,
    val groupNames: List<String>,
    val defaultGroupName: String? = null,
    val mode: String,
    val targetRepeatCount: Int = 1,
    val humanize: Boolean = false,
    val typingDelayMs: Long = 60,
    val fieldDelayMs: Long = 100,
    val settleTimeoutMs: Long = 2000,
    val pauseOnCaptcha: Boolean = true,
    val clearTabBetweenRuns: Boolean = true,
    // When non-null, runScriptExecution (scripted) is used instead of runAutomationLoop (dynamic).
    val script: AutomationScript? = null,
)

enum class AutomationState {
    IDLE, NAVIGATING, SCANNING, FILLING, CLICKING_NEXT, WAITING_SETTLE,
    WAITING_CAPTCHA_HUMAN, WAITING_DATA_EXHAUSTED, CLEARING_DATA, COMPLETED, PAUSED, ERROR,
}

enum class RecordingPhase { Capturing, Saving }

/**
 * Receives results from async JavaScript.
 *
 * WHY THIS EXISTS: WebView.evaluateJavascript() does NOT wait for Promises. When the script returns a Promise
 * (any async function) the callback fires immediately with "{}". That made every Field/Click step look like it
 * "failed" instantly. Instead, the JS now calls AutomationAsync.resolve(callId, json) when it is really done.
 *
 * Must be a public class with a public @JavascriptInterface method (reflection access).
 */
class AutomationAsyncBridge {
    val pending = ConcurrentHashMap<String, CompletableDeferred<String>>()

    @android.webkit.JavascriptInterface
    fun resolve(callId: String, result: String?) {
        pending.remove(callId)?.complete(result ?: "")
    }
}

object AutomationEngine {
    private const val TAG = "AutomationEngine"
    private const val HUD_VIEW_TAG = "ddg_automation_hud_overlay"
    private const val ASYNC_BRIDGE_NAME = "AutomationAsync"
    private const val MAX_WAIT_MS = 300_000L
    private const val PAGE_READY_TIMEOUT_MS = 10_000L
    private const val PAGE_READY_POST_MS = 1_000L

    private val DYNAMIC_FIELDS = setOf("email", "confirmemail", "phone", "confirmphone", "username", "otp")

    private var currentWebView: WeakReference<WebView>? = null
    private var scannerJs: String? = null
    private var appContext: Context? = null
    private var automationJob: Job? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    private val asyncBridge = AutomationAsyncBridge()
    private val mainHandler = Handler(Looper.getMainLooper())
    private var recorderScriptHandler: ScriptHandler? = null
    private var lastRecordedAt = 0L

    @Volatile private var isInitialized = false

    private val _detectedFieldCount = MutableLiveData(0)
    val detectedFieldCount: LiveData<Int> get() = _detectedFieldCount
    private val _state = MutableLiveData(AutomationState.IDLE)
    val state: LiveData<AutomationState> get() = _state
    private val _completedCount = MutableLiveData(0)
    val completedCount: LiveData<Int> get() = _completedCount
    private val _totalTargetCount = MutableLiveData(1)
    val totalTargetCount: LiveData<Int> get() = _totalTargetCount
    private val _statusMessage = MutableLiveData("")
    val statusMessage: LiveData<String> get() = _statusMessage
    private val _captchaState = MutableLiveData(false)
    val captchaState: LiveData<Boolean> get() = _captchaState
    private val _dataExhaustedState = MutableLiveData(false)
    val dataExhaustedState: LiveData<Boolean> get() = _dataExhaustedState

    var hudVisible by mutableStateOf(false); private set
    var hudMode by mutableStateOf(HudMode.Hidden); private set
    var hudPositionTop by mutableStateOf(false); private set
    private var activeTabId: String? = null

    var hudState by mutableStateOf(AutomationState.IDLE); private set
    var hudCompleted by mutableIntStateOf(0); private set
    var hudTotal by mutableIntStateOf(1); private set
    var hudStatus by mutableStateOf(""); private set
    var hudGroupName by mutableStateOf(""); private set

    var onShowFireDialog: ((String) -> Unit)? = null
    private var fireDialogContinuation: CancellableContinuation<Unit>? = null

    private val _recordedSteps = MutableLiveData<List<RecordedStep>>(emptyList())
    val recordedSteps: LiveData<List<RecordedStep>> get() = _recordedSteps
    var hudRecordedSteps by mutableStateOf<List<RecordedStep>>(emptyList()); private set
    var hudRecordingPhase by mutableStateOf(RecordingPhase.Capturing); private set
    var hudRecordingPaused by mutableStateOf(false); private set
    var hudRecordingHost by mutableStateOf(""); private set
    var hudRecordingUrl by mutableStateOf(""); private set

    var hudAvailableScripts by mutableStateOf<List<AutomationScript>>(emptyList()); private set
    var hudSelectedScript by mutableStateOf<AutomationScript?>(null); private set
    var hudSelectedGroups by mutableStateOf<List<String>>(emptyList()); private set
    var hudSelectedDefaultGroup by mutableStateOf(""); private set
    var hudRunRepeatCount by mutableIntStateOf(1); private set
    var hudEditingScript by mutableStateOf<AutomationScript?>(null); private set

    private var currentConfig: AutomationConfig? = null
    private var pendingBrowserConfig: AutomationConfig? = null
    private var currentFieldsJson: JSONArray = JSONArray()
    private var currentActionRef: String? = null
    private var currentProfile: ActiveAutomationProfile? = null

    var onAutomationFinished: ((Int) -> Unit)? = null
    var onDataExhaustedAction: ((String) -> Unit)? = null

    fun init(context: Context) {
        if (isInitialized) return
        isInitialized = true
        appContext = context.applicationContext
        VaultRepository.init(context)
        AutomationScriptRepository.init(context)
        hudMode = HudMode.Hidden
        hudVisible = false
        hudRecordingPaused = false
        hudRecordedSteps = emptyList()
        hudRecordingPhase = RecordingPhase.Capturing
        currentWebView = null
    }

    private fun updateState(newState: AutomationState) {
        _state.postValue(newState)
        hudState = newState
        if (newState != AutomationState.IDLE) hudVisible = true
    }

    private fun updateStatus(msg: String) {
        _statusMessage.postValue(msg)
        hudStatus = msg
        Log.d(TAG, "Status: $msg")
    }

    private fun updateProgress(done: Int, total: Int) {
        _completedCount.postValue(done)
        _totalTargetCount.postValue(total)
        hudCompleted = done
        hudTotal = total
    }

    fun attachWebView(webView: WebView, tabId: String? = null) {
        Log.d(TAG, "attachWebView tabId=$tabId")
        currentWebView = WeakReference(webView)
        if (tabId != null) activeTabId = tabId

        // Bridges: idempotent. addJavascriptInterface replaces by name; safe to re-call.
        try {
            val bridge = AutomationBridge(
                tabId = tabId,
                onFieldsDetectedCallback = { json, tid -> onFieldsDetected(json, tid) },
                onPageScannedCallback = { _, _ -> },
                onFillCompletedCallback = { _, _ -> },
                onStepSettledCallback = { _, _ -> },
                onElementRecordedCallback = { json, tid -> onRecorderElementCaptured(json, tid) },
            )
            webView.addJavascriptInterface(bridge, "AutomationBridge")
            // Async result channel used by evaluateJsSuspend()
            webView.addJavascriptInterface(asyncBridge, ASYNC_BRIDGE_NAME)
        } catch (e: Exception) {
            Log.e(TAG, "attachBridge failed: ${e.message}")
        }

        // HUD mount with retry (parent might not exist yet on very first page)
        mountHudOverlayIfNeeded(webView)

        // Consume pending config exactly once.
        val config = pendingBrowserConfig
        if (config != null) {
            pendingBrowserConfig = null
            Log.d(TAG, "attachWebView: consuming pendingBrowserConfig (script=${config.script?.name})")
            startAutomation(config, navigateFirstCycle = false)
        } else if (hudMode == HudMode.Recording && !hudRecordingPaused) {
            webView.postDelayed({
                loadRecorder()?.let { webView.evaluateJavascript(it) { Log.d(TAG, "Recorder re-injected after page event") } }
            }, 200)
        } else if (hudMode != HudMode.Recording && hudMode != HudMode.Running) {
            webView.evaluateJavascript("try { window.__automationRecorderStop(); } catch(_) {}", null)
        }
    }

    fun detachWebView() {
        Log.d(TAG, "detachWebView")
        currentWebView = null
        _detectedFieldCount.postValue(0)
    }

    /**
     * Call this from the browser's WebViewClient.onPageFinished(). While recording it re-injects the recorder
     * into every newly loaded page (the recorder JS must be idempotent).
     */
    fun onPageFinished(webView: WebView) {
        if (hudMode == HudMode.Recording && !hudRecordingPaused && hudRecordingPhase == RecordingPhase.Capturing) {
            loadRecorder()?.let { webView.evaluateJavascript(it, null) }
        }
    }

    fun injectScanner() {
        val webView = currentWebView?.get() ?: return
        val js = scannerJs ?: loadScanner() ?: return
        webView.evaluateJavascript(js) {
            webView.evaluateJavascript("try { if (window.__automationScan) window.__automationScan(); } catch(_) {}", null)
        }
    }

    private fun loadScanner(): String? = try {
        appContext?.assets?.open("automation/automation_scanner.js")?.bufferedReader()?.use { it.readText() }?.also { scannerJs = it }
    } catch (e: Exception) { null }

    fun onFieldsDetected(json: String, tabId: String?) {
        try {
            if (json.contains("\"debug\"")) return
            val arr = JSONArray(json)
            currentFieldsJson = arr
            _detectedFieldCount.postValue(arr.length())
        } catch (e: Exception) { _detectedFieldCount.postValue(0) }
    }

    fun openScriptList() {
        val host = currentWebView?.get()?.url?.let { AutomationScriptRepository.hostOfUrl(it) } ?: ""
        hudAvailableScripts = AutomationScriptRepository.scriptsForHost(host)
        hudMode = HudMode.ScriptList
    }

    fun openRunSetup(script: AutomationScript) {
        hudSelectedScript = script
        // Keep the script's group names so the HUD can warn if a group was deleted.
        hudSelectedGroups = script.groupNames
        hudSelectedDefaultGroup = script.defaultGroupName
        hudRunRepeatCount = 1
        hudMode = HudMode.RunSetup
    }

    fun updateRunGroups(groups: List<String>) { hudSelectedGroups = groups }
    fun updateRunDefaultGroup(group: String) { hudSelectedDefaultGroup = group }
    fun updateRunRepeat(count: Int) { hudRunRepeatCount = count.coerceIn(1, 999) }

    /**
     * Play button path from "My Scripts".
     * The config is only QUEUED; it is started either by attachWebView() (new tab) or by the 2s fallback below
     * (browser already open). Previously it started immediately on a stale WebView while BrowserActivity was also
     * navigating, so steps ran on the wrong page.
     */
    fun launchScriptFromList(script: AutomationScript, context: Context) {
        Log.d(TAG, "launchScriptFromList: ${script.name}, groups=${script.groupNames}")
        if (script.groupNames.isEmpty()) {
            Toast.makeText(context, "Script has no data assigned. Edit script first.", Toast.LENGTH_LONG).show()
            return
        }

        VaultRepository.init(context)

        val config = AutomationConfig(
            targetUrl = script.url,
            groupNames = script.groupNames,
            defaultGroupName = script.defaultGroupName.takeIf { it.isNotBlank() },
            mode = "series",
            targetRepeatCount = 1,
            humanize = script.humanize,
            script = script,
        )

        queueAutomationInBrowserTab(config)

        val browserIntent = com.duckduckgo.app.browser.BrowserActivity.intent(
            context = context,
            launchSource = com.duckduckgo.app.browser.mode.InAppNavigation,
            queryExtra = script.url,
        ).apply {
            addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        }
        context.startActivity(browserIntent)

        // Fallback: if the browser tab was already open, attachWebView() may never be called again.
        scope.launch {
            delay(2000)
            val cfg = pendingBrowserConfig ?: return@launch // already consumed by attachWebView()
            if (currentWebView?.get() != null) {
                pendingBrowserConfig = null
                startAutomation(cfg, navigateFirstCycle = false)
            }
        }
    }

    fun openScriptEditor(script: AutomationScript) {
        hudEditingScript = script
        hudMode = HudMode.EditScript
    }

    fun addStepToEditingScript(step: RecordedStep) {
        val current = hudEditingScript ?: return
        val newStep = step.copy(sequenceIndex = current.steps.size + 1)
        hudEditingScript = current.copy(steps = current.steps + newStep)
    }

    fun removeStepFromEditingScript(id: String) {
        val current = hudEditingScript ?: return
        val updatedSteps = current.steps.filterNot { it.id == id }.mapIndexed { i, s -> s.copy(sequenceIndex = i + 1) }
        hudEditingScript = current.copy(steps = updatedSteps)
    }

    fun moveStepTo(id: String, newIndex: Int) {
        val current = hudEditingScript ?: return
        val steps = current.steps.toMutableList()
        val idx = steps.indexOfFirst { it.id == id }
        if (idx == -1 || idx == newIndex) return
        if (newIndex !in steps.indices) return

        val step = steps.removeAt(idx)
        steps.add(newIndex, step)

        val reindexed = steps.mapIndexed { i, s -> s.copy(sequenceIndex = i + 1) }
        hudEditingScript = current.copy(steps = reindexed)
    }

    fun updateWaitStep(id: String, newWaitMs: Long) {
        val current = hudEditingScript ?: return
        val cappedMs = newWaitMs.coerceIn(0L, MAX_WAIT_MS)
        val steps = current.steps.map {
            if (it.id == id) it.copy(waitMs = cappedMs, displayLabel = "Wait ${cappedMs / 1000}s") else it
        }
        hudEditingScript = current.copy(steps = steps)
    }

    fun saveEditingScript() {
        val current = hudEditingScript ?: return
        AutomationScriptRepository.saveScript(current)
        Toast.makeText(appContext, "Script saved", Toast.LENGTH_SHORT).show()
        hudEditingScript = null
        openScriptList()
    }

    fun startSelectedScriptRun() {
        val script = hudSelectedScript ?: return
        if (hudSelectedGroups.isEmpty()) {
            Toast.makeText(appContext, "Select a recurring data group first", Toast.LENGTH_SHORT).show()
            return
        }
        val config = AutomationConfig(
            targetUrl = script.url,
            groupNames = hudSelectedGroups,
            defaultGroupName = hudSelectedDefaultGroup.takeIf { it.isNotBlank() },
            mode = "series",
            targetRepeatCount = hudRunRepeatCount,
            humanize = script.humanize,
            script = script,
        )
        hudMode = HudMode.Running
        hudVisible = true
        currentConfig = config
        hudGroupName = config.groupNames.joinToString(", ")
        updateProgress(0, config.targetRepeatCount)
        updateState(AutomationState.NAVIGATING)
        _captchaState.postValue(false)
        _dataExhaustedState.postValue(false)

        automationJob?.cancel()
        automationJob = scope.launch { runScriptExecution(script, config, navigateFirstCycle = true) }
    }

    fun pauseAutomation() {
        automationJob?.cancel()
        updateState(AutomationState.PAUSED)
        updateStatus("Automation paused.")
        currentWebView?.get()?.evaluateJavascript("try { window.__automation_active = false; } catch(_) {}", null)
    }

    fun resumeAutomation() {
        val script = hudSelectedScript ?: return
        val config = currentConfig ?: return
        updateState(AutomationState.NAVIGATING)
        automationJob?.cancel()
        currentWebView?.get()?.evaluateJavascript("try { window.__automation_active = true; } catch(_) {}", null)
        automationJob = scope.launch {
            if (config.script != null) runScriptExecution(config.script, config, navigateFirstCycle = false)
            else runAutomationLoop(config, navigateFirstCycle = false)
        }
    }

    fun stopAutomation() {
        Log.d(TAG, "stopAutomation")
        automationJob?.cancel()
        fireDialogContinuation?.cancel()
        fireDialogContinuation = null
        currentConfig = null
        pendingBrowserConfig = null
        currentProfile = null
        _captchaState.postValue(false)
        _dataExhaustedState.postValue(false)
        updateState(AutomationState.IDLE)
        updateStatus("")
        hudMode = HudMode.Hidden
        hudVisible = false
        currentWebView?.get()?.evaluateJavascript("try { window.__automation_active = false; } catch(_) {}", null)
    }

    fun continueAfterCaptchaResolution() {
        _captchaState.postValue(false)
        resumeAutomation()
    }

    fun handleDataExhaustedChoice(choice: String) {
        val script = hudSelectedScript ?: return
        val config = currentConfig ?: return
        _dataExhaustedState.postValue(false)
        if (choice == "repeat_same") {
            config.groupNames.forEach { VaultRepository.resetUsed(it) }
            updateState(AutomationState.NAVIGATING)
            automationJob?.cancel()
            currentWebView?.get()?.evaluateJavascript("try { window.__automation_active = true; } catch(_) {}", null)
            automationJob = scope.launch {
                if (config.script != null) runScriptExecution(config.script, config, navigateFirstCycle = false)
                else runAutomationLoop(config, navigateFirstCycle = false)
            }
        } else {
            stopAutomation()
            onDataExhaustedAction?.invoke(config.groupNames.firstOrNull() ?: "")
            appContext?.let { ctx ->
                val intent = Intent(ctx, AutomationActivity::class.java).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                ctx.startActivity(intent)
            }
        }
    }

    private fun resolveFieldValue(step: RecordedStep, recurringProfile: ActiveAutomationProfile, defaultProfile: ActiveAutomationProfile?): String {
        if (step.customValue.isNotBlank()) return step.customValue
        val looksLike = step.fieldType.lowercase()
        val isDynamic = DYNAMIC_FIELDS.contains(looksLike)
        val primary = if (isDynamic) recurringProfile else defaultProfile
        val fallback = if (isDynamic) defaultProfile else recurringProfile
        val v1 = primary?.resolveForField(step.fieldType, step.name, step.tag)
        if (!v1.isNullOrBlank()) return v1
        return fallback?.resolveForField(step.fieldType, step.name, step.tag) ?: ""
    }

    // ---------------------------------------------------------------------------------------------
    // JS BUILDERS (run through evaluateJsSuspend, which waits for the Promise to REALLY finish)
    // ---------------------------------------------------------------------------------------------

    /** Finds a link/button/clickable by id -> css -> xpath -> name -> visible text/aria-label/title, then clicks it. */
    private fun buildClickJs(step: RecordedStep): String {
        val labels = listOf(step.buttonLabel, step.displayLabel, step.ariaLabel)
            .map { it.trim() }
            .filter { it.isNotBlank() && !it.equals("Next button", ignoreCase = true) }
            .distinct()
        return """
        (async function() {
            const id = ${JSONObject.quote(step.domId)}, nm = ${JSONObject.quote(step.name)},
                  css = ${JSONObject.quote(step.cssSelector)}, xpath = ${JSONObject.quote(step.xpath)};
            const labels = ${JSONArray(labels)}.map(function(s) { return String(s).toLowerCase().replace(/\s+/g, ' ').trim(); });
            const SEL = 'a,button,input[type=submit],input[type=button],input[type=image],[role=button],[role=link],[role=menuitem],[onclick],summary';
            function visible(el) {
                if (!el || !el.isConnected) return false;
                try {
                    const st = getComputedStyle(el);
                    if (st.display === 'none' || st.visibility === 'hidden') return false;
                    const r = el.getBoundingClientRect();
                    return r.width > 0 && r.height > 0;
                } catch (_) { return false; }
            }
            function txt(el) { return ((el.innerText || el.textContent || el.value || '') + '').replace(/\s+/g, ' ').trim().toLowerCase(); }
            function attr(el, a) { return (el.getAttribute(a) || '').toLowerCase().trim(); }
            function textMatches(el, exact) {
                const t = txt(el), a = attr(el, 'aria-label'), ti = attr(el, 'title');
                return labels.some(function(l) {
                    return exact ? (t === l || a === l || ti === l) : (t.includes(l) || a.includes(l) || ti.includes(l));
                });
            }
            function clickable(el) { return (el.closest && el.closest(SEL)) || el; }
            function bySelectors() {
                const tries = [
                    ['id', function() { return id ? document.getElementById(id) : null; }],
                    ['css', function() { return css ? document.querySelector(css) : null; }],
                    ['xpath', function() { return xpath ? document.evaluate(xpath, document, null, 9, null).singleNodeValue : null; }],
                    ['name', function() { return nm ? document.getElementsByName(nm)[0] : null; }]
                ];
                for (const t of tries) {
                    try { const e = t[1](); if (e && visible(e)) return {el: e, via: t[0]}; } catch (_) {}
                }
                return null;
            }
            function byText() {
                if (!labels.length) return null;
                const all = Array.from(document.querySelectorAll(SEL)).filter(visible);
                let e = all.find(function(x) { return textMatches(x, true); }) || all.find(function(x) { return textMatches(x, false); });
                if (e) return e;
                const loose = Array.from(document.querySelectorAll('div,span,li,p')).filter(function(x) {
                    if (!visible(x)) return false;
                    try { return getComputedStyle(x).cursor === 'pointer'; } catch (_) { return false; }
                });
                return loose.find(function(x) { return textMatches(x, true); }) || null;
            }
            function find() {
                const s = bySelectors(), t = byText();
                if (s) {
                    // selector hit: trust it unless its text clearly differs from the recorded label
                    if (!labels.length || !txt(s.el) || textMatches(s.el, false)) return {el: clickable(s.el), via: s.via};
                    if (t) return {el: t, via: 'text'};
                    return {el: clickable(s.el), via: s.via + '?'};
                }
                return t ? {el: t, via: 'text'} : null;
            }
            const start = Date.now();
            let hit = null;
            while (Date.now() - start < 10000) {
                hit = find();
                if (hit) break;
                await new Promise(function(r) { setTimeout(r, 300); });
            }
            if (!hit) {
                const shown = Array.from(document.querySelectorAll('a,button')).filter(visible).slice(0, 10).map(function(x) { return txt(x).slice(0, 25); });
                return JSON.stringify({ok: false, err: 'click target not found. labels=' + JSON.stringify(labels) +
                    ' selectors(id/css/xpath/name)=' + [!!id, !!css, !!xpath, !!nm].join('/') +
                    ' visible a/button=' + JSON.stringify(shown)});
            }
            const el = hit.el;
            try {
                el.scrollIntoView({block: 'center'});
                await new Promise(function(r) { setTimeout(r, 250); });
                if (el.tagName === 'A' && el.target === '_blank') el.target = '_self';
                const r = el.getBoundingClientRect();
                const o = {bubbles: true, cancelable: true, clientX: r.left + r.width / 2, clientY: r.top + r.height / 2, button: 0, view: window};
                window.__ddgNavMark = true;
                try { el.focus(); } catch (_) {}
                try { el.dispatchEvent(new PointerEvent('pointerdown', o)); } catch (_) {}
                el.dispatchEvent(new MouseEvent('mousedown', o));
                try { el.dispatchEvent(new PointerEvent('pointerup', o)); } catch (_) {}
                el.dispatchEvent(new MouseEvent('mouseup', o));
                el.click();
                return JSON.stringify({ok: true, via: hit.via});
            } catch (e) { return JSON.stringify({ok: false, err: 'click error: ' + e.message}); }
        })()
        """.trimIndent()
    }

    /** Finds an input/textarea/select/checkbox and fills it (React-safe native setter, humanized typing optional). */
    private fun buildFillJs(step: RecordedStep, value: String, humanize: Boolean): String {
        val hints = listOf(step.placeholder, step.ariaLabel, step.displayLabel, step.fieldType)
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .distinct()
        return """
        (async function() {
            const id = ${JSONObject.quote(step.domId)}, nm = ${JSONObject.quote(step.name)},
                  css = ${JSONObject.quote(step.cssSelector)}, xpath = ${JSONObject.quote(step.xpath)};
            const hints = ${JSONArray(hints)}.map(function(s) { return String(s).toLowerCase(); });
            const wanted = ${JSONObject.quote(value)};
            const humanize = ${if (humanize) "true" else "false"};
            function isBoxy(el) { return !!el && el.tagName === 'INPUT' && (el.type === 'checkbox' || el.type === 'radio'); }
            function visible(el) {
                if (!el || !el.isConnected) return false;
                if (isBoxy(el)) return true; // styled checkboxes are often 0x0 / opacity 0
                try {
                    const st = getComputedStyle(el);
                    if (st.display === 'none' || st.visibility === 'hidden') return false;
                    const r = el.getBoundingClientRect();
                    return r.width > 0 && r.height > 0;
                } catch (_) { return false; }
            }
            function realVisible(el) {
                try { const r = el.getBoundingClientRect(); return r.width > 4 && r.height > 4 && getComputedStyle(el).opacity !== '0'; }
                catch (_) { return false; }
            }
            function bySelectors() {
                const tries = [
                    function() { return id ? document.getElementById(id) : null; },
                    function() { return css ? document.querySelector(css) : null; },
                    function() { return xpath ? document.evaluate(xpath, document, null, 9, null).singleNodeValue : null; },
                    function() { return nm ? document.getElementsByName(nm)[0] : null; }
                ];
                for (const t of tries) { try { const e = t(); if (e && visible(e)) return e; } catch (_) {} }
                return null;
            }
            function byHints() {
                const els = Array.from(document.querySelectorAll('input:not([type=hidden]),textarea,select')).filter(visible);
                for (const h of hints) {
                    const e = els.find(function(x) {
                        const c = ((x.name || '') + ' ' + (x.id || '') + ' ' + (x.placeholder || '') + ' ' +
                                   (x.getAttribute('aria-label') || '') + ' ' + (x.autocomplete || '')).toLowerCase();
                        return c.includes(h);
                    });
                    if (e) return e;
                }
                return null;
            }
            const start = Date.now();
            let el = null;
            while (Date.now() - start < 10000) {
                el = bySelectors() || byHints();
                if (el) break;
                await new Promise(function(r) { setTimeout(r, 300); });
            }
            if (!el) return JSON.stringify({ok: false, err: 'field not found (id/css/xpath/name/hints=' + JSON.stringify(hints) + ')'});
            try {
                el.scrollIntoView({block: 'center'});
                try { el.focus(); } catch (_) {}

                if (isBoxy(el)) {
                    const want = !/^(false|0|no)${'$'}/i.test(wanted.trim());
                    if (el.checked !== want) {
                        const lab = (el.labels && el.labels[0]) || el.closest('label');
                        (lab && !realVisible(el) ? lab : el).click();
                        if (el.checked !== want) { el.checked = want; el.dispatchEvent(new Event('change', {bubbles: true})); }
                    }
                    return JSON.stringify({ok: el.checked === want, err: 'checkbox state did not change'});
                }

                if (!wanted) return JSON.stringify({ok: false, err: 'no data value available for this field'});

                if (el.tagName === 'SELECT') {
                    const w = wanted.toLowerCase().trim();
                    const opt = Array.from(el.options).find(function(o) { return o.value.toLowerCase() === w || o.text.toLowerCase().trim() === w; })
                             || Array.from(el.options).find(function(o) { return o.text.toLowerCase().includes(w); });
                    if (!opt) return JSON.stringify({ok: false, err: 'no option matches the value'});
                    el.value = opt.value;
                    el.dispatchEvent(new Event('input', {bubbles: true}));
                    el.dispatchEvent(new Event('change', {bubbles: true}));
                    return JSON.stringify({ok: true});
                }

                const proto = el.tagName === 'TEXTAREA' ? window.HTMLTextAreaElement.prototype : window.HTMLInputElement.prototype;
                const setter = (Object.getOwnPropertyDescriptor(proto, 'value') || {}).set;
                function setVal(v) {
                    if (setter) setter.call(el, v); else el.value = v;
                    if (el._valueTracker) el._valueTracker.setValue('');
                }
                setVal('');
                el.dispatchEvent(new Event('input', {bubbles: true}));
                if (humanize) {
                    let cur = '';
                    for (let i = 0; i < wanted.length; i++) {
                        cur += wanted[i];
                        setVal(cur);
                        el.dispatchEvent(new Event('input', {bubbles: true}));
                        await new Promise(function(r) { setTimeout(r, 50 + Math.random() * 100); });
                    }
                } else {
                    setVal(wanted);
                    if (el.value !== wanted) { try { el.select(); document.execCommand('insertText', false, wanted); } catch (_) {} }
                    el.dispatchEvent(new Event('input', {bubbles: true, composed: true}));
                }
                el.dispatchEvent(new Event('change', {bubbles: true}));
                el.dispatchEvent(new Event('blur', {bubbles: true}));
                return JSON.stringify({ok: el.value === wanted, err: 'value did not stick (site reset the field)'});
            } catch (e) { return JSON.stringify({ok: false, err: 'fill error: ' + e.message}); }
        })()
        """.trimIndent()
    }

    // ---------------------------------------------------------------------------------------------
    // SCRIPTED MODE: replays script.steps in order
    // ---------------------------------------------------------------------------------------------
    private suspend fun runScriptExecution(
        script: AutomationScript,
        config: AutomationConfig,
        navigateFirstCycle: Boolean = true,
    ) {
        val webView = currentWebView?.get() ?: run {
            Log.e(TAG, "runScriptExecution: webView is null")
            return
        }
        var completed = hudCompleted
        var isFirst = true
        Log.d(TAG, "runScriptExecution: script=${script.name}, steps=${script.steps.size}, target=${config.targetRepeatCount}, navigateFirstCycle=$navigateFirstCycle")

        while (completed < config.targetRepeatCount && scope.isActive) {
            // FIXED: acquireMergedProfile (was acquireNextProfile) — pulls one value from EVERY selected group.
            val activeProfile = VaultRepository.acquireMergedProfile(config.groupNames, config.mode)

            if (activeProfile == null) {
                val missing = config.groupNames.filter { VaultRepository.getGroup(it) == null }
                if (missing.isNotEmpty()) {
                    updateStatus("Group(s) ${missing.joinToString(", ")} deleted. Please edit the script.")
                } else {
                    updateStatus("All selected recurring groups are empty or used.")
                }
                _dataExhaustedState.postValue(true)
                updateState(AutomationState.WAITING_DATA_EXHAUSTED)
                return
            }

            // FIXED: acquireMergedProfile with a single-element list for the default group.
            val defaultProfile = if (
                !config.defaultGroupName.isNullOrBlank() &&
                !config.groupNames.contains(config.defaultGroupName)
            ) {
                VaultRepository.acquireMergedProfile(listOf(config.defaultGroupName), "default") ?: activeProfile
            } else {
                activeProfile
            }

            updateState(AutomationState.NAVIGATING)
            updateStatus("Loading ${script.url}...")
            ensureOnUrl(webView, script.url, forceReload = !isFirst || navigateFirstCycle)
            isFirst = false
            waitForPageReady(webView)

            var lastError = ""

            for ((stepIdx, step) in script.steps.withIndex()) {
                if (!scope.isActive) {
                    Log.d(TAG, "runScriptExecution: scope cancelled at step $stepIdx")
                    return
                }
                Log.d(TAG, "runScriptExecution: step ${stepIdx + 1}/${script.steps.size} kind=${step.kind} label=${step.displayLabel}")
                lastError = ""

                val stepOk: Boolean = when (step.kind) {
                    StepKind.Field -> {
                        updateState(AutomationState.FILLING)
                        updateStatus("Filling ${step.displayLabel}...")
                        val valToFill = resolveFieldValue(step, activeProfile, defaultProfile)
                        if (config.humanize) delay(Random.nextLong(500, 3000))

                        val raw = evaluateJsSuspend(webView, "return await ${buildFillJs(step, valToFill, config.humanize)};")
                        val res = safeJsonObject(raw)
                        val ok = res.optBoolean("ok", false)
                        if (!ok) {
                            lastError = res.optString("err").ifBlank { "no result from page (timeout)" }
                            Log.e(TAG, "Field step failed: $lastError")
                        }
                        ok
                    }

                    StepKind.Wait -> {
                        val safeWait = step.waitMs.coerceIn(0L, MAX_WAIT_MS)
                        updateStatus("Waiting ${safeWait / 1000}s...")
                        delay(safeWait)
                        true
                    }

                    StepKind.DeleteCookies -> {
                        updateState(AutomationState.CLEARING_DATA)
                        updateStatus("Deleting site data...")
                        if (onShowFireDialog != null) {
                            try {
                                suspendCancellableCoroutine<Unit> { cont ->
                                    fireDialogContinuation = cont
                                    onShowFireDialog?.invoke(step.fieldType)
                                }
                            } catch (e: Exception) { Log.e(TAG, "Fire dialog failed: ${e.message}") }
                        }
                        try {
                            if (step.fieldType == "delete_all") clearAllData(webView)
                            else clearTabData(webView)
                        } catch (e: Exception) { Log.e(TAG, "Clear data failed: ${e.message}") }
                        delay(500)
                        true
                    }

                    StepKind.CaptchaWait -> {
                        _captchaState.postValue(true)
                        updateState(AutomationState.WAITING_CAPTCHA_HUMAN)
                        return
                    }

                    StepKind.ClickNext -> {
                        updateState(AutomationState.CLICKING_NEXT)
                        updateStatus("Clicking ${step.displayLabel}...")
                        if (config.humanize) delay(Random.nextLong(500, 3000))

                        val raw = evaluateJsSuspend(webView, "return await ${buildClickJs(step)};")
                        val res = safeJsonObject(raw)
                        val ok = res.optBoolean("ok", false)
                        if (ok) {
                            Log.d(TAG, "ClickNext '${step.displayLabel}' succeeded via ${res.optString("via").ifBlank { "unknown" }}")
                            updateStatus("Waiting for page...")
                            waitAfterClick(webView)
                        } else {
                            lastError = res.optString("err").ifBlank { "no result from page (timeout)" }
                            Log.e(TAG, "ClickNext '${step.displayLabel}' failed: $lastError")
                        }
                        ok
                    }
                }

                if (!stepOk) {
                    val msg = ("Failed at step ${stepIdx + 1}: ${step.displayLabel}" +
                        if (lastError.isNotBlank()) " — $lastError" else "").take(160)
                    Log.e(TAG, "runScriptExecution: $msg")
                    updateStatus(msg)
                    updateState(AutomationState.ERROR)
                    return
                }
            }

            // FIXED: markProfileUsed takes the new ActiveAutomationProfile shape (Map<String, List<Int>>).
            VaultRepository.markProfileUsed(activeProfile)
            completed++
            hudCompleted = completed
            updateProgress(completed, config.targetRepeatCount)
            Log.d(TAG, "runScriptExecution: cycle complete $completed / ${config.targetRepeatCount}")
            delay(500)
        }

        updateState(AutomationState.COMPLETED)
        updateStatus("Finished $completed / ${config.targetRepeatCount}")
        webView.evaluateJavascript("try { window.__automation_active = false; } catch(_) {}", null)
        onAutomationFinished?.invoke(completed)
    }

    /**
     * DYNAMIC MODE: original scan-and-fill behaviour (CreateAutomationScreen path, config.script == null).
     */
    private suspend fun runAutomationLoop(config: AutomationConfig, navigateFirstCycle: Boolean) {
        val webView = currentWebView?.get() ?: return
        val targetUri = if (config.targetUrl.startsWith("http")) config.targetUrl else "https://${config.targetUrl}"
        var isFirst = true
        while (hudCompleted < config.targetRepeatCount && scope.isActive) {
            // FIXED: acquireMergedProfile replaces acquireNextProfile.
            val activeProfile = VaultRepository.acquireMergedProfile(config.groupNames, config.mode)
            if (activeProfile == null) {
                _dataExhaustedState.postValue(true)
                updateState(AutomationState.WAITING_DATA_EXHAUSTED)
                return
            }
            currentProfile = activeProfile

            val defaultProfile = if (
                !config.defaultGroupName.isNullOrBlank() &&
                !config.groupNames.contains(config.defaultGroupName)
            ) {
                VaultRepository.acquireMergedProfile(listOf(config.defaultGroupName), "default") ?: activeProfile
            } else {
                activeProfile
            }

            if (config.clearTabBetweenRuns && hudCompleted > 0) {
                updateState(AutomationState.CLEARING_DATA)
                updateStatus("Clearing tab data...")
                clearTabData(webView); delay(500)
            }
            updateState(AutomationState.NAVIGATING)
            if (!isFirst || navigateFirstCycle) {
                updateStatus("Loading ${config.targetUrl}...")
                withContext(Dispatchers.Main) { webView.loadUrl(targetUri) }
                delay(500)
            }
            isFirst = false
            waitForPageReady(webView)
            injectScanner(); delay(800)

            var stepSuccess = false
            var counter = 0
            while (counter < 5 && scope.isActive) {
                counter++
                updateState(AutomationState.SCANNING)
                updateStatus("Scanning form fields (Step $counter)...")
                val scanObj = try { JSONObject(evaluateJsSuspend(webView, "return JSON.stringify(await __automationScan());")) } catch (_: Exception) { JSONObject() }
                val fieldsArr = scanObj.optJSONArray("fields") ?: JSONArray()
                if (scanObj.optBoolean("captchaDetected", false) && config.pauseOnCaptcha) {
                    _captchaState.postValue(true); updateState(AutomationState.WAITING_CAPTCHA_HUMAN); return
                }
                currentActionRef = scanObj.optString("actionButtonRef").takeIf { it.isNotBlank() }
                val fillArray = JSONArray()
                for (i in 0 until fieldsArr.length()) {
                    val f = fieldsArr.getJSONObject(i)
                    val looksLike = f.optString("looksLike")
                    val matched = when {
                        looksLike == "termsCheckbox" -> "true"
                        else -> {
                            val isDynamic = DYNAMIC_FIELDS.contains(looksLike.lowercase())
                            val primary = if (isDynamic) activeProfile else defaultProfile
                            val fallback = if (isDynamic) defaultProfile else activeProfile
                            val v1 = primary?.resolveForField(looksLike, f.optString("name"), f.optString("tag"))
                            if (!v1.isNullOrBlank()) v1 else fallback?.resolveForField(looksLike, f.optString("name"), f.optString("tag"))
                        }
                    }
                    if (!matched.isNullOrBlank()) {
                        fillArray.put(JSONObject().apply { put("ref", f.optString("ref")); put("index", f.optInt("index", i)); put("value", matched) })
                    }
                }
                if (fillArray.length() == 0) {
                    if (counter == 1) {
                        delay(1200); injectScanner()
                        val retryObj = try { JSONObject(evaluateJsSuspend(webView, "return JSON.stringify(await __automationScan());")) } catch (_: Exception) { JSONObject() }
                        if ((retryObj.optJSONArray("fields")?.length() ?: 0) > 0) { counter--; continue }
                    }
                    stepSuccess = counter > 1; break
                }
                updateState(AutomationState.FILLING)
                updateStatus("Filling ${fillArray.length()} field(s) (Step $counter)...")
                val fillOpts = JSONObject().apply {
                    put("typingDelayMs", config.typingDelayMs)
                    put("fieldDelayMs", config.fieldDelayMs)
                    put("humanize", config.humanize)
                }
                evaluateJsSuspend(webView, "return JSON.stringify(await __automationFill(${JSONObject.quote(fillArray.toString())}, ${JSONObject.quote(fillOpts.toString())}));")
                delay(500)
                updateState(AutomationState.CLICKING_NEXT)
                val btnName = scanObj.optString("actionButtonName").ifBlank { "Next / Submit" }
                updateStatus("Clicking '$btnName' & checking next step...")
                val clickOpts = JSONObject().apply {
                    put("actionRef", currentActionRef ?: "")
                    put("settleTimeoutMs", config.settleTimeoutMs)
                    put("pauseOnCaptcha", config.pauseOnCaptcha)
                }
                val settle = try { JSONObject(evaluateJsSuspend(webView, "return JSON.stringify(await __automationClickNextAndSettle(${JSONObject.quote(clickOpts.toString())}));")) } catch (_: Exception) { JSONObject() }
                if (settle.optString("verdict") == "captcha_blocked" && config.pauseOnCaptcha) {
                    _captchaState.postValue(true); updateState(AutomationState.WAITING_CAPTCHA_HUMAN); return
                }
                delay(600); waitForPageReady(webView); injectScanner(); delay(500)
                val postNav = try { JSONObject(evaluateJsSuspend(webView, "return JSON.stringify(await __automationScan());")) } catch (_: Exception) { JSONObject() }
                val postFields = postNav.optJSONArray("fields") ?: JSONArray()
                var unfilled = 0
                for (k in 0 until postFields.length()) {
                    val f = postFields.getJSONObject(k)
                    if (f.optString("looksLike", "") != "termsCheckbox" && f.optString("currentValue", "").isBlank()) unfilled++
                }
                if (unfilled > 0 && settle.optString("verdict") != "validation_error" && settle.optString("verdict") != "no_button_found") {
                    updateStatus("Moved to Step ${counter + 1} — continuing...")
                    delay(400)
                } else { stepSuccess = true; break }
            }
            if (stepSuccess) {
                // FIXED: markProfileUsed takes the new ActiveAutomationProfile shape.
                VaultRepository.markProfileUsed(activeProfile)
                updateProgress(hudCompleted + 1, config.targetRepeatCount)
                updateStatus("Completed cycle ${hudCompleted} of ${config.targetRepeatCount}")
            } else {
                updateStatus("No matching fields found.")
                break
            }
            delay(1000)
        }
        updateState(AutomationState.COMPLETED)
        updateStatus("Finished $hudCompleted / ${config.targetRepeatCount} automation(s)!")
        webView.evaluateJavascript("try { window.__automation_active = false; } catch(_) {}", null)
        onAutomationFinished?.invoke(hudCompleted)
    }

    /**
     * Only QUEUES the config. It is started by attachWebView() when the browser tab attaches, or by the
     * fallback in launchScriptFromList(). Starting here ran steps on a stale WebView while BrowserActivity
     * was still navigating.
     */
    fun queueAutomationInBrowserTab(config: AutomationConfig) {
        currentConfig = config
        pendingBrowserConfig = config
        hudGroupName = config.groupNames.joinToString(", ")
        updateProgress(0, config.targetRepeatCount)
        updateState(AutomationState.NAVIGATING)
        updateStatus("Opening ${config.targetUrl} in browser tab...")
    }

    fun startAutomation(config: AutomationConfig, navigateFirstCycle: Boolean = true) {
        Log.d(TAG, "startAutomation: script=${config.script?.name}, navigateFirstCycle=$navigateFirstCycle")
        currentConfig = config
        hudGroupName = config.groupNames.joinToString(", ")
        updateProgress(0, config.targetRepeatCount)
        updateState(AutomationState.NAVIGATING)
        hudMode = HudMode.Running
        hudVisible = true
        _captchaState.postValue(false)
        _dataExhaustedState.postValue(false)
        val webView = currentWebView?.get()
        if (webView == null) {
            pendingBrowserConfig = config
            updateStatus("Waiting for browser tab...")
            return
        }
        mountHudOverlayIfNeeded(webView)
        automationJob?.cancel()
        webView.evaluateJavascript("try { window.__automation_active = true; } catch(_) {}", null)
        automationJob = scope.launch {
            val scriptToRun = config.script
            if (scriptToRun != null) {
                runScriptExecution(scriptToRun, config, navigateFirstCycle)
            } else {
                runAutomationLoop(config, navigateFirstCycle)
            }
        }
    }

    private fun clearTabData(webView: WebView) {
        try {
            val url = webView.url.orEmpty()
            if (url.isNotBlank()) {
                val cm = CookieManager.getInstance()
                cm.getCookie(url)?.split(";")?.forEach { cookie ->
                    val name = cookie.substringBefore("=").trim()
                    if (name.isNotBlank()) cm.setCookie(url, "$name=; Expires=Thu, 01 Jan 1970 00:00:00 GMT; Path=/")
                }
                cm.flush()
            }
            webView.clearCache(true); webView.clearFormData()
            webView.evaluateJavascript("""
                (function() {
                    try { localStorage.clear(); } catch(_) {}
                    try { sessionStorage.clear(); } catch(_) {}
                    try { if (window.indexedDB && window.indexedDB.databases) window.indexedDB.databases().then(dbs => dbs.forEach(db => window.indexedDB.deleteDatabase(db.name))); } catch(_) {}
                    try { if (navigator.serviceWorker) navigator.serviceWorker.getRegistrations().then(regs => regs.forEach(r => r.unregister())); } catch(_) {}
                    try { if (window.caches) caches.keys().then(keys => keys.forEach(k => caches.delete(k))); } catch(_) {}
                })();
            """.trimIndent(), null)
        } catch (e: Exception) { Log.e(TAG, "Clear tab err: ${e.message}") }
    }

    private fun clearAllData(webView: WebView) {
        try {
            webView.clearCache(true); webView.clearHistory(); webView.clearFormData()
            CookieManager.getInstance().removeAllCookies(null)
            CookieManager.getInstance().flush()
            WebStorage.getInstance().deleteAllData()
            webView.evaluateJavascript("""
                (function() {
                    try { localStorage.clear(); } catch(_) {}
                    try { sessionStorage.clear(); } catch(_) {}
                })();
            """.trimIndent(), null)
        } catch (e: Exception) { Log.e(TAG, "Clear all err: ${e.message}") }
    }

    fun resolveFireDialogResult() {
        fireDialogContinuation?.resumeWith(Result.success(Unit))
        fireDialogContinuation = null
    }

    // ---------------------------------------------------------------------------------------------
    // NAVIGATION / WAIT HELPERS
    // ---------------------------------------------------------------------------------------------

    private fun normalizeUrl(u: String): String =
        u.trim().substringBefore('#')
            .removePrefix("https://").removePrefix("http://").removePrefix("www.")
            .trimEnd('/').lowercase()

    /**
     * Makes sure the WebView is on [targetUrl]. When not forcing, it first gives the browser up to 4s to get
     * there by itself (BrowserActivity is also navigating) and only then loads it.
     */
    private suspend fun ensureOnUrl(webView: WebView, targetUrl: String, forceReload: Boolean) {
        val target = normalizeUrl(targetUrl)
        if (!forceReload) {
            var waited = 0L
            while (waited < 4000L) {
                val cur = normalizeUrl(webView.url.orEmpty())
                if (cur == target || cur.startsWith(target)) {
                    Log.d(TAG, "ensureOnUrl: already on target")
                    return
                }
                delay(250)
                waited += 250
            }
        }
        Log.d(TAG, "ensureOnUrl: loading $targetUrl")
        webView.loadUrl(if (targetUrl.startsWith("http")) targetUrl else "https://$targetUrl")
        // let progress/readyState reset so the OLD page is not mistaken for "ready"
        delay(500)
    }

    /**
     * After a click: if the click started a real navigation (the page marker disappears) wait for the new page;
     * otherwise assume an in-page update and just settle briefly.
     */
    private suspend fun waitAfterClick(webView: WebView) {
        var waited = 0L
        while (waited < 2500L) {
            delay(250)
            waited += 250
            val s = evaluateJsSuspend(webView, "return window.__ddgNavMark ? 'same' : 'new';", 3000L)
            if (s == "new") {
                waitForPageReady(webView)
                return
            }
        }
        delay(300)
    }

    private suspend fun waitForPageReady(webView: WebView) {
        var waited = 0L
        var lastLog = -1L
        while (waited < PAGE_READY_TIMEOUT_MS) {
            val progress = withContext(Dispatchers.Main) { webView.progress }
            val readyState = try {
                evaluateJsSuspend(webView, "return document.readyState;", 2000L).trim('"')
            } catch (_: Exception) { "" }

            if (progress >= 100 || readyState == "complete" || readyState == "interactive") {
                Log.d(TAG, "Page ready: progress=$progress readyState=$readyState waited=${waited}ms")
                delay(PAGE_READY_POST_MS)
                return
            }
            if (waited - lastLog >= 2000L) {
                Log.d(TAG, "Page loading: progress=$progress readyState=$readyState waited=${waited}ms")
                lastLog = waited
            }
            delay(200)
            waited += 200
        }
        Log.w(TAG, "Page ready timeout after ${PAGE_READY_TIMEOUT_MS}ms; proceeding anyway")
        delay(PAGE_READY_POST_MS)
    }

    /**
     * Runs [body] (JavaScript statements that `return` a value) inside an async function and WAITS until the
     * script has really finished, by having the page call AutomationAsync.resolve(). Returns the string the
     * script returned, or "{}" on timeout.
     */
    private suspend fun evaluateJsSuspend(webView: WebView, body: String, timeoutMs: Long = 15_000L): String {
        val callId = UUID.randomUUID().toString()
        val deferred = CompletableDeferred<String>()
        asyncBridge.pending[callId] = deferred
        val wrapped = """
            (function() {
                var __id = ${JSONObject.quote(callId)};
                function __send(v) {
                    try {
                        AutomationAsync.resolve(__id, (typeof v === 'string') ? v : JSON.stringify(v === undefined ? null : v));
                    } catch (e) {}
                }
                try {
                    Promise.resolve((async function() { ${body.trim()} })())
                        .then(__send, function(e) { __send(JSON.stringify({ok:false, err:'js error: ' + (e && e.message ? e.message : e)})); });
                } catch (e) { __send(JSON.stringify({ok:false, err:'js error: ' + e.message})); }
            })();
        """.trimIndent()
        withContext(Dispatchers.Main) { webView.evaluateJavascript(wrapped, null) }
        val result = withTimeoutOrNull(timeoutMs) { deferred.await() }
        asyncBridge.pending.remove(callId)
        if (result == null) {
            Log.w(TAG, "evaluateJsSuspend timed out after ${timeoutMs}ms (page navigating, or AutomationAsync bridge not attached before the page loaded?)")
        }
        return result ?: "{}"
    }

    private fun safeJsonObject(raw: String): JSONObject = try { JSONObject(raw) } catch (_: Exception) { JSONObject() }

    fun setActiveTab(tabId: String) {
        if (activeTabId == tabId) return
        if (hudMode == HudMode.Menu) { hudMode = HudMode.Hidden; hudVisible = false }
        if (hudMode == HudMode.Recording && activeTabId != null) {
            cancelRecording()
            Toast.makeText(appContext, "Recording cancelled", Toast.LENGTH_SHORT).show()
        }
        activeTabId = tabId
        _detectedFieldCount.postValue(0)
    }

    fun clearActiveTab(tabId: String) {
        if (activeTabId != tabId) return
        activeTabId = null
        _detectedFieldCount.postValue(0)
    }

    fun toggleHudPosition() {
        hudPositionTop = !hudPositionTop
        val webView = currentWebView?.get() ?: return
        val parent = webView.parent as? ViewGroup ?: return
        val composeView = parent.findViewWithTag<ComposeView>(HUD_VIEW_TAG) ?: return
        val params = composeView.layoutParams as? FrameLayout.LayoutParams ?: return
        params.gravity = if (hudPositionTop) Gravity.TOP else Gravity.BOTTOM
        composeView.layoutParams = params
        composeView.requestLayout()
    }

    fun onOmnibarBotIconClicked() {
        when (hudMode) {
            HudMode.Recording -> return
            HudMode.Running -> { currentWebView?.get()?.let { mountHudOverlayIfNeeded(it); hudVisible = true } }
            HudMode.Menu -> { hudMode = HudMode.Hidden; hudVisible = false }
            HudMode.Hidden -> { currentWebView?.get()?.let { mountHudOverlayIfNeeded(it); hudMode = HudMode.Menu; hudVisible = true } }
            else -> { hudMode = HudMode.Hidden; hudVisible = false }
        }
    }

    fun closeHud() {
        if (hudMode == HudMode.Running) return
        hudMode = HudMode.Hidden
        hudVisible = false
    }

    // ---------------------------------------------------------------------------------------------
    // RECORDING
    // ---------------------------------------------------------------------------------------------

    /**
     * Registers the recorder as a document-start script (runs automatically on EVERY new page and iframe,
     * so links/buttons/fields are captured without a manual refresh) and also injects it into the current page.
     * The recorder JS must be idempotent (safe to run more than once on the same page).
     */
    private fun installRecorder(webView: WebView) {
        val js = loadRecorder() ?: run {
            Log.e(TAG, "automation_recorder.js missing from assets")
            return
        }
        removeRecorderHandler()
        try {
            if (WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) {
                recorderScriptHandler = WebViewCompat.addDocumentStartJavaScript(webView, js, setOf("*"))
                Log.d(TAG, "Recorder registered as document-start script")
            } else {
                Log.w(TAG, "DOCUMENT_START_SCRIPT not supported; relying on onPageFinished re-injection")
            }
        } catch (e: Exception) {
            Log.e(TAG, "document-start registration failed: ${e.message}")
        }
        webView.evaluateJavascript(js) { Log.d(TAG, "Recorder injected into current page") }
    }

    private fun removeRecorderHandler() {
        try { recorderScriptHandler?.remove() } catch (_: Exception) {}
        recorderScriptHandler = null
    }

    fun startRecording() {
        if (hudMode == HudMode.Recording) {
            currentWebView?.get()?.let { installRecorder(it) }
            hudRecordingPaused = false
            return
        }
        val webView = currentWebView?.get() ?: return
        webView.evaluateJavascript("try { window.__automationRecorderStop(); } catch(_) {}", null)
        webView.evaluateJavascript("try { window.__automation_active = false; } catch(_) {}", null)
        val url = webView.url.orEmpty()
        hudRecordingHost = AutomationScriptRepository.hostOfUrl(url)
        hudRecordingUrl = url
        hudRecordedSteps = emptyList()
        hudRecordingPaused = false
        hudRecordingPhase = RecordingPhase.Capturing
        hudMode = HudMode.Recording
        hudVisible = true
        Log.d(TAG, "startRecording: url=$url")
        installRecorder(webView)
        // safety net for pages that were still loading
        webView.postDelayed({
            if (hudMode == HudMode.Recording && hudRecordingPhase == RecordingPhase.Capturing) {
                loadRecorder()?.let { webView.evaluateJavascript(it) { Log.d(TAG, "Recorder injected (delayed)") } }
            }
        }, 600)
    }

    fun pauseRecording() {
        if (hudMode != HudMode.Recording) return
        hudRecordingPaused = true
        currentWebView?.get()?.evaluateJavascript("try { window.__automationRecorderPause(); } catch(_) {}", null)
    }

    fun resumeRecording() {
        if (hudMode != HudMode.Recording) return
        hudRecordingPaused = false
        currentWebView?.get()?.evaluateJavascript("try { window.__automationRecorderResume(); } catch(_) {}", null)
    }

    private fun sanitizeString(s: String, maxLen: Int): String = try {
        s.filter { c -> !c.isISOControl() || c == '\n' || c == '\t' }.take(maxLen)
    } catch (_: Exception) { "" }

    fun onRecorderElementCaptured(json: String, tabId: String?) {
        // The JS bridge calls this from a background thread; compose state must be updated on the main thread,
        // otherwise two quick events can overwrite each other and a step is lost.
        if (Looper.myLooper() != Looper.getMainLooper()) {
            mainHandler.post { onRecorderElementCaptured(json, tabId) }
            return
        }
        Log.d(TAG, "onRecorderElementCaptured mode=$hudMode paused=$hudRecordingPaused json=${json.take(200)}")
        if (hudMode != HudMode.Recording || hudRecordingPaused) return
        try {
            val obj = JSONObject(json)
            val kindRaw = obj.optString("kind", "Field")
            val stepKind = when (kindRaw.lowercase()) {
                "clicknext", "click", "button", "link", "submit", "anchor" -> StepKind.ClickNext
                "wait" -> StepKind.Wait
                "captchawait" -> StepKind.CaptchaWait
                else -> StepKind.Field
            }
            val cssSelector = sanitizeString(obj.optString("cssSelector", ""), 500)
            val xpathVal = sanitizeString(obj.optString("xpath", ""), 500)
            val domId = sanitizeString(obj.optString("domId", ""), 100)
            val name = sanitizeString(obj.optString("name", ""), 100)
            if (cssSelector.isBlank() && xpathVal.isBlank() && domId.isBlank() && name.isBlank()) {
                Log.w(TAG, "Rejecting step with no identifying selector"); return
            }

            // Drop duplicates caused by double events (pointerdown + click, repeated injection, ...)
            val last = hudRecordedSteps.lastOrNull()
            val now = System.currentTimeMillis()
            if (last != null && last.kind == stepKind && last.cssSelector == cssSelector &&
                last.xpath == xpathVal && now - lastRecordedAt < 600
            ) {
                Log.d(TAG, "Ignoring duplicate recorder event"); return
            }
            lastRecordedAt = now

            val looksLike = sanitizeString(obj.optString("looksLike", ""), 50)
            val canonicalType = if (stepKind == StepKind.Field) FieldTypeMapper.normalizeLooksLike(looksLike) else ""
            val rawLabel = if (stepKind == StepKind.ClickNext) obj.optString("buttonLabel", "Next button")
                           else FieldTypeMapper.fieldRowLabel(canonicalType, obj.optString("displayLabel", ""))
            val displayLabel = sanitizeString(rawLabel, 100)
            val step = RecordedStep(
                sequenceIndex = hudRecordedSteps.size + 1,
                kind = stepKind,
                fieldType = canonicalType,
                displayLabel = displayLabel,
                tag = sanitizeString(obj.optString("tag"), 30),
                domType = sanitizeString(obj.optString("domType"), 30),
                name = name,
                domId = domId,
                placeholder = sanitizeString(obj.optString("placeholder"), 100),
                ariaLabel = sanitizeString(obj.optString("ariaLabel"), 100),
                cssSelector = cssSelector,
                xpath = xpathVal,
                index = obj.optInt("index", -1),
                buttonLabel = sanitizeString(obj.optString("buttonLabel"), 100),
                verified = stepKind != StepKind.Field || canonicalType.isNotBlank(),
            )
            hudRecordedSteps = hudRecordedSteps + step
            Log.d(TAG, "Recorded step #${step.sequenceIndex} kind=$stepKind label=$displayLabel")
        } catch (e: Exception) {
            Log.e(TAG, "onRecorderElementCaptured parse fail: ${e.message}")
        }
    }

    fun removeRecordedStep(id: String) {
        if (hudMode != HudMode.Recording) return
        hudRecordedSteps = hudRecordedSteps.filterNot { it.id == id }.mapIndexed { i, s -> s.copy(sequenceIndex = i + 1) }
    }

    fun assignFieldTypeToStep(id: String, canonicalType: String) {
        if (hudMode != HudMode.Recording) return
        hudRecordedSteps = hudRecordedSteps.map { if (it.id == id) it.copy(fieldType = canonicalType, displayLabel = FieldTypeMapper.fieldRowLabel(canonicalType, it.displayLabel), verified = true) else it }
    }

    fun stopRecordingAndPromptSave() {
        if (hudRecordedSteps.isEmpty()) { cancelRecording(); return }
        currentWebView?.get()?.evaluateJavascript("try { window.__automationRecorderStop(); } catch(_) {}", null)
        removeRecorderHandler()
        hudRecordingPhase = RecordingPhase.Saving
    }

    fun cancelSaveRecording() {
        hudRecordingPhase = RecordingPhase.Capturing
        currentWebView?.get()?.let { wv -> installRecorder(wv) }
    }

    fun saveRecording(name: String) {
        if (hudRecordingPhase != RecordingPhase.Saving) return
        val ctx = appContext ?: return
        if (hudRecordingHost.isBlank() || hudRecordedSteps.isEmpty()) return
        currentWebView?.get()?.evaluateJavascript("try { window.__automationRecorderStop(); } catch(_) {}", null)
        removeRecorderHandler()
        val finalName = name.trim().ifBlank { "${hudRecordingHost} Script" }
        val script = AutomationScript(
            id = UUID.randomUUID().toString(), url = hudRecordingUrl, host = hudRecordingHost,
            name = finalName, groupNames = emptyList(), humanize = false, createdAt = System.currentTimeMillis(),
            updatedAt = System.currentTimeMillis(), steps = hudRecordedSteps
        )
        AutomationScriptRepository.saveScript(script)
        Toast.makeText(ctx, "Script saved: $finalName", Toast.LENGTH_SHORT).show()
        hudRecordedSteps = emptyList()
        hudRecordingPaused = false
        hudRecordingPhase = RecordingPhase.Capturing
        hudMode = HudMode.Menu
        hudVisible = true
    }

    fun cancelRecording() {
        currentWebView?.get()?.evaluateJavascript("try { window.__automationRecorderStop(); } catch(_) {}", null)
        removeRecorderHandler()
        hudRecordedSteps = emptyList()
        hudRecordingPaused = false
        hudRecordingPhase = RecordingPhase.Capturing
        hudMode = HudMode.Hidden
        hudVisible = false
    }

    private fun loadRecorder(): String? = try {
        appContext?.assets?.open("automation/automation_recorder.js")?.bufferedReader()?.use { it.readText() }
    } catch (e: Exception) { null }

    /**
     * Retries up to 10x if the WebView has no parent yet, and brings the HUD to the front.
     */
    private fun mountHudOverlayIfNeeded(webView: WebView, retry: Int = 0) {
        val parent = webView.parent as? ViewGroup
        if (parent == null) {
            if (retry < 10) {
                Log.w(TAG, "mountHudOverlayIfNeeded: no parent yet (retry=$retry)")
                webView.postDelayed({ mountHudOverlayIfNeeded(webView, retry + 1) }, 100)
            } else {
                Log.e(TAG, "mountHudOverlayIfNeeded: giving up after $retry retries")
            }
            return
        }
        if (parent.findViewWithTag<ComposeView>(HUD_VIEW_TAG) != null) {
            Log.d(TAG, "mountHudOverlayIfNeeded: HUD already mounted")
            return
        }
        Log.d(TAG, "mountHudOverlayIfNeeded: mounting in ${parent::class.java.simpleName}")

        val composeView = ComposeView(webView.context).apply {
            tag = HUD_VIEW_TAG
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnDetachedFromWindow)
            isClickable = false
            isFocusable = false
            setContent {
                AutomationThemeWrapper(isLightMode = resolveDuckDuckGoIsLight(context)) {
                    BrowserTabAutomationHud()
                }
            }
        }
        val params = if (parent is FrameLayout) {
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { gravity = if (hudPositionTop) Gravity.TOP else Gravity.BOTTOM }
        } else {
            ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }
        parent.addView(composeView, params)
        composeView.bringToFront()
        Log.d(TAG, "mountHudOverlayIfNeeded: HUD mounted and brought to front")
    }
}