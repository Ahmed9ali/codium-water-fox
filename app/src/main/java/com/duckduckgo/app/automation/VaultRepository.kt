/*
 * Copyright (c) 2025 DuckDuckGo
 * ... license header ...
 */

package com.duckduckgo.app.automation

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.runtime.mutableStateOf
import org.json.JSONArray
import org.json.JSONObject

data class VaultEntry(
    val fieldType: String,
    val value: String,
)

data class VaultGroup(
    val name: String,
    val entries: List<VaultEntry>,
    val pinned: Boolean = false,
)

object VaultRepository {
    private const val PREFS_NAME = "automation_vault"
    private const val KEY_GROUPS = "groups"
    private const val MAX_PINNED = 5

    private var prefs: SharedPreferences? = null

    val groups = mutableStateOf<List<VaultGroup>>(emptyList())

    fun init(context: Context) {
        if (prefs != null) return
        prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        groups.value = load()
    }

    fun addGroup(name: String, rawText: String, pinned: Boolean = false) {
        val entries = parseEntries(rawText)
        val finalPinned = pinned && groups.value.count { it.pinned } < MAX_PINNED
        val group = VaultGroup(name = name.trim(), entries = entries, pinned = finalPinned)
        groups.value = groups.value + group
        save()
    }

    fun removeGroup(name: String) {
        groups.value = groups.value.filterNot { it.name == name }
        save()
    }

    fun renameGroup(oldName: String, newName: String) {
        val trimmed = newName.trim()
        if (trimmed.isBlank()) return
        groups.value = groups.value.map { g ->
            if (g.name == oldName) g.copy(name = trimmed) else g
        }
        save()
    }

    fun togglePin(name: String) {
        val current = groups.value.firstOrNull { it.name == name } ?: return
        if (!current.pinned && groups.value.count { it.pinned } >= MAX_PINNED) {
            return // silently ignore — UI will show a toast
        }
        groups.value = groups.value.map { g ->
            if (g.name == name) g.copy(pinned = !g.pinned) else g
        }
        // Sort: pinned first, original order preserved within each bucket
        val pinned = groups.value.filter { it.pinned }
        val unpinned = groups.value.filterNot { it.pinned }
        groups.value = pinned + unpinned
        save()
    }

    fun canPinMore(): Boolean = groups.value.count { it.pinned } < MAX_PINNED

    fun getGroup(name: String): VaultGroup? = groups.value.firstOrNull { it.name == name }

    fun updateEntryValue(groupName: String, index: Int, newValue: String) {
        groups.value = groups.value.map { g ->
            if (g.name != groupName) return@map g
            val updated = g.entries.toMutableList()
            if (index in updated.indices) {
                updated[index] = updated[index].copy(value = newValue)
            }
            g.copy(entries = updated)
        }
        save()
    }

    fun deleteEntry(groupName: String, index: Int) {
        groups.value = groups.value.map { g ->
            if (g.name != groupName) return@map g
            val updated = g.entries.toMutableList()
            if (index in updated.indices) updated.removeAt(index)
            g.copy(entries = updated)
        }
        save()
    }

    private fun parseEntries(rawText: String): List<VaultEntry> {
        return rawText
            .lines()
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .map { line ->
                val parts = line.split(":", limit = 2)
                if (parts.size == 2) {
                    VaultEntry(fieldType = parts[0].trim(), value = parts[1].trim())
                } else {
                    VaultEntry(fieldType = "text", value = line)
                }
            }
    }

    private fun save() {
        val json = JSONArray()
        groups.value.forEach { group ->
            val groupJson = JSONObject()
            groupJson.put("name", group.name)
            groupJson.put("pinned", group.pinned)
            val entriesJson = JSONArray()
            group.entries.forEach { entry ->
                val entryJson = JSONObject()
                entryJson.put("fieldType", entry.fieldType)
                entryJson.put("value", entry.value)
                entriesJson.put(entryJson)
            }
            groupJson.put("entries", entriesJson)
            json.put(groupJson)
        }
        prefs?.edit()?.putString(KEY_GROUPS, json.toString())?.apply()
    }

    private fun load(): List<VaultGroup> {
        val raw = prefs?.getString(KEY_GROUPS, null) ?: return emptyList()
        return try {
            val json = JSONArray(raw)
            (0 until json.length()).map { i ->
                val groupJson = json.getJSONObject(i)
                val entriesJson = groupJson.getJSONArray("entries")
                val entries = (0 until entriesJson.length()).map { j ->
                    val entryJson = entriesJson.getJSONObject(j)
                    VaultEntry(
                        fieldType = entryJson.getString("fieldType"),
                        value = entryJson.getString("value"),
                    )
                }
                VaultGroup(
                    name = groupJson.getString("name"),
                    entries = entries,
                    pinned = groupJson.optBoolean("pinned", false),
                )
            }
        } catch (e: Exception) {
            emptyList()
        }
    }
}