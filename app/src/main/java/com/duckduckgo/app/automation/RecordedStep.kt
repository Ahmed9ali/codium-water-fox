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

import org.json.JSONObject
import java.util.UUID

enum class StepKind { Field, Wait, CaptchaWait, ClickNext }

/**
 * One recorded action in a step-by-step skeleton.
 *
 * Single data class (not a sealed hierarchy) so it round-trips through the
 * same JSON shape and is editable inline in the HUD.
 */
data class RecordedStep(
    val id: String = UUID.randomUUID().toString(),
    val sequenceIndex: Int,
    val kind: StepKind,

    // Field-only
    val fieldType: String = "",           // canonical key, matches VaultRepository.normalizeFieldKey
    val displayLabel: String = "",
    val tag: String = "",                 // input / textarea / select
    val domType: String = "",             // HTML `type` attribute
    val name: String = "",
    val domId: String = "",
    val placeholder: String = "",
    val ariaLabel: String = "",
    val cssSelector: String = "",
    val xpath: String = "",

    // Wait / CaptchaWait
    val waitMs: Long = 0L,

    // ClickNext
    val buttonLabel: String = "",

    // Status
    val verified: Boolean = true,
    val addedAt: Long = System.currentTimeMillis(),
) {
    val isField get() = kind == StepKind.Field
    val isWait get() = kind == StepKind.Wait
    val isCaptchaWait get() = kind == StepKind.CaptchaWait
    val isClickNext get() = kind == StepKind.ClickNext

    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id)
        put("sequenceIndex", sequenceIndex)
        put("kind", kind.name)
        put("fieldType", fieldType)
        put("displayLabel", displayLabel)
        put("tag", tag)
        put("domType", domType)
        put("name", name)
        put("domId", domId)
        put("placeholder", placeholder)
        put("ariaLabel", ariaLabel)
        put("cssSelector", cssSelector)
        put("xpath", xpath)
        put("waitMs", waitMs)
        put("buttonLabel", buttonLabel)
        put("verified", verified)
        put("addedAt", addedAt)
    }

    companion object {
        fun fromJson(json: JSONObject): RecordedStep = RecordedStep(
            id = json.optString("id").ifBlank { UUID.randomUUID().toString() },
            sequenceIndex = json.optInt("sequenceIndex", 0),
            kind = runCatching { StepKind.valueOf(json.optString("kind", "Field")) }
                .getOrDefault(StepKind.Field),
            fieldType = json.optString("fieldType"),
            displayLabel = json.optString("displayLabel"),
            tag = json.optString("tag"),
            domType = json.optString("domType"),
            name = json.optString("name"),
            domId = json.optString("domId"),
            placeholder = json.optString("placeholder"),
            ariaLabel = json.optString("ariaLabel"),
            cssSelector = json.optString("cssSelector"),
            xpath = json.optString("xpath"),
            waitMs = json.optLong("waitMs", 0L),
            buttonLabel = json.optString("buttonLabel"),
            verified = json.optBoolean("verified", true),
            addedAt = json.optLong("addedAt", System.currentTimeMillis()),
        )
    }
}

/**
 * Maps the `looksLike` value produced by automation_scanner.js / the recorder
 * to a canonical VaultRepository key, plus a user-facing display label.
 *
 * Unknown values → "" (the HUD renders a red ✗ with a type-assignment chip).
 */
object FieldTypeMapper {

    data class Option(val canonical: String, val label: String)

    val assignOptions: List<Option> = listOf(
        Option("email", "Email"),
        Option("confirmemail", "Confirm Email"),
        Option("password", "Password"),
        Option("confirmpassword", "Confirm Password"),
        Option("phone", "Phone"),
        Option("confirmphone", "Confirm Phone"),
        Option("firstname", "First Name"),
        Option("middlename", "Middle Name"),
        Option("lastname", "Last Name"),
        Option("fullname", "Full Name"),
        Option("username", "Username"),
        Option("nickname", "Nickname"),
        Option("message", "Message"),
        Option("subject", "Subject"),
        Option("dob", "Date of Birth"),
        Option("gender", "Gender"),
        Option("address", "Address"),
        Option("city", "City"),
        Option("state", "State"),
        Option("zip", "Zip / Postal Code"),
        Option("country", "Country"),
        Option("countrycode", "Country Code"),
        Option("company", "Company"),
        Option("jobtitle", "Job Title"),
        Option("otp", "OTP / Verification Code"),
        Option("termscheckbox", "Terms / Agree Checkbox"),
        Option("text", "Text"),
    )

    private val looksLikeToCanonical: Map<String, String> = mapOf(
        "email" to "email",
        "confirmemail" to "confirmemail",
        "password" to "password",
        "confirmpassword" to "confirmpassword",
        "username" to "username",
        "firstname" to "firstname",
        "middlename" to "middlename",
        "lastname" to "lastname",
        "nickname" to "nickname",
        "fullname" to "fullname",
        "subject" to "subject",
        "message" to "message",
        "dob" to "dob",
        "gender" to "gender",
        "zip" to "zip",
        "city" to "city",
        "state" to "state",
        "country" to "country",
        "countrycode" to "countrycode",
        "address" to "address",
        "company" to "company",
        "jobtitle" to "jobtitle",
        "phone" to "phone",
        "confirmphone" to "confirmphone",
        "otp" to "otp",
        "termscheckbox" to "termscheckbox",
        "text" to "text",
    )

    private val canonicalToLabel: Map<String, String> =
        assignOptions.associate { it.canonical to it.label }

    fun normalizeLooksLike(looksLike: String): String =
        looksLikeToCanonical[looksLike.trim().lowercase()] ?: ""

    fun isKnown(canonical: String): Boolean = canonicalToLabel.containsKey(canonical)

    fun displayLabel(canonical: String): String =
        canonicalToLabel[canonical] ?: canonical.replaceFirstChar { it.uppercase() }

    fun fieldRowLabel(canonical: String, fallback: String): String = when {
        canonical == "termscheckbox" -> "Terms Checkbox"
        canonical.isNotBlank() -> "${displayLabel(canonical)} Input"
        fallback.isNotBlank() -> fallback
        else -> "Unknown field"
    }
}