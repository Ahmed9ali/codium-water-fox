package com.duckduckgo.app.automation

import android.os.Handler
import android.os.Looper
import android.webkit.JavascriptInterface

class AutomationBridge(
    private val tabId: String?,
    private val onFieldsDetectedCallback: (String, String?) -> Unit,
    private val onPageScannedCallback: (String, String?) -> Unit,
    private val onFillCompletedCallback: (String, String?) -> Unit,
    private val onStepSettledCallback: (String, String?) -> Unit,
    private val onElementRecordedCallback: (String, String?) -> Unit,
) {
    private val handler = Handler(Looper.getMainLooper())

    @JavascriptInterface
    fun onFieldsDetected(json: String) {
        handler.post { onFieldsDetectedCallback(json, tabId) }
    }

    @JavascriptInterface
    fun onPageScanned(json: String) {
        handler.post { onPageScannedCallback(json, tabId) }
    }

    @JavascriptInterface
    fun onFillCompleted(json: String) {
        handler.post { onFillCompletedCallback(json, tabId) }
    }

    @JavascriptInterface
    fun onStepSettled(json: String) {
        handler.post { onStepSettledCallback(json, tabId) }
    }

    @JavascriptInterface
    fun onElementRecorded(json: String) {
        handler.post { onElementRecordedCallback(json, tabId) }
    }
}