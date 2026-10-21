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
import android.content.SharedPreferences
import android.net.Uri
import androidx.compose.runtime.mutableStateOf
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/**
 * Saved step-by-step skeleton for one host.
 * Steps describe *what to do*; the actual field values come from VaultRepository at run time.
 */
data class AutomationScript(
    val id: String,
    val host: String,
    val name: String,
    val createdAt: Long,
    val updatedAt: Long,
    val steps: List<RecordedStep>,
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id)
        put("host", host)
        put("name", name)
        put("createdAt", createdAt)
        put("updatedAt", updatedAt)
        put("steps", JSONArray().apply { steps.forEach { put(it.toJson()) } })
    }

    companion object {
        fun fromJson(json: JSONObject): AutomationScript {
            val stepsJson = json.optJSONArray("steps") ?: JSONArray()
            val steps = (0 until stepsJson.length()).map {
                RecordedStep.fromJson(stepsJson.getJSONObject(it))
            }
            return AutomationScript(
                id = json.optString("id").ifBlank { UUID.randomUUID().toString() },
                host = json.optString("host"),
                name = json.optString("name"),
                createdAt = json.optLong("createdAt", System.currentTimeMillis()),
                updatedAt = json.optLong("updatedAt", System.currentTimeMillis()),
                steps = steps,
            )
        }
    }
}

object AutomationScriptRepository {
    private const val PREFS_NAME = "automation_scripts"
    private const val KEY_SCRIPTS = "scripts"

    private var prefs: SharedPreferences? = null

    val scripts = mutableStateOf<List<AutomationScript>>(emptyList())

    fun init(context: Context) {
        if (prefs != null) return
        prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        scripts.value = load()
    }

    /**
     * Insert or replace by id. Preserves the original createdAt when updating.
     */
    fun saveScript(script: AutomationScript): String {
        val now = System.currentTimeMillis()
        val existing = scripts.value.firstOrNull { it.id == script.id }
        val toStore = if (existing != null) {
            script.copy(createdAt = existing.createdAt, updatedAt = now)
        } else {
            script.copy(createdAt = now, updatedAt = now)
        }
        scripts.value = if (existing != null) {
            scripts.value.map { if (it.id == toStore.id) toStore else it }
        } else {
            scripts.value + toStore
        }
        save()
        return toStore.id
    }

    fun deleteScript(id: String) {
        scripts.value = scripts.value.filterNot { it.id == id }
        save()
    }

    fun getScript(id: String): AutomationScript? = scripts.value.firstOrNull { it.id == id }

    fun scriptsForHost(host: String): List<AutomationScript> =
        scripts.value
            .filter { it.host.equals(host, ignoreCase = true) }
            .sortedByDescending { it.updatedAt }

    fun allScriptsNewestFirst(): List<AutomationScript> =
        scripts.value.sortedByDescending { it.updatedAt }

    /** "https://www.example.com/path" → "example.com". Never throws. */
    fun hostOfUrl(url: String): String = try {
        Uri.parse(url).host?.lowercase()?.removePrefix("www.") ?: ""
    } catch (_: Exception) {
        ""
    }

    private fun save() {
        val arr = JSONArray()
        scripts.value.forEach { arr.put(it.toJson()) }
        prefs?.edit()?.putString(KEY_SCRIPTS, arr.toString())?.apply()
    }

    private fun load(): List<AutomationScript> {
        val raw = prefs?.getString(KEY_SCRIPTS, null) ?: return emptyList()
        return try {
            val arr = JSONArray(raw)
            (0 until arr.length()).map { AutomationScript.fromJson(arr.getJSONObject(it)) }
        } catch (_: Exception) {
            emptyList()
        }
    }
}