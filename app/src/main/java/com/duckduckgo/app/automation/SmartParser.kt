/*
 * Copyright (c) 2025 DuckDuckGo
 * ... license header ...
 */

package com.duckduckgo.app.automation

object SmartParser {
    private val emailRegex = Regex("[a-zA-Z0-9._%+-]+@[a-zA-Z0-9.-]+\\.[a-zA-Z]{2,}")
    private val phoneRegex = Regex("(\\+?\\d{1,3}[\\s-]?)?\\(?\\d{2,4}\\)?[\\s-]?\\d{3,4}[\\s-]?\\d{3,4}")
    private val dateRegex = Regex("\\b(\\d{1,2}[/.\\-]\\d{1,2}[/.\\-]\\d{2,4}|\\d{4}[/.\\-]\\d{1,2}[/.\\-]\\d{1,2})\\b")

    data class ParsedData(
        val emails: List<String>,
        val phones: List<String>,
        val dates: List<String>,
        val names: List<String>,
        val addresses: List<String>,
    ) {
        val all: List<Pair<String, String>> get() =
            emails.map { "email" to it } +
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

        // Filter phones by length to remove false matches like dates
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

        return ParsedData(
            emails = emails,
            phones = phones,
            dates = dates,
            names = names,
            addresses = addresses,
        )
    }

    private fun extractNames(text: String): List<String> {
        val results = mutableListOf<String>()
        val patterns = listOf(
            Regex("(?i)(?:my name is|i am|i'm|this is|name[:\\-])\\s+([A-Z][a-z]+(?:\\s+[A-Z][a-z]+){0,3})"),
            Regex("(?i)(?:mr|mrs|ms|miss|dr)\\.?\\s+([A-Z][a-z]+(?:\\s+[A-Z][a-z]+){0,3})"),
        )
        patterns.forEach { regex ->
            regex.findAll(text).forEach { match ->
                val name = match.groupValues[1].trim()
                if (name.length in 3..50) results.add(name)
            }
        }
        return results.distinct().take(10)
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
        return results.distinct().take(5)
    }

    private fun digitsOnly(s: String): String = s.filter { it.isDigit() }
}