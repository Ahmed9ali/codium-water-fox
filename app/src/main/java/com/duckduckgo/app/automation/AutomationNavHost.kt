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

package com.duckduckgo.app.automation

import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Gravity
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.WebStorage
import android.webkit.WebView
import android.widget.FrameLayout
import androidx.appcompat.app.AppCompatDelegate
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import com.duckduckgo.common.ui.compose.theme.DuckDuckGoTheme
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

data class AutomationConfig(
    val targetUrl: String,
    val groupName: String,
    val mode: String, // "series" (top_down), "bottom_up", "random"
    val targetRepeatCount: Int = 1,
    val typingDelayMs: Long = 60,
    val fieldDelayMs: Long = 100,
    val settleTimeoutMs: Long = 2000,
    val pauseOnCaptcha: Boolean = true,
    val clearTabBetweenRuns: Boolean = true,
)

enum class AutomationState {
    IDLE,
    NAVIGATING,
    SCANNING,
    FILLING,
    CLICKING_NEXT,
    WAITING_SETTLE,
    WAITING_CAPTCHA_HUMAN,
    WAITING_DATA_EXHAUSTED,
    CLEARING_DATA,
    COMPLETED,
    PAUSED,
    ERROR,
}

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

    var hudVisible by mutableStateOf(false)
        private set
    var hudState by mutableStateOf(AutomationState.IDLE)
        private set
    var hudCompleted by mutableIntStateOf(0)
        private set
    var hudTotal by mutableIntStateOf(1)
        private set
    var hudStatus by mutableStateOf("")
        private set
    var hudGroupName by mutableStateOf("")
        private set

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
    }

    private fun updateState(newState: AutomationState) {
        _state.postValue(newState)
        hudState = newState
        hudVisible = newState != AutomationState.IDLE
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

    fun attachWebView(webView: WebView) {
        currentWebView = WeakReference(webView)
        try {
            val bridge = AutomationBridge(
                onFieldsDetectedCallback = { json -> onFieldsDetected(json) },
                onPageScannedCallback = { json -> handlePageScanned(json) },
                onFillCompletedCallback = { json -> handleFillCompleted(json) },
                onStepSettledCallback = { json -> handleStepSettled(json) },
            )
            webView.addJavascriptInterface(bridge, "AutomationBridge")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to add JavascriptInterface: ${e.message}")
        }

        mountHudOverlayIfNeeded(webView)

        val queued = pendingBrowserConfig
        if (queued != null) {
            pendingBrowserConfig = null
            startAutomation(queued, navigateFirstCycle = false)
        }
    }

    fun detachWebView() {
        currentWebView = null
        _detectedFieldCount.postValue(0)
    }

    fun injectScanner() {
        val webView = currentWebView?.get() ?: return
        val js = scannerJs ?: loadScanner() ?: return
        webView.evaluateJavascript(js) {
            Log.d(TAG, "Scanner injected into tab")
        }
    }

    private fun loadScanner(): String? {
        val ctx = appContext ?: return null
        return try {
            ctx.assets.open("automation/automation_scanner.js")
                .bufferedReader().use { it.readText() }
                .also { scannerJs = it }
        } catch (e: Exception) {
            Log.e(TAG, "Error loading scanner asset: ${e.message}")
            null
        }
    }

    fun onFieldsDetected(json: String) {
        try {
            if (json.contains("\"debug\"")) return
            val arr = JSONArray(json)
            currentFieldsJson = arr
            _detectedFieldCount.postValue(arr.length())
        } catch (e: Exception) {
            _detectedFieldCount.postValue(0)
        }
    }

    fun fillCurrentPage() {
        val group = VaultRepository.groups.value.firstOrNull() ?: return
        val webView = currentWebView?.get() ?: return
        val url = webView.url ?: return
        startAutomation(
            config = AutomationConfig(
                targetUrl = url,
                groupName = group.name,
                mode = "series",
                targetRepeatCount = 1,
            ),
            navigateFirstCycle = false,
        )
    }

    fun startAutomation(config: AutomationConfig, navigateFirstCycle: Boolean = true) {
        currentConfig = config
        hudGroupName = config.groupName
        updateProgress(0, config.targetRepeatCount)
        updateState(AutomationState.NAVIGATING)
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
        automationJob = scope.launch {
            runAutomationLoop(config, navigateFirstCycle)
        }
    }

    fun pauseAutomation() {
        automationJob?.cancel()
        updateState(AutomationState.PAUSED)
        updateStatus("Automation paused.")
    }

    fun resumeAutomation() {
        val config = currentConfig ?: return
        updateState(AutomationState.SCANNING)
        automationJob?.cancel()
        automationJob = scope.launch {
            runAutomationLoop(config, navigateFirstCycle = false)
        }
    }

    fun stopAutomation() {
        automationJob?.cancel()
        currentConfig = null
        pendingBrowserConfig = null
        currentProfile = null
        _captchaState.postValue(false)
        _dataExhaustedState.postValue(false)
        updateState(AutomationState.IDLE)
        updateStatus("")
        hudVisible = false
    }

    fun continueAfterCaptchaResolution() {
        _captchaState.postValue(false)
        resumeAutomation()
    }

    fun handleDataExhaustedChoice(choice: String) {
        val config = currentConfig ?: return
        _dataExhaustedState.postValue(false)
        if (choice == "repeat_same") {
            VaultRepository.resetUsed(config.groupName)
            updateState(AutomationState.NAVIGATING)
            automationJob?.cancel()
            automationJob = scope.launch {
                runAutomationLoop(config, navigateFirstCycle = true)
            }
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

    private suspend fun runAutomationLoop(config: AutomationConfig, navigateFirstCycle: Boolean) {
        val webView = currentWebView?.get() ?: return
        val targetUri = if (config.targetUrl.startsWith("http://") || config.targetUrl.startsWith("https://")) {
            config.targetUrl
        } else {
            "https://${config.targetUrl}"
        }

        var isFirstIteration = true

        while (hudCompleted < config.targetRepeatCount && scope.isActive) {
            if (!VaultRepository.hasUnusedData(config.groupName)) {
                _dataExhaustedState.postValue(true)
                updateState(AutomationState.WAITING_DATA_EXHAUSTED)
                updateStatus("All data in '${config.groupName}' used. Repeat or add new data?")
                return
            }

            val profile = VaultRepository.acquireNextProfile(config.groupName, config.mode)
            if (profile == null) {
                _dataExhaustedState.postValue(true)
                updateState(AutomationState.WAITING_DATA_EXHAUSTED)
                updateStatus("Dataset profile allocation failed. Choose action.")
                return
            }
            currentProfile = profile

            if (config.clearTabBetweenRuns && hudCompleted > 0) {
                updateState(AutomationState.CLEARING_DATA)
                updateStatus("Clearing tab cookies, storage & cache...")
                clearBrowserSession(webView)
                delay(500)
            }

            updateState(AutomationState.NAVIGATING)
            if (!isFirstIteration || navigateFirstCycle) {
                updateStatus("Loading ${config.targetUrl}...")
                withContext(Dispatchers.Main) {
                    webView.loadUrl(targetUri)
                }
            } else {
                updateStatus("Waiting for page ${config.targetUrl} to ready...")
                val currentUrl = withContext(Dispatchers.Main) { webView.url.orEmpty() }
                if (currentUrl.isBlank() || currentUrl == "about:blank") {
                    withContext(Dispatchers.Main) {
                        webView.loadUrl(targetUri)
                    }
                }
            }
            isFirstIteration = false

            waitForPageReady(webView)
            injectScanner()
            delay(600)

            var stepSuccess = false
            var multiStepCounter = 0

            while (multiStepCounter < 5 && scope.isActive) {
                multiStepCounter++

                updateState(AutomationState.SCANNING)
                updateStatus("Scanning form fields (Step $multiStepCounter)...")
                val scanResultJson = evaluateJsSuspend(webView, "return JSON.stringify(__automationScan());")
                val scanObj = try { JSONObject(scanResultJson) } catch (_: Exception) { JSONObject() }

                val fieldsArr = scanObj.optJSONArray("fields") ?: currentFieldsJson
                currentFieldsJson = fieldsArr

                if (scanObj.optBoolean("captchaDetected", false) && config.pauseOnCaptcha) {
                    _captchaState.postValue(true)
                    updateState(AutomationState.WAITING_CAPTCHA_HUMAN)
                    updateStatus("CAPTCHA detected! Solve it in the tab, then tap Continue.")
                    return
                }

                currentActionRef = scanObj.optString("actionButtonRef").takeIf { it.isNotBlank() }

                val fillArray = JSONArray()
                for (i in 0 until fieldsArr.length()) {
                    val fieldObj = fieldsArr.getJSONObject(i)
                    val ref = fieldObj.optString("ref")
                    val looksLike = fieldObj.optString("looksLike")
                    val name = fieldObj.optString("name")

                    val matchedValue = if (looksLike == "termsCheckbox") {
                        "true"
                    } else {
                        profile.resolveForField(looksLike, name)
                    }

                    if (!matchedValue.isNullOrBlank()) {
                        val fillItem = JSONObject().apply {
                            put("ref", ref)
                            put("index", fieldObj.optInt("index", i))
                            put("value", matchedValue)
                        }
                        fillArray.put(fillItem)
                    }
                }

                if (fillArray.length() == 0) {
                    if (multiStepCounter == 1) {
                        delay(1200)
                        injectScanner()
                        val retryScanJson = evaluateJsSuspend(webView, "return JSON.stringify(__automationScan());")
                        val retryObj = try { JSONObject(retryScanJson) } catch (_: Exception) { JSONObject() }
                        val retryFields = retryObj.optJSONArray("fields") ?: JSONArray()
                        if (retryFields.length() > 0) {
                            multiStepCounter--
                            continue
                        }
                    }
                    stepSuccess = multiStepCounter > 1
                    break
                }

                updateState(AutomationState.FILLING)
                updateStatus("Filling ${fillArray.length()} field(s) (Step $multiStepCounter)...")

                val optionsObj = JSONObject().apply {
                    put("typingDelayMs", config.typingDelayMs)
                    put("fieldDelayMs", config.fieldDelayMs)
                }

                val fillArg = JSONObject.quote(fillArray.toString())
                val optArg = JSONObject.quote(optionsObj.toString())
                evaluateJsSuspend(webView, "return JSON.stringify(await __automationFill($fillArg, $optArg));")
                delay(500)

                updateState(AutomationState.CLICKING_NEXT)
                val btnName = scanObj.optString("actionButtonName").ifBlank { "Next / Submit" }
                updateStatus("Clicking '$btnName' & checking next step...")

                val clickOptions = JSONObject().apply {
                    put("actionRef", currentActionRef ?: "")
                    put("settleTimeoutMs", config.settleTimeoutMs)
                    put("pauseOnCaptcha", config.pauseOnCaptcha)
                }
                val clickArg = JSONObject.quote(clickOptions.toString())
                val settleResultJson = evaluateJsSuspend(
                    webView,
                    "return JSON.stringify(await __automationClickNextAndSettle($clickArg));",
                )
                val settleObj = try { JSONObject(settleResultJson) } catch (_: Exception) { JSONObject() }

                val verdict = settleObj.optString("verdict", "none")
                if (verdict == "captcha_blocked" && config.pauseOnCaptcha) {
                    _captchaState.postValue(true)
                    updateState(AutomationState.WAITING_CAPTCHA_HUMAN)
                    updateStatus("CAPTCHA appeared! Solve it in the tab, then tap Continue.")
                    return
                }

                delay(600)
                waitForPageReady(webView)
                injectScanner()
                delay(500)

                val postNavScanJson = evaluateJsSuspend(webView, "return JSON.stringify(__automationScan());")
                val postNavObj = try { JSONObject(postNavScanJson) } catch (_: Exception) { JSONObject() }
                if (postNavObj.optBoolean("captchaDetected", false) && config.pauseOnCaptcha) {
                    _captchaState.postValue(true)
                    updateState(AutomationState.WAITING_CAPTCHA_HUMAN)
                    updateStatus("CAPTCHA detected! Solve it in the tab, then tap Continue.")
                    return
                }

                val postFields = postNavObj.optJSONArray("fields") ?: JSONArray()
                var unfilledNextStepCount = 0
                for (k in 0 until postFields.length()) {
                    val f = postFields.getJSONObject(k)
                    val curVal = f.optString("currentValue", "")
                    val typeKey = f.optString("looksLike", "")
                    if (typeKey != "termsCheckbox" && curVal.isBlank()) {
                        unfilledNextStepCount++
                    }
                }

                if (unfilledNextStepCount > 0 && verdict != "validation_error" && verdict != "no_button_found") {
                    updateStatus("Moved to Step ${multiStepCounter + 1} — continuing with same profile...")
                    delay(400)
                } else {
                    stepSuccess = true
                    break
                }
            }

            if (stepSuccess) {
                VaultRepository.markProfileUsed(profile)
                val nextDone = hudCompleted + 1
                updateProgress(nextDone, config.targetRepeatCount)
                updateStatus("Completed cycle $nextDone of ${config.targetRepeatCount}")
            } else {
                updateStatus("No fillable form found on page.")
                break
            }

            delay(1000)
        }

        updateState(AutomationState.COMPLETED)
        updateStatus("Finished $hudCompleted / ${config.targetRepeatCount} automation(s)!")
        onAutomationFinished?.invoke(hudCompleted)
    }

    private suspend fun waitForPageReady(webView: WebView) {
        var waitedMs = 0L
        while (waitedMs < 8000L) {
            val progress = withContext(Dispatchers.Main) { webView.progress }
            val readyState = evaluateJsSuspend(webView, "return document.readyState;")
            if (progress >= 90 && (readyState.contains("interactive") || readyState.contains("complete"))) {
                break
            }
            delay(250)
            waitedMs += 250
        }
    }

    private suspend fun evaluateJsSuspend(webView: WebView, script: String): String =
        suspendCancellableCoroutine { continuation ->
            Handler(Looper.getMainLooper()).post {
                val wrapped = "(async () => { try { ${script.trim()} } catch(e) { return JSON.stringify({error: e.message}); } })();"
                webView.evaluateJavascript(wrapped) { result ->
                    val clean = if (result.isNullOrEmpty() || result == "null") "{}" else result
                    val unquoted = try {
                        if (clean.startsWith("\"") && clean.endsWith("\"")) {
                            JSONTokener(clean).nextValue() as? String ?: clean
                        } else {
                            clean
                        }
                    } catch (_: Exception) {
                        clean
                    }
                    continuation.resume(unquoted) { _, _, _ -> }
                }
            }
        }

    private fun clearBrowserSession(webView: WebView) {
        try {
            webView.clearCache(true)
            webView.clearHistory()
            webView.clearFormData()
            CookieManager.getInstance().removeAllCookies(null)
            CookieManager.getInstance().flush()
            WebStorage.getInstance().deleteAllData()
            webView.evaluateJavascript(
                "try { localStorage.clear(); sessionStorage.clear(); } catch(_) {}",
                null,
            )
        } catch (e: Exception) {
            Log.e(TAG, "Error clearing browser session: ${e.message}")
        }
    }

    private fun handlePageScanned(json: String) {}
    private fun handleFillCompleted(json: String) {}
    private fun handleStepSettled(json: String) {}

    private fun mountHudOverlayIfNeeded(webView: WebView) {
        val parent = webView.parent as? ViewGroup ?: return
        if (parent.findViewWithTag<ComposeView>(HUD_VIEW_TAG) != null) return

        val composeView = ComposeView(webView.context).apply {
            tag = HUD_VIEW_TAG
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnDetachedFromWindow)
            setContent {
                val isLight = isLightThemeEnabled(context)
                AutomationThemeWrapper(isLightMode = isLight) {
                    BrowserTabAutomationHud()
                }
            }
        }

        val params = FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
        ).apply {
            gravity = Gravity.BOTTOM
        }
        parent.addView(composeView, params)
    }

    private fun isLightThemeEnabled(context: Context): Boolean {
        val prefs = context.getSharedPreferences("com.duckduckgo.app.settings_activity.settings", Context.MODE_PRIVATE)
        return when (prefs.getString("GLOBAL_THEME", "SYSTEM_DEFAULT")?.uppercase()) {
            "LIGHT" -> true
            "DARK" -> false
            else -> {
                when (AppCompatDelegate.getDefaultNightMode()) {
                    AppCompatDelegate.MODE_NIGHT_NO -> true
                    AppCompatDelegate.MODE_NIGHT_YES -> false
                    else -> {
                        val uiMode = context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK
                        uiMode != Configuration.UI_MODE_NIGHT_YES
                    }
                }
            }
        }
    }
}

@Composable
private fun BrowserTabAutomationHud() {
    if (!AutomationEngine.hudVisible) return

    val state = AutomationEngine.hudState
    val completed = AutomationEngine.hudCompleted
    val total = AutomationEngine.hudTotal
    val status = AutomationEngine.hudStatus
    val groupName = AutomationEngine.hudGroupName
    val unusedLeft = if (groupName.isNotBlank()) VaultRepository.unusedProfileCount(groupName) else 0

    val shape = RoundedCornerShape(16.dp)

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 12.dp),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(shape)
                .background(DuckDuckGoTheme.colors.backgrounds.surface, shape)
                .border(1.5.dp, DuckDuckGoTheme.colors.brand.accentBlue, shape)
                .padding(14.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = if (groupName.isNotBlank()) "Automation · $groupName" else "Browser Automation",
                        color = DuckDuckGoTheme.colors.text.primary,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Bold,
                    )
                    Spacer(Modifier.height(2.dp))
                    Text(
                        text = status.ifBlank { "Running..." },
                        color = DuckDuckGoTheme.colors.text.secondary,
                        fontSize = 12.sp,
                    )
                }
                Spacer(Modifier.width(8.dp))
                DdgStatusBadge(
                    text = "$completed / $total",
                    isPositive = state != AutomationState.WAITING_CAPTCHA_HUMAN &&
                        state != AutomationState.WAITING_DATA_EXHAUSTED,
                )
            }

            Spacer(Modifier.height(8.dp))

            LinearProgressIndicator(
                progress = {
                    if (total <= 0) 0f else (completed.toFloat() / total.toFloat()).coerceIn(0f, 1f)
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(6.dp)
                    .clip(RoundedCornerShape(4.dp)),
                color = DuckDuckGoTheme.colors.brand.accentBlue,
                trackColor = DuckDuckGoTheme.colors.backgrounds.background,
            )

            Spacer(Modifier.height(6.dp))

            Text(
                text = "$unusedLeft unused profile(s) remaining in group",
                color = DuckDuckGoTheme.colors.text.secondary,
                fontSize = 11.sp,
            )

            Spacer(Modifier.height(10.dp))

            when (state) {
                AutomationState.WAITING_CAPTCHA_HUMAN -> {
                    DdgPrimaryButton(
                        text = "I Solved the CAPTCHA — Continue",
                        onClick = { AutomationEngine.continueAfterCaptchaResolution() },
                    )
                    Spacer(Modifier.height(6.dp))
                    DdgSecondaryButton(
                        text = "Stop Automation",
                        onClick = { AutomationEngine.stopAutomation() },
                    )
                }

                AutomationState.WAITING_DATA_EXHAUSTED -> {
                    Text(
                        text = "Group data finished! Repeat with same data or add new data?",
                        color = DuckDuckGoTheme.colors.text.primary,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Spacer(Modifier.height(8.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        DdgPrimaryButton(
                            text = "Repeat Data",
                            modifier = Modifier.weight(1f),
                            onClick = { AutomationEngine.handleDataExhaustedChoice("repeat_same") },
                        )
                        DdgSecondaryButton(
                            text = "Add Data",
                            modifier = Modifier.weight(1f),
                            onClick = { AutomationEngine.handleDataExhaustedChoice("add_new") },
                        )
                    }
                    Spacer(Modifier.height(6.dp))
                    Text(
                        text = "Dismiss & Finish",
                        color = DuckDuckGoTheme.colors.text.secondary,
                        fontSize = 12.sp,
                        modifier = Modifier
                            .align(Alignment.CenterHorizontally)
                            .clickable { AutomationEngine.stopAutomation() }
                            .padding(4.dp),
                    )
                }

                AutomationState.COMPLETED -> {
                    DdgPrimaryButton(
                        text = "Done — Dismiss Banner",
                        onClick = { AutomationEngine.stopAutomation() },
                    )
                }

                else -> {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        if (state == AutomationState.PAUSED) {
                            DdgPrimaryButton(
                                text = "Resume",
                                modifier = Modifier.weight(1f),
                                onClick = { AutomationEngine.resumeAutomation() },
                            )
                        } else {
                            DdgSecondaryButton(
                                text = "Pause",
                                modifier = Modifier.weight(1f),
                                onClick = { AutomationEngine.pauseAutomation() },
                            )
                        }
                        DdgSecondaryButton(
                            text = "Stop",
                            modifier = Modifier.weight(1f),
                            onClick = { AutomationEngine.stopAutomation() },
                        )
                    }
                }
            }
        }
    }
}
