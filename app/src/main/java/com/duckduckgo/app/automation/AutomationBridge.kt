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

import android.os.Handler
import android.os.Looper
import android.util.Log
import android.webkit.JavascriptInterface

/**
 * JS ⇄ Kotlin bridge. Every callback carries the originating [tabId] so
 * the engine can scope state to the correct tab (multi-tab safety, spec §18).
 *
 * Each @JavascriptInterface method must have a matching constructor callback;
 * otherwise the callback is dead code and JS invocations will fail at runtime.
 */
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
        Log.d(TAG, "onFieldsDetected[$tabId]: $json")
        handler.post { onFieldsDetectedCallback(json, tabId) }
    }

    @JavascriptInterface
    fun onPageScanned(json: String) {
        Log.d(TAG, "onPageScanned[$tabId]: $json")
        handler.post { onPageScannedCallback(json, tabId) }
    }

    @JavascriptInterface
    fun onFillCompleted(json: String) {
        Log.d(TAG, "onFillCompleted[$tabId]: $json")
        handler.post { onFillCompletedCallback(json, tabId) }
    }

    @JavascriptInterface
    fun onStepSettled(json: String) {
        Log.d(TAG, "onStepSettled[$tabId]: $json")
        handler.post { onStepSettledCallback(json, tabId) }
    }

    @JavascriptInterface
    fun onElementRecorded(json: String) {
        Log.d(TAG, "onElementRecorded[$tabId]: $json")
        handler.post { onElementRecordedCallback(json, tabId) }
    }

    private companion object {
        const val TAG = "AutomationBridge"
    }
}