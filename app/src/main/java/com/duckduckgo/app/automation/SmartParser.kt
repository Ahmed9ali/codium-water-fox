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

object SmartParser {
    private val emailRegex = Regex("[a-zA-Z0-9._%+-]+@[a-zA-Z0-9.-]+\\.[a-zA-Z]{2,}")
    private val phoneRegex = Regex("(\\+?\\d{1,3}[\\s-]?)?\\(?\\d{2,4}\\)?[\\s-]?\\d{3,4}[\\s-]?\\d{3,4}")
    private val dateRegex = Regex("\\b(\\d{1,2}[/.\\-]\\d{1,2}[/.\\-]\\d{2,4}|\\d{4}[/.\\-]\\d{1,2}[/.\\-]\\d{1,2})\\b")
    private val zipRegex = Regex("^\\d{5,6}(-\\d{4})?$")
    private val usernameRegex = Regex("^[a-zA-Z][a-zA-Z0-9_.]{3,24}$")

    data class ParsedData(
        val emails: List<String>,
        val phones: List<String>,
        val dates: List<String>,
        val names: List<String>,
        val addresses: List<String>,
        val passwords: List<String> = emptyList(),
        val usernames: List<String> = emptyList(),
    ) {
        val all: List<Pair<String, String>>
            get() =
                emails.map { "email" to it } +
                    usernames.map { "username" to it } +
                    passwords.map { "password" to it } +
                    phones.map { "phone" to it } +
                    dates.map { "dob" to it } +
                    names.map { "fullName" to it } +
                    addresses.map { "address" to it }
    }

    fun parse(rawText: String): ParsedData {
        val emails = emailRegex.findAll(rawText)
            .map { it.value.trim() }
            .distinct()
            .toList()

        val phones = phoneRegex.findAll(rawText)
            .map { it.value.trim() }
            .filter { digitsOnly(it).length in 8..15 }
            .distinct()
            .toList()

        val dates = dateRegex.findAll(rawText)
            .map { it.value.trim() }
            .distinct()
            .toList()

        val names = extractNames(rawText)
        val addresses = extractAddresses(rawText)
        val passwords = extractKeyedValues(rawText, listOf("password", "pwd", "pass"))
        val usernames = extractKeyedValues(rawText, listOf("username", "user", "login", "handle"))

        return ParsedData(
            emails = emails,
            phones = phones,
            dates = dates,
            names = names,
            addresses = addresses,
            passwords = passwords,
            usernames = usernames,
        )
    }

    /**
     * Classifies a single raw line/token when the user pastes unlabeled lines.
     */
    fun classifySingleToken(token: String): String {
        val trimmed = token.trim()
        if (emailRegex.matches(trimmed)) return "email"
        if (dateRegex.matches(trimmed)) return "dob"
        if (zipRegex.matches(trimmed)) return "zip"
        val digits = digitsOnly(trimmed)
        if (digits.length in 9..15 && trimmed.all { it.isDigit() || it in "+-() " }) return "phone"
        // Password heuristic: no spaces, 6..32 chars, contains both letters and digits/symbols
        if (!trimmed.contains(" ") && trimmed.length in 6..32 &&
            trimmed.any { it.isLetter() } &&
            (trimmed.any { it.isDigit() } || trimmed.any { !it.isLetterOrDigit() }) &&
            trimmed.any { !it.isLetterOrDigit() && it != '_' && it != '.' }
        ) {
            return "password"
        }
        if (usernameRegex.matches(trimmed) && (trimmed.contains("_") || trimmed.any { it.isDigit() })) {
            return "username"
        }
        // Full name heuristic: 2 or 3 capitalized words
        val words = trimmed.split(Regex("\\s+"))
        if (words.size in 2..3 && words.all { w -> w.length >= 2 && w.first().isUpperCase() && w.all { it.isLetter() } }) {
            return "fullName"
        }
        return "text"
    }

    private fun extractKeyedValues(text: String, keys: List<String>): List<String> {
        val results = mutableListOf<String>()
        val keyPattern = keys.joinToString("|")
        val regex = Regex("(?i)\\b(?:$keyPattern)\\s*[:=\\-]\\s*([^\\s,;]+)")
        regex.findAll(text).forEach { match ->
            val v = match.groupValues[1].trim()
            if (v.isNotEmpty()) results.add(v)
        }
        return results.distinct()
    }

    private fun extractNames(text: String): List<String> {
        val results = mutableListOf<String>()
        val patterns = listOf(
            Regex("(?i)(?:my name is|i am|i'm|this is|full\\s*name[:\\-]|name[:\\-])\\s*([A-Z][a-z]+(?:\\s+[A-Z][a-z]+){0,3})"),
            Regex("(?i)(?:mr|mrs|ms|miss|dr)\\.?\\s+([A-Z][a-z]+(?:\\s+[A-Z][a-z]+){0,3})"),
        )
        patterns.forEach { regex ->
            regex.findAll(text).forEach { match ->
                val name = match.groupValues[1].trim()
                if (name.length in 3..50) results.add(name)
            }
        }
        return results.distinct().take(20)
    }

    private fun extractAddresses(text: String): List<String> {
        val results = mutableListOf<String>()
        val lines = text.split("\n", ";")
        val addressKeywords = listOf(
            "street", "st.", "avenue", "ave", "road", "rd", "lane", "ln",
            "block", "sector", "nagar", "colony", "apartment", "apt",
            "floor", "building", "bldg", "house", "flat",
        )
        lines.forEach { line ->
            val lower = line.lowercase()
            if (addressKeywords.any { lower.contains(it) } && line.length in 10..200) {
                results.add(line.trim())
            }
        }
        return results.distinct().take(10)
    }

    private fun digitsOnly(s: String): String = s.filter { it.isDigit() }
}
