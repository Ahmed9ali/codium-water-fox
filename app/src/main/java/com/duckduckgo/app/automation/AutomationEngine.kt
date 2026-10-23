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
import kotlinx.coroutines.CancellableContinuation
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
    val typingDelayMs: Long = 60,
    val fieldDelayMs: Long = 100,
    val settleTimeoutMs: Long = 2000,
    val pauseOnCaptcha: Boolean = true,
    val clearTabBetweenRuns: Boolean = true,
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

    var onShowFireDialog: ((String) -> Unit)? = null
    private var fireDialogContinuation: CancellableContinuation<Unit>? = null

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
    
    var hudEditingScript by mutableStateOf<AutomationScript?>(null); private set

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
        
        // Force reset UI state to prevent getting stuck if the app was killed mid-run
        hudMode = HudMode.Hidden
        hudVisible = false
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
    }

    private fun updateProgress(done: Int, total: Int) {
        _completedCount.postValue(done)
        _totalTargetCount.postValue(total)
        hudCompleted = done
        hudTotal = total
    }

    fun attachWebView(webView: WebView, tabId: String? = null) {
        currentWebView = WeakReference(webView)
        if (tabId != null) {
            activeTabId = tabId
        }
        
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

        if (hudMode != HudMode.Recording) {
            webView.evaluateJavascript("try { window.__automationRecorderStop(); } catch(_) {}", null)
        }
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
        if (tabId != null && activeTabId != null && tabId != activeTabId) return
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

    fun moveStep(id: String, up: Boolean) {
        val current = hudEditingScript ?: return
        val steps = current.steps.toMutableList()
        val idx = steps.indexOfFirst { it.id == id }
        if (idx == -1) return
        val targetIdx = if (up) idx - 1 else idx + 1
        if (targetIdx in steps.indices) {
            val temp = steps[idx]
            steps[idx] = steps[targetIdx]
            steps[targetIdx] = temp
            val reindexed = steps.mapIndexed { i, s -> s.copy(sequenceIndex = i + 1) }
            hudEditingScript = current.copy(steps = reindexed)
        }
    }

    fun updateWaitStep(id: String, newWaitMs: Long) {
        val current = hudEditingScript ?: return
        val steps = current.steps.map { 
            if (it.id == id) it.copy(waitMs = newWaitMs, displayLabel = "Wait ${newWaitMs / 1000}s") else it 
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

    fun pauseAutomation() {
        automationJob?.cancel()
        updateState(AutomationState.PAUSED)
        updateStatus("Automation paused.")
    }

    fun resumeAutomation() {
        val script = hudSelectedScript ?: return
        val config = currentConfig ?: return
        updateState(AutomationState.NAVIGATING)
        automationJob?.cancel()
        automationJob = scope.launch { runScriptExecution(script, config) }
    }

    fun stopAutomation() {
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
            VaultRepository.resetUsed(config.groupName)
            updateState(AutomationState.NAVIGATING)
            automationJob?.cancel()
            automationJob = scope.launch { runScriptExecution(script, config) }
        } else {
            stopAutomation()
            onDataExhaustedAction?.invoke(config.groupName)
            appContext?.let { ctx ->
                val intent = Intent(ctx, AutomationActivity::class.java).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                ctx.startActivity(intent)
            }
        }
    }

    private fun escapeJsString(str: String?): String {
        if (str == null) return ""
        return str.replace("\\", "\\\\")
            .replace("'", "\\'")
            .replace("\"", "\\\"")
            .replace("\n", "\\n")
            .replace("\r", "\\r")
    }

    // ========================================================================
    // SCRIPT EXECUTION (Selenium-style Wait & Type)
    // ========================================================================

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
            delay(1500) // Wait for React/Vue to mount

            for (step in script.steps) {
                if (!scope.isActive) break
                
                when (step.kind) {
                    StepKind.Field -> {
                        updateState(AutomationState.FILLING)
                        updateStatus("Filling ${step.displayLabel}...")
                        val valToFill = profile.resolveForField(step.fieldType, step.name, step.tag) ?: ""
                        
                        val safeVal = escapeJsString(valToFill)
                        val safeCss = escapeJsString(step.cssSelector)
                        val safeXpath = escapeJsString(step.xpath)
                        val safeName = escapeJsString(step.name)
                        val safeId = escapeJsString(step.domId)

                        var success = false
                        for (attempt in 1..3) {
                            val js = """
                                (function() {
                                    try {
                                        let el = null;
                                        const id = '$safeId';
                                        const name = '$safeName';
                                        const css = '$safeCss';
                                        const xpath = '$safeXpath';

                                        if (id) el = document.getElementById(id);
                                        if (!el && name) el = document.querySelector(`[name="${name}"]`);
                                        if (!el && css) { try { el = document.querySelector(css); } catch(_){} }
                                        if (!el && xpath) { try { el = document.evaluate(xpath, document, null, XPathResult.FIRST_ORDERED_NODE_TYPE, null).singleNodeValue; } catch(_){} }
                                        
                                        if (!el) return {ok: false, err: 'not found'};
                                        
                                        el.focus();
                                        
                                        const nativeInput = Object.getOwnPropertyDescriptor(window.HTMLInputElement.prototype, "value").set;
                                        const nativeTextArea = Object.getOwnPropertyDescriptor(window.HTMLTextAreaElement.prototype, "value").set;
                                        
                                        if (el.tagName === 'TEXTAREA' && nativeTextArea) {
                                            nativeTextArea.call(el, '');
                                            el.dispatchEvent(new Event('input', { bubbles: true }));
                                            nativeTextArea.call(el, '$safeVal');
                                        } else if (nativeInput) {
                                            nativeInput.call(el, '');
                                            el.dispatchEvent(new Event('input', { bubbles: true }));
                                            nativeInput.call(el, '$safeVal');
                                        } else {
                                            el.value = '';
                                            el.dispatchEvent(new Event('input', { bubbles: true }));
                                            el.value = '$safeVal';
                                        }
                                        
                                        el.dispatchEvent(new Event('input', { bubbles: true }));
                                        el.dispatchEvent(new Event('change', { bubbles: true }));
                                        el.blur();
                                        return {ok: true};
                                    } catch(e) { return {ok: false, err: e.message}; }
                                })();
                            """.trimIndent()
                            val result = evaluateJsSuspend(webView, "return JSON.stringify(${js});")
                            if (result.contains("\"ok\":true") || result.contains("\"ok\": true")) {
                                success = true
                                break
                            }
                            delay(1000)
                        }
                        if (!success) Log.e(TAG, "Failed to fill field: ${step.displayLabel}")
                        delay(1000)
                    }
                    StepKind.Wait -> {
                        updateStatus("Waiting ${step.waitMs}ms...")
                        delay(step.waitMs)
                    }
                    StepKind.DeleteCookies -> {
                        updateState(AutomationState.CLEARING_DATA)
                        updateStatus("Triggering native delete dialog...")
                        
                        if (onShowFireDialog != null) {
                            suspendCancellableCoroutine<Unit> { cont ->
                                fireDialogContinuation = cont
                                onShowFireDialog?.invoke(step.fieldType)
                            }
                        } else {
                            if (step.fieldType == "delete_all") clearAllData(webView)
                            else clearTabData(webView)
                        }
                        delay(1000)
                    }
                    StepKind.CaptchaWait -> {
                        _captchaState.postValue(true)
                        updateState(AutomationState.WAITING_CAPTCHA_HUMAN)
                        return
                    }
                    StepKind.ClickNext -> {
                        updateState(AutomationState.CLICKING_NEXT)
                        updateStatus("Clicking ${step.displayLabel}...")
                        
                        val safeCss = escapeJsString(step.cssSelector)
                        val safeXpath = escapeJsString(step.xpath)
                        val safeName = escapeJsString(step.name)
                        val safeId = escapeJsString(step.domId)

                        var success = false
                        for (attempt in 1..3) {
                            val js = """
                                (function() {
                                    try {
                                        let el = null;
                                        const id = '$safeId';
                                        const name = '$safeName';
                                        const css = '$safeCss';
                                        const xpath = '$safeXpath';

                                        if (id) el = document.getElementById(id);
                                        if (!el && name) el = document.querySelector(`[name="${name}"]`);
                                        if (!el && css) { try { el = document.querySelector(css); } catch(_){} }
                                        if (!el && xpath) { try { el = document.evaluate(xpath, document, null, XPathResult.FIRST_ORDERED_NODE_TYPE, null).singleNodeValue; } catch(_){} }
                                        
                                        if (!el) return {ok: false, err: 'not found'};
                                        
                                        el.scrollIntoView({block: 'center'});
                                        
                                        // Simulate native React/Vue click
                                        const rect = el.getBoundingClientRect();
                                        const cx = rect.left + rect.width / 2;
                                        const cy = rect.top + rect.height / 2;
                                        const eventInit = { bubbles: true, cancelable: true, clientX: cx, clientY: cy, button: 0 };
                                        
                                        el.dispatchEvent(new MouseEvent("mousedown", eventInit));
                                        el.dispatchEvent(new MouseEvent("mouseup", eventInit));
                                        el.click();
                                        return {ok: true};
                                    } catch(e) { return {ok: false, err: e.message}; }
                                })();
                            """.trimIndent()
                            val result = evaluateJsSuspend(webView, "return JSON.stringify(${js});")
                            if (result.contains("\"ok\":true") || result.contains("\"ok\": true")) {
                                success = true
                                break
                            }
                            delay(1000)
                        }
                        if (!success) Log.e(TAG, "Failed to click: ${step.displayLabel}")
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

    // ========================================================================
    // LEGACY ACTIVITY SUPPORT (Scanner-driven)
    // ========================================================================

    fun queueAutomationInBrowserTab(config: AutomationConfig) {
        currentConfig = config
        pendingBrowserConfig = config
        hudGroupName = config.groupName
        updateProgress(0, config.targetRepeatCount)
        updateState(AutomationState.NAVIGATING)
        updateStatus("Opening ${config.targetUrl} in browser tab...")

        val existingWebView = currentWebView?.get()
        if (existingWebView != null) {
            mountHudOverlayIfNeeded(existingWebView)
            pendingBrowserConfig = null
            startAutomation(config, navigateFirstCycle = false)
        }
    }

    fun startAutomation(config: AutomationConfig, navigateFirstCycle: Boolean = true) {
        currentConfig = config
        hudGroupName = config.groupName
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
        automationJob = scope.launch { runAutomationLoop(config, navigateFirstCycle) }
    }

    private suspend fun runAutomationLoop(config: AutomationConfig, navigateFirstCycle: Boolean) {
        val webView = currentWebView?.get() ?: return
        val targetUri = if (config.targetUrl.startsWith("http")) config.targetUrl else "https://${config.targetUrl}"
        var isFirst = true
        
        while (hudCompleted < config.targetRepeatCount && scope.isActive) {
            if (!VaultRepository.hasUnusedData(config.groupName)) {
                _dataExhaustedState.postValue(true); updateState(AutomationState.WAITING_DATA_EXHAUSTED); return
            }
            val profile = VaultRepository.acquireNextProfile(config.groupName, config.mode) ?: return
            currentProfile = profile
            
            if (config.clearTabBetweenRuns && hudCompleted > 0) {
                updateState(AutomationState.CLEARING_DATA)
                updateStatus("Clearing tab cookies, storage & cache...")
                clearBrowserSession(webView)
                delay(500)
            }
            
            updateState(AutomationState.NAVIGATING)
            if (!isFirst || navigateFirstCycle) {
                updateStatus("Loading ${config.targetUrl}...")
                withContext(Dispatchers.Main) { webView.loadUrl(targetUri) }
            }
            isFirst = false
            waitForPageReady(webView); injectScanner(); delay(600)

            var stepSuccess = false
            var counter = 0
            while (counter < 5 && scope.isActive) {
                counter++
                updateState(AutomationState.SCANNING)
                updateStatus("Scanning form fields (Step $counter)...")
                val scanObj = try { JSONObject(evaluateJsSuspend(webView, "return JSON.stringify(__automationScan());")) } catch (_: Exception) { JSONObject() }
                val fieldsArr = scanObj.optJSONArray("fields") ?: JSONArray()
                
                if (scanObj.optBoolean("captchaDetected", false) && config.pauseOnCaptcha) {
                    _captchaState.postValue(true); updateState(AutomationState.WAITING_CAPTCHA_HUMAN); return
                }
                
                currentActionRef = scanObj.optString("actionButtonRef").takeIf { it.isNotBlank() }
                val fillArray = JSONArray()
                val singleFallbackValue = if (profile.fields.size == 1) profile.fields.values.firstOrNull() else null

                for (i in 0 until fieldsArr.length()) {
                    val f = fieldsArr.getJSONObject(i)
                    val looksLike = f.optString("looksLike")
                    val matched = if (looksLike == "termsCheckbox") "true" else profile.resolveForField(looksLike, f.optString("name"), f.optString("tag"))
                    if (!matched.isNullOrBlank()) {
                        fillArray.put(JSONObject().apply { put("ref", f.optString("ref")); put("index", f.optInt("index", i)); put("value", matched) })
                    }
                }
                
                if (fillArray.length() == 0) {
                    if (counter == 1) {
                        delay(1200); injectScanner()
                        val retryObj = try { JSONObject(evaluateJsSuspend(webView, "return JSON.stringify(__automationScan());")) } catch (_: Exception) { JSONObject() }
                        if ((retryObj.optJSONArray("fields")?.length() ?: 0) > 0) { counter--; continue }
                    }
                    stepSuccess = counter > 1; break 
                }
                
                updateState(AutomationState.FILLING)
                updateStatus("Filling ${fillArray.length()} field(s) (Step $counter)...")
                val fillOpts = JSONObject().apply { put("typingDelayMs", config.typingDelayMs); put("fieldDelayMs", config.fieldDelayMs) }
                evaluateJsSuspend(webView, "return JSON.stringify(await __automationFill(${JSONObject.quote(fillArray.toString())}, ${JSONObject.quote(fillOpts.toString())}));")
                delay(500)
                
                updateState(AutomationState.CLICKING_NEXT)
                val btnName = scanObj.optString("actionButtonName").ifBlank { "Next / Submit" }
                updateStatus("Clicking '$btnName' & checking next step...")
                val clickOpts = JSONObject().apply { put("actionRef", currentActionRef ?: ""); put("settleTimeoutMs", config.settleTimeoutMs); put("pauseOnCaptcha", config.pauseOnCaptcha) }
                val settle = try { JSONObject(evaluateJsSuspend(webView, "return JSON.stringify(await __automationClickNextAndSettle(${JSONObject.quote(clickOpts.toString())}));")) } catch (_: Exception) { JSONObject() }
                
                if (settle.optString("verdict") == "captcha_blocked" && config.pauseOnCaptcha) {
                    _captchaState.postValue(true); updateState(AutomationState.WAITING_CAPTCHA_HUMAN); return
                }
                
                delay(600); waitForPageReady(webView); injectScanner(); delay(500)
                val postNav = try { JSONObject(evaluateJsSuspend(webView, "return JSON.stringify(__automationScan());")) } catch (_: Exception) { JSONObject() }
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
                VaultRepository.markProfileUsed(profile)
                updateProgress(hudCompleted + 1, config.targetRepeatCount)
                updateStatus("Completed cycle ${hudCompleted} of ${config.targetRepeatCount}")
            } else {
                updateStatus("No matching fields found for '${config.groupName}'.")
                break
            }
            delay(1000)
        }
        updateState(AutomationState.COMPLETED)
        updateStatus("Finished $hudCompleted / ${config.targetRepeatCount} automation(s)!")
        onAutomationFinished?.invoke(hudCompleted)
    }

    private fun clearBrowserSession(webView: WebView) {
        try {
            webView.clearCache(true)
            webView.clearHistory()
            webView.clearFormData()
            CookieManager.getInstance().removeAllCookies(null)
            CookieManager.getInstance().flush()
            WebStorage.getInstance().deleteAllData()
            
            val js = """
                (function() {
                    try { localStorage.clear(); } catch(_) {}
                    try { sessionStorage.clear(); } catch(_) {}
                    try {
                        if (window.indexedDB && window.indexedDB.databases) {
                            window.indexedDB.databases().then(dbs => {
                                dbs.forEach(db => window.indexedDB.deleteDatabase(db.name));
                            });
                        }
                    } catch(_) {}
                    try {
                        if (navigator.serviceWorker) {
                            navigator.serviceWorker.getRegistrations().then(function(registrations) {
                                for(let registration of registrations) {
                                    registration.unregister();
                                }
                            });
                        }
                    } catch(_) {}
                    return "cleared";
                })();
            """.trimIndent()
            webView.evaluateJavascript(js, null)
        } catch (e: Exception) {
            Log.e(TAG, "Error clearing session: ${e.message}")
        }
    }

    private fun clearTabData(webView: WebView) {
        try {
            webView.clearCache(true)
            webView.clearFormData()

            val js = """
                (function() {
                    try { localStorage.clear(); } catch(_) {}
                    try { sessionStorage.clear(); } catch(_) {}
                    try {
                        if (window.indexedDB && window.indexedDB.databases) {
                            window.indexedDB.databases().then(dbs => {
                                dbs.forEach(db => window.indexedDB.deleteDatabase(db.name));
                            });
                        }
                    } catch(_) {}
                    try {
                        if (navigator.serviceWorker) {
                            navigator.serviceWorker.getRegistrations().then(function(registrations) {
                                for(let registration of registrations) { registration.unregister(); }
                            });
                        }
                    } catch(_) {}
                })();
            """.trimIndent()
            webView.evaluateJavascript(js, null)

            val url = webView.url ?: return
            val cookieManager = CookieManager.getInstance()
            cookieManager.getCookie(url)?.split(";")?.forEach { cookie ->
                val name = cookie.substringBefore("=").trim()
                cookieManager.setCookie(url, "$name=; Expires=Thu, 01 Jan 1970 00:00:00 GMT; Path=/")
            }
            cookieManager.flush()
        } catch (e: Exception) {
            Log.e(TAG, "Error clearing tab data: ${e.message}")
        }
    }

    private fun clearAllData(webView: WebView) {
        try {
            webView.clearCache(true)
            webView.clearHistory()
            webView.clearFormData()
            CookieManager.getInstance().removeAllCookies(null)
            CookieManager.getInstance().flush()
            WebStorage.getInstance().deleteAllData()
            
            val js = """
                (function() {
                    try { localStorage.clear(); } catch(_) {}
                    try { sessionStorage.clear(); } catch(_) {}
                    try {
                        if (window.indexedDB && window.indexedDB.databases) {
                            window.indexedDB.databases().then(dbs => {
                                dbs.forEach(db => window.indexedDB.deleteDatabase(db.name));
                            });
                        }
                    } catch(_) {}
                    try {
                        if (navigator.serviceWorker) {
                            navigator.serviceWorker.getRegistrations().then(function(registrations) {
                                for(let registration of registrations) { registration.unregister(); }
                            });
                        }
                    } catch(_) {}
                })();
            """.trimIndent()
            webView.evaluateJavascript(js, null)
        } catch (e: Exception) {
            Log.e(TAG, "Error clearing all data: ${e.message}")
        }
    }

    // ========================================================================
    // UTILS
    // ========================================================================

    fun resolveFireDialogResult() {
        fireDialogContinuation?.resumeWith(Result.success(Unit))
        fireDialogContinuation = null
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
        
        val script = AutomationScript(
            id = UUID.randomUUID().toString(),
            url = hudRecordingUrl,
            host = hudRecordingHost,
            name = finalName,
            groupName = "", 
            createdAt = System.currentTimeMillis(),
            updatedAt = System.currentTimeMillis(),
            steps = hudRecordedSteps
        )
        
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