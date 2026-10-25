package com.duckduckgo.app.automation

import android.content.Context
import android.content.SharedPreferences
import android.net.Uri
import androidx.compose.runtime.mutableStateOf
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

data class AutomationScript(
    val id: String,
    val url: String,
    val host: String,
    val name: String,
    val groupNames: List<String> = emptyList(), // Supports multiple recurring groups
    val defaultGroupName: String = "",
    val humanize: Boolean = false,
    val createdAt: Long,
    val updatedAt: Long,
    val steps: List<RecordedStep>,
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id)
        put("url", url)
        put("host", host)
        put("name", name)
        put("groupNames", JSONArray(groupNames))
        put("defaultGroupName", defaultGroupName)
        put("humanize", humanize)
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
            
            val groupsJson = json.optJSONArray("groupNames")
            val groupNames = if (groupsJson != null) {
                (0 until groupsJson.length()).map { groupsJson.getString(it) }
            } else {
                // Migration from old single groupName string
                listOfNotNull(json.optString("groupName").takeIf { it.isNotBlank() })
            }

            return AutomationScript(
                id = json.optString("id").ifBlank { UUID.randomUUID().toString() },
                url = json.optString("url"),
                host = json.optString("host"),
                name = json.optString("name"),
                groupNames = groupNames,
                defaultGroupName = json.optString("defaultGroupName"),
                humanize = json.optBoolean("humanize", false),
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

    fun getScript(id: String): AutomationScript? =
        scripts.value.firstOrNull { it.id == id }

    fun renameScript(id: String, newName: String): Boolean {
        val existing = scripts.value.firstOrNull { it.id == id } ?: return false
        val trimmed = newName.trim()
        if (trimmed.isBlank() || trimmed == existing.name) return false
        saveScript(existing.copy(name = trimmed))
        return true
    }

    fun scriptsForHost(host: String): List<AutomationScript> =
        scripts.value
            .filter { it.host.equals(host, ignoreCase = true) }
            .sortedByDescending { it.updatedAt }

    fun allScripts(): List<AutomationScript> = scripts.value.sortedByDescending { it.updatedAt }

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