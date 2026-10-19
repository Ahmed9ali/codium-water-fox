/*
 * Copyright (c) 2025 DuckDuckGo
 * ... license header ...
 */

package com.duckduckgo.app.automation

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.webkit.WebView
import android.widget.Toast
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import org.json.JSONArray
import org.json.JSONObject
import java.lang.ref.WeakReference

object AutomationEngine {
    private var currentWebView: WeakReference<WebView>? = null
    private var scannerJs: String? = null
    private var appContext: Context? = null

    private val _detectedFieldCount = MutableLiveData(0)
    val detectedFieldCount: LiveData<Int> get() = _detectedFieldCount

    data class DetectedField(
        val index: Int,
        val type: String,
        val name: String,
        val id: String,
        val looksLike: String,
    )

    private var lastDetectedFields: List<DetectedField> = emptyList()

    fun init(context: Context) {
        appContext = context.applicationContext
    }

    fun attachWebView(webView: WebView) {
        currentWebView = WeakReference(webView)
    }

    fun detachWebView() {
        currentWebView = null
        _detectedFieldCount.postValue(0)
        lastDetectedFields = emptyList()
    }

    fun injectScanner() {
        // DIAGNOSTIC: probe whether the bridge is registered
        currentWebView?.get()?.evaluateJavascript("typeof window.AutomationBridge") { result ->
            Handler(Looper.getMainLooper()).post {
                appContext?.let {
                    Toast.makeText(it, "Bridge type: $result", Toast.LENGTH_LONG).show()
                }
            }
        }

        val webView = currentWebView?.get()
        if (webView == null) {
            Log.d("AUTOMATION", "injectScanner: webView is NULL")
            return
        }
        val js = scannerJs ?: loadScanner()
        if (js == null) {
            Log.d("AUTOMATION", "injectScanner: JS asset could not be loaded")
            return
        }
        Log.d("AUTOMATION", "injectScanner: evaluating JS (${js.length} chars)")
        webView.evaluateJavascript(js) { result ->
            Log.d("AUTOMATION", "injectScanner result: $result")
        }
    }

    private fun loadScanner(): String? {
        val ctx = appContext ?: return null
        return try {
            ctx.assets.open("automation/automation_scanner.js")
                .bufferedReader().use { it.readText() }
                .also { scannerJs = it }
        } catch (e: Exception) {
            null
        }
    }

 fun onFieldsDetected(json: String) {
    Log.d("AUTOMATION", "onFieldsDetected called: $json")
    Handler(Looper.getMainLooper()).post {
        appContext?.let {
            Toast.makeText(it, "Bridge: ${json.take(80)}", Toast.LENGTH_LONG).show()
        }
    }
    try {
        // Debug-only payload — don't try to parse as field list
        if (json.contains("\"debug\"")) return

        val arr = JSONArray(json)
        // ... rest unchanged
            lastDetectedFields = (0 until arr.length()).map {
                val obj = arr.getJSONObject(it)
                DetectedField(
                    index = obj.getInt("index"),
                    type = obj.optString("type"),
                    name = obj.optString("name"),
                    id = obj.optString("id"),
                    looksLike = obj.optString("looksLike"),
                )
            }
            Log.d("AUTOMATION", "Parsed ${lastDetectedFields.size} fields")
            _detectedFieldCount.postValue(lastDetectedFields.size)
        } catch (e: Exception) {
            Log.d("AUTOMATION", "onFieldsDetected parse error: ${e.message}")
            _detectedFieldCount.postValue(0)
        }
    }

    fun fillCurrentPage() {
        val webView = currentWebView?.get() ?: return
        val groups = VaultRepository.groups.value
        val entries = groups.firstOrNull()?.entries ?: return
        if (entries.isEmpty() || lastDetectedFields.isEmpty()) return

        val byType = entries.associate { it.fieldType.lowercase() to it.value }
        val fallback = entries.firstOrNull()?.value ?: ""

        val fillList = JSONArray()
        lastDetectedFields.forEach { field ->
            val value = byType[field.looksLike]
                ?: byType["text"]
                ?: fallback
            val obj = JSONObject()
            obj.put("index", field.index)
            obj.put("value", value)
            fillList.put(obj)
        }
        val arg = JSONObject.quote(fillList.toString())
        webView.evaluateJavascript("(async () => { await window.__automationFill($arg); })();", null)
    }
}