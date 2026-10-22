package com.duckduckgo.app.automation

import org.json.JSONObject
import java.util.UUID

enum class StepKind { Field, Wait, CaptchaWait, ClickNext }

data class RecordedStep(
    val id: String = UUID.randomUUID().toString(),
    val sequenceIndex: Int,
    val kind: StepKind,

    // Field attributes
    val fieldType: String = "",
    val displayLabel: String = "",
    val tag: String = "",
    val domType: String = "",
    val name: String = "",
    val domId: String = "",
    val placeholder: String = "",
    val ariaLabel: String = "",
    val cssSelector: String = "",
    val xpath: String = "",
    val index: Int = -1, // New: absolute index fallback

    // Wait / Click
    val waitMs: Long = 0L,
    val buttonLabel: String = "",
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
        put("index", index)
        put("waitMs", waitMs)
        put("buttonLabel", buttonLabel)
        put("verified", verified)
        put("addedAt", addedAt)
    }

    companion object {
        fun fromJson(json: JSONObject): RecordedStep = RecordedStep(
            id = json.optString("id").ifBlank { UUID.randomUUID().toString() },
            sequenceIndex = json.optInt("sequenceIndex", 0),
            kind = runCatching { StepKind.valueOf(json.optString("kind", "Field")) }.getOrDefault(StepKind.Field),
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
            index = json.optInt("index", -1),
            waitMs = json.optLong("waitMs", 0L),
            buttonLabel = json.optString("buttonLabel"),
            verified = json.optBoolean("verified", true),
            addedAt = json.optLong("addedAt", System.currentTimeMillis()),
        )
    }
}

object FieldTypeMapper {
    data class Option(val canonical: String, val label: String)

    val assignOptions: List<Option> = listOf(
        Option("email", "Email"), Option("confirmemail", "Confirm Email"),
        Option("password", "Password"), Option("confirmpassword", "Confirm Password"),
        Option("phone", "Phone"), Option("confirmphone", "Confirm Phone"),
        Option("firstname", "First Name"), Option("middlename", "Middle Name"),
        Option("lastname", "Last Name"), Option("fullname", "Full Name"),
        Option("username", "Username"), Option("nickname", "Nickname"),
        Option("message", "Message"), Option("subject", "Subject"),
        Option("dob", "Date of Birth"), Option("gender", "Gender"),
        Option("address", "Address"), Option("city", "City"),
        Option("state", "State"), Option("zip", "Zip / Postal Code"),
        Option("country", "Country"), Option("countrycode", "Country Code"),
        Option("company", "Company"), Option("jobtitle", "Job Title"),
        Option("otp", "OTP / Verification Code"), Option("termscheckbox", "Terms / Agree Checkbox"),
        Option("text", "Text"),
    )

    private val looksLikeToCanonical: Map<String, String> = mapOf(
        "email" to "email", "confirmemail" to "confirmemail",
        "password" to "password", "confirmpassword" to "confirmpassword",
        "username" to "username", "firstname" to "firstname", "middlename" to "middlename",
        "lastname" to "lastname", "nickname" to "nickname", "fullname" to "fullname",
        "subject" to "subject", "message" to "message", "dob" to "dob", "gender" to "gender",
        "zip" to "zip", "city" to "city", "state" to "state", "country" to "country",
        "countrycode" to "countrycode", "address" to "address", "company" to "company",
        "jobtitle" to "jobtitle", "phone" to "phone", "confirmphone" to "confirmphone",
        "otp" to "otp", "termscheckbox" to "termscheckbox", "text" to "text",
    )

    private val canonicalToLabel: Map<String, String> = assignOptions.associate { it.canonical to it.label }

    fun normalizeLooksLike(looksLike: String): String = looksLikeToCanonical[looksLike.trim().lowercase()] ?: ""

    fun displayLabel(canonical: String): String = canonicalToLabel[canonical] ?: canonical.replaceFirstChar { it.uppercase() }

    fun fieldRowLabel(canonical: String, fallback: String): String = when {
        canonical == "termscheckbox" -> "Terms Checkbox"
        canonical.isNotBlank() -> "${displayLabel(canonical)} Input"
        fallback.isNotBlank() -> fallback
        else -> "Unknown field"
    }
}