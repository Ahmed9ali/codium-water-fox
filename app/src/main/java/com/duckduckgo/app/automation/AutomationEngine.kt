package com.duckduckgo.app.automation

import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Gravity
import android.view.ViewGroup
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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener
import java.lang.ref.WeakReference
import java.util.UUID

data class AutomationConfig(
    val targetUrl: String,
    val groupName: String,
    val mode: String,
    val targetRepeatCount: Int = 1,
)

enum class AutomationState {
    IDLE, NAVIGATING, SCANNING, FILLING, CLICKING_NEXT, WAITING_SETTLE,
    WAITING_CAPTCHA_HUMAN, WAITING_DATA_EXHAUSTED, CLEARING_DATA, COMPLETED, PAUSED, ERROR,
}

enum class RecordingPhase { Capturing, Saving }

object AutomationEngine {
    private const val TAG = "AutomationEngine"
    private const val HUD_VIEW_TAG = "ddg_automation_hud_overlay"

    private var currentWebView: WeakReference<WebView>? = null
    private var scannerJs: String? = null
    private var appContext: Context? = null
    private var automationJob: Job? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

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
    private var activeTabId: String? = null

    var hudState by mutableStateOf(AutomationState.IDLE); private set
    var hudCompleted by mutableIntStateOf(0); private set
    var hudTotal by mutableIntStateOf(1); private set
    var hudStatus by mutableStateOf(""); private set
    var hudGroupName by mutableStateOf(""); private set

    private val _recordedSteps = MutableLiveData<List<RecordedStep>>(emptyList())
    val recordedSteps: LiveData<List<RecordedStep>> get() = _recordedSteps
    var hudRecordedSteps by mutableStateOf<List<RecordedStep>>(emptyList()); private set
    var hudRecordingPhase by mutableStateOf(RecordingPhase.Capturing); private set
    var hudRecordingHost by mutableStateOf(""); private set
    var hudRecordingUrl by mutableStateOf(""); private set

    var hudAvailableScripts by mutableStateOf<List<AutomationScript>>(emptyList()); private set
    var hudSelectedScript by mutableStateOf<AutomationScript?>(null); private set
    var hudSelectedGroup by mutableStateOf(""); private set
    var hudRunRepeatCount by mutableIntStateOf(1); private set

    private var currentConfig: AutomationConfig? = null
    private var pendingBrowserConfig: AutomationConfig? = null
    private var currentFieldsJson: JSONArray = JSONArray()
    private var currentActionRef: String? = null
    private var currentProfile: ActiveAutomationProfile? = null

    var onAutomationFinished: ((Int) -> Unit)? = null
    var onDataExhaustedAction: ((String) -> Unit)? = null

    fun init(context: Context) {
        appContext = context.applicationContext
        VaultRepository.init(context)
        AutomationScriptRepository.init(context)
    }

    private fun updateState(newState: AutomationState) {
        _state.postValue(newState)
        hudState = newState
        if (newState != AutomationState.IDLE) hudVisible = true
    }

    private fun updateStatus(msg: String) {
        _statusMessage.postValue(msg)
        hudStatus = msg
    }

    private fun updateProgress(done: Int, total: Int) {
        _completedCount.postValue(done)
        _totalTargetCount.postValue(total)
        hudCompleted = done
        hudTotal = total
    }

    fun attachWebView(webView: WebView, tabId: String? = null) {
        currentWebView = WeakReference(webView)
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
        } catch (e: Exception) { Log.e(TAG, "Failed to add JS bridge") }
    }

    fun detachWebView() { currentWebView = null; _detectedFieldCount.postValue(0) }

    fun injectScanner() {
        val webView = currentWebView?.get() ?: return
        val js = scannerJs ?: loadScanner() ?: return
        webView.evaluateJavascript(js) { Log.d(TAG, "Scanner injected") }
    }

    private fun loadScanner(): String? = try {
        appContext?.assets?.open("automation/automation_scanner.js")?.bufferedReader()?.use { it.readText() }?.also { scannerJs = it }
    } catch (e: Exception) { null }

    fun onFieldsDetected(json: String, tabId: String?) {
        if (tabId == null || tabId != activeTabId) return
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
        hudSelectedGroup = VaultRepository.groups.value.firstOrNull()?.name ?: ""
        hudRunRepeatCount = 1
        hudMode = HudMode.RunSetup
    }

    fun updateRunGroup(group: String) { hudSelectedGroup = group }
    fun updateRunRepeat(count: Int) { hudRunRepeatCount = count.coerceAtLeast(1) }

    fun startSelectedScriptRun() {
        val script = hudSelectedScript ?: return
        if (hudSelectedGroup.isBlank()) {
            Toast.makeText(appContext, "Select a group first", Toast.LENGTH_SHORT).show()
            return
        }
        val config = AutomationConfig(script.url, hudSelectedGroup, "series", hudRunRepeatCount)
        hudMode = HudMode.Running
        hudVisible = true
        currentConfig = config
        hudGroupName = config.groupName
        updateProgress(0, config.targetRepeatCount)
        updateState(AutomationState.NAVIGATING)
        _captchaState.postValue(false)
        _dataExhaustedState.postValue(false)

        automationJob?.cancel()
        automationJob = scope.launch { runScriptExecution(script, config) }
    }

    private suspend fun runScriptExecution(script: AutomationScript, config: AutomationConfig) {
        val webView = currentWebView?.get() ?: return
        var completed = 0

        while (completed < config.targetRepeatCount && scope.isActive) {
            if (!VaultRepository.hasUnusedData(config.groupName)) {
                _dataExhaustedState.postValue(true)
                updateState(AutomationState.WAITING_DATA_EXHAUSTED)
                return
            }
            val profile = VaultRepository.acquireNextProfile(config.groupName, config.mode) ?: return

            updateState(AutomationState.NAVIGATING)
            updateStatus("Loading ${script.url}...")
            withContext(Dispatchers.Main) { webView.loadUrl(script.url) }
            waitForPageReady(webView)
            delay(500)

            for (step in script.steps) {
                if (!scope.isActive) break
                
                when (step.kind) {
                    StepKind.Field -> {
                        updateState(AutomationState.FILLING)
                        updateStatus("Filling ${step.displayLabel}...")
                        val valToFill = profile.resolveForField(step.fieldType, step.name, step.tag) ?: ""
                        
                        // Multi-strategy finder + Native Setter
                        val js = """
                            (function() {
                                try {
                                    let el = null;
                                    try { if ('${step.xpath}') el = document.evaluate('${step.xpath}', document, null, XPathResult.FIRST_ORDERED_NODE_TYPE, null).singleNodeValue; } catch(_){}
                                    if (!el && '${step.cssSelector}') { try { el = document.querySelector('${step.cssSelector}'); } catch(_){} }
                                    if (!el && '${step.name}') { el = document.querySelector('[name="${step.name}"]'); }
                                    if (!el && '${step.domId}') { el = document.getElementById('${step.domId}'); }
                                    if (!el && '${step.placeholder}') { el = document.querySelector('[placeholder="${step.placeholder}"]'); }
                                    if (!el && ${step.index} >= 0) {
                                        const all = Array.from(document.querySelectorAll('input:not([type="hidden"]), textarea, select'));
                                        el = all[${step.index}];
                                    }
                                    if (!el) return {ok: false, err: 'not found'};
                                    
                                    el.focus();
                                    // React/Vue Native Setter
                                    const nativeInput = Object.getOwnPropertyDescriptor(window.HTMLInputElement.prototype, "value").set;
                                    const nativeTextArea = Object.getOwnPropertyDescriptor(window.HTMLTextAreaElement.prototype, "value").set;
                                    if (el.tagName === 'TEXTAREA' && nativeTextArea) nativeTextArea.call(el, '${valToFill}');
                                    else if (nativeInput) nativeInput.call(el, '${valToFill}');
                                    else el.value = '${valToFill}';
                                    
                                    el.dispatchEvent(new Event('input', { bubbles: true }));
                                    el.dispatchEvent(new Event('change', { bubbles: true }));
                                    el.blur();
                                    return {ok: true};
                                } catch(e) { return {ok: false, err: e.message}; }
                            })();
                        """.trimIndent()
                        evaluateJsSuspend(webView, "return JSON.stringify(${js});")
                        delay(400)
                    }
                    StepKind.Wait -> { updateStatus("Waiting ${step.waitMs}ms..."); delay(step.waitMs) }
                    StepKind.CaptchaWait -> {
                        _captchaState.postValue(true)
                        updateState(AutomationState.WAITING_CAPTCHA_HUMAN)
                        return
                    }
                    StepKind.ClickNext -> {
                        updateState(AutomationState.CLICKING_NEXT)
                        updateStatus("Clicking ${step.displayLabel}...")
                        val js = """
                            (function() {
                                try {
                                    let el = null;
                                    try { if ('${step.xpath}') el = document.evaluate('${step.xpath}', document, null, XPathResult.FIRST_ORDERED_NODE_TYPE, null).singleNodeValue; } catch(_){}
                                    if (!el && '${step.cssSelector}') { try { el = document.querySelector('${step.cssSelector}'); } catch(_){} }
                                    if (el) { el.click(); return {ok: true}; }
                                    return {ok: false, err: 'not found'};
                                } catch(e) { return {ok: false, err: e.message}; }
                            })();
                        """.trimIndent()
                        evaluateJsSuspend(webView, "return JSON.stringify(${js});")
                        delay(1500)
                    }
                }
            }
            VaultRepository.markProfileUsed(profile)
            completed++
            updateProgress(completed, config.targetRepeatCount)
            delay(1000)
        }
        updateState(AutomationState.COMPLETED)
        updateStatus("Finished $completed / ${config.targetRepeatCount}")
        onAutomationFinished?.invoke(completed)
    }

    private suspend fun waitForPageReady(webView: WebView) {
        var waited = 0L
        while (waited < 8000L) {
            val progress = withContext(Dispatchers.Main) { webView.progress }
            val state = evaluateJsSuspend(webView, "return document.readyState;")
            if (progress >= 90 && (state.contains("interactive") || state.contains("complete"))) break
            delay(250); waited += 250
        }
    }

    private suspend fun evaluateJsSuspend(webView: WebView, script: String): String =
        suspendCancellableCoroutine { cont ->
            Handler(Looper.getMainLooper()).post {
                val wrapped = "(async () => { try { ${script.trim()} } catch(e) { return JSON.stringify({error: e.message}); } })();"
                webView.evaluateJavascript(wrapped) { result ->
                    val clean = if (result.isNullOrEmpty() || result == "null") "{}" else result
                    val unquoted = try { if (clean.startsWith("\"") && clean.endsWith("\"")) JSONTokener(clean).nextValue() as? String ?: clean else clean } catch (_: Exception) { clean }
                    cont.resume(unquoted) { _, _, _ -> }
                }
            }
        }

    fun setActiveTab(tabId: String) {
        if (activeTabId == tabId) return
        if (hudMode == HudMode.Menu) { hudMode = HudMode.Hidden; hudVisible = false }
        if (hudMode == HudMode.Recording && activeTabId != null) { cancelRecording(); Toast.makeText(appContext, "Recording cancelled", Toast.LENGTH_SHORT).show() }
        activeTabId = tabId
        _detectedFieldCount.postValue(0)
    }

    fun clearActiveTab(tabId: String) {
        if (activeTabId != tabId) return
        activeTabId = null
        _detectedFieldCount.postValue(0)
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

    fun startRecording() {
        val webView = currentWebView?.get() ?: return
        val url = webView.url.orEmpty()
        hudRecordingHost = AutomationScriptRepository.hostOfUrl(url)
        hudRecordingUrl = url
        hudRecordedSteps = emptyList()
        hudRecordingPhase = RecordingPhase.Capturing
        hudMode = HudMode.Recording
        hudVisible = true
        loadRecorder()?.let { webView.evaluateJavascript(it) { Log.d(TAG, "Recorder injected") } }
    }

    fun onRecorderElementCaptured(json: String, tabId: String?) {
        if (hudMode != HudMode.Recording) return
        try {
            val obj = JSONObject(json)
            val kindRaw = obj.optString("kind", "Field")
            val stepKind = when (kindRaw) { "ClickNext" -> StepKind.ClickNext; "Wait" -> StepKind.Wait; "CaptchaWait" -> StepKind.CaptchaWait; else -> StepKind.Field }
            val looksLike = obj.optString("looksLike", "")
            val canonicalType = if (stepKind == StepKind.Field) FieldTypeMapper.normalizeLooksLike(looksLike) else ""
            val displayLabel = if (stepKind == StepKind.ClickNext) obj.optString("buttonLabel", "Next button") else FieldTypeMapper.fieldRowLabel(canonicalType, obj.optString("displayLabel", ""))
            
            val step = RecordedStep(
                sequenceIndex = hudRecordedSteps.size + 1,
                kind = stepKind,
                fieldType = canonicalType,
                displayLabel = displayLabel,
                tag = obj.optString("tag"),
                domType = obj.optString("domType"),
                name = obj.optString("name"),
                domId = obj.optString("domId"),
                placeholder = obj.optString("placeholder"),
                ariaLabel = obj.optString("ariaLabel"),
                cssSelector = obj.optString("cssSelector"),
                xpath = obj.optString("xpath"),
                index = obj.optInt("index", -1),
                buttonLabel = obj.optString("buttonLabel"),
                verified = stepKind != StepKind.Field || canonicalType.isNotBlank(),
            )
            hudRecordedSteps = hudRecordedSteps + step
        } catch (e: Exception) { Log.e(TAG, "Parse fail: ${e.message}") }
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
        hudRecordingPhase = RecordingPhase.Saving
    }

    fun cancelSaveRecording() {
        hudRecordingPhase = RecordingPhase.Capturing
        currentWebView?.get()?.let { wv -> loadRecorder()?.let { js -> wv.evaluateJavascript(js) { Log.d(TAG, "Re-injected") } } }
    }

    fun saveRecording(name: String) {
        val ctx = appContext ?: return
        if (hudRecordingHost.isBlank() || hudRecordedSteps.isEmpty()) return
        currentWebView?.get()?.evaluateJavascript("try { window.__automationRecorderStop(); } catch(_) {}", null)

        val finalName = name.trim().ifBlank { "${hudRecordingHost} Script" }
        val script = AutomationScript(UUID.randomUUID().toString(), hudRecordingUrl, hudRecordingHost, finalName, System.currentTimeMillis(), System.currentTimeMillis(), hudRecordedSteps)
        AutomationScriptRepository.saveScript(script)
        Toast.makeText(ctx, "Script saved: $finalName", Toast.LENGTH_SHORT).show()

        hudRecordedSteps = emptyList()
        hudRecordingPhase = RecordingPhase.Capturing
        hudMode = HudMode.Menu
        hudVisible = true
    }

    fun cancelRecording() {
        currentWebView?.get()?.evaluateJavascript("try { window.__automationRecorderStop(); } catch(_) {}", null)
        hudRecordedSteps = emptyList()
        hudRecordingPhase = RecordingPhase.Capturing
        hudMode = HudMode.Hidden
        hudVisible = false
    }

    private fun loadRecorder(): String? = try {
        appContext?.assets?.open("automation/automation_recorder.js")?.bufferedReader()?.use { it.readText() }
    } catch (e: Exception) { null }

    private fun mountHudOverlayIfNeeded(webView: WebView) {
        val parent = webView.parent as? ViewGroup ?: return
        if (parent.findViewWithTag<ComposeView>(HUD_VIEW_TAG) != null) return
        val composeView = ComposeView(webView.context).apply {
            tag = HUD_VIEW_TAG
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnDetachedFromWindow)
            setContent { AutomationThemeWrapper(isLightMode = resolveDuckDuckGoIsLight(context)) { BrowserTabAutomationHud() } }
        }
        parent.addView(composeView, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { gravity = Gravity.BOTTOM })
    }
}