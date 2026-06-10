package com.medtroniclabs.microcoaching.domain.validation

import com.medtroniclabs.microcoaching.ai.retrieval.GroundingChunk
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Validates AI-generated content before it is displayed to the CHW.
 *
 * Applies to BOTH online (backend Gemini) AND edge (on-device Gemma) responses.
 * On failure, [FallbackSelector] serves the pre-authored Bangla card instead.
 *
 * Block-list rules follow DDD v2 Section 7.9.
 */
class OutputValidator {

    data class ValidationResult(
        val isValid: Boolean,
        val failureReason: String? = null,
    )

    /** Validates a structured JSON coaching card response from the AI. */
    fun validate(jsonResponse: String): ValidationResult {
        if (jsonResponse.isBlank()) return ValidationResult(false, "empty_response")

        val obj = try {
            Json.parseToJsonElement(jsonResponse) as? JsonObject
                ?: return ValidationResult(false, "not_json_object")
        } catch (_: Exception) {
            return ValidationResult(false, "invalid_json")
        }

        val title = obj["title"]?.jsonPrimitive?.content ?: ""
        val body = obj["body"]?.jsonPrimitive?.content ?: ""
        val blocked = containsBlockedPhrase("$title $body".lowercase())
        return if (blocked != null) ValidationResult(false, "blocked:$blocked") else ValidationResult(true)
    }

    /** Validates a plain text fragment (e.g., edge-mode output before JSON wrapping). */
    fun validateText(text: String): ValidationResult {
        if (text.isBlank()) return ValidationResult(false, "empty_text")
        val blocked = containsBlockedPhrase(text.lowercase())
        return if (blocked != null) ValidationResult(false, "blocked:$blocked") else ValidationResult(true)
    }

    /**
     * L3 sentinel that the hardened chat system prompt asks Gemma to emit when it
     * cannot answer from the Reference content. Intercepted client-side so we serve
     * the canned BN refusal instead of leaking the sentinel to the CHW.
     */
    fun isNoGroundSentinel(response: String): Boolean =
        REFUSE_NO_GROUND_SENTINEL in response

    /**
     * Sentinel the open-scope system prompt asks Gemma to emit when the question
     * falls outside SPICE's clinical scope (weather, sports, etc.). Intercepted
     * client-side so the CHW sees the same canned scope-refusal copy used by the
     * L1 keyword classifier in Strict mode.
     */
    fun isOutOfScopeSentinel(response: String): Boolean =
        REFUSE_OUT_OF_SCOPE_SENTINEL in response

    /**
     * L4 validator for chat responses (chat_plan.md §B4).
     *
     * Stricter than [validateText] because the response is produced under a
     * hardened "use only the Reference content" directive. We accept drugs and
     * dosages **only** when the exact term also appears in one of the [candidates]
     * — that's the per-query allow-list. Anything outside the candidate set is
     * treated as fabricated.
     *
     * Length cap is enforced at the word level on the English Gemma output —
     * verbose replies are the strongest hallucination signal at 1B parameters.
     */
    fun validateChatResponse(
        response: String,
        candidates: List<GroundingChunk>,
        maxWords: Int = 250,
        allowFreeText: Boolean = false,
    ): ValidationResult {
        if (response.isBlank()) return ValidationResult(false, "empty_text")
        val lower = response.lowercase()

        // Reject the sentinel before any other check — it isn't user-facing content.
        if (REFUSE_NO_GROUND_SENTINEL in response) return ValidationResult(false, "no_ground_sentinel")

        // Length cap.
        val wordCount = response.split(WHITESPACE).count { it.isNotBlank() }
        if (wordCount > maxWords) return ValidationResult(false, "too_long:$wordCount")

        // Diagnostic phrases are always blocked (mirrors validateText / validate).
        DIAGNOSTIC_PHRASES.firstOrNull { it in lower }?.let {
            return ValidationResult(false, "diagnostic:$it")
        }

        // Drug / dosage block-list only applies to the grounded path, where the
        // hardened prompt told the model to use ONLY the reference text. In the
        // open-scope path there are no candidates, so the rule has no meaning —
        // the safety caveat baked into the system prompt is what carries us.
        if (!allowFreeText) {
            val candidateText = candidates.joinToString(" ") {
                (it.bodyEn.orEmpty() + " " + it.bodyBn.orEmpty() + " " +
                    it.titleEn.orEmpty() + " " + it.titleBn.orEmpty()).lowercase()
            }

            DRUG_NAMES.firstOrNull { drug -> drug in lower && drug !in candidateText }?.let {
                return ValidationResult(false, "drug_not_in_source:$it")
            }
            DOSAGE_PATTERNS.firstOrNull { dose -> dose in lower && dose !in candidateText }?.let {
                return ValidationResult(false, "dosage_not_in_source:$it")
            }
        }

        return ValidationResult(true)
    }

    private fun containsBlockedPhrase(lowercaseText: String): String? {
        DIAGNOSTIC_PHRASES.forEach { if (lowercaseText.contains(it)) return "diagnostic:$it" }
        DRUG_NAMES.forEach { if (lowercaseText.contains(it)) return "drug:$it" }
        DOSAGE_PATTERNS.forEach { if (lowercaseText.contains(it)) return "dosage:$it" }
        return null
    }

    companion object {

        /** Sentinel the L3 hardened prompt asks the model to emit when it can't ground its answer. */
        const val REFUSE_NO_GROUND_SENTINEL = "[[REFUSE_NO_GROUND]]"

        /** Sentinel the open-scope prompt asks the model to emit when the question is out of clinical scope. */
        const val REFUSE_OUT_OF_SCOPE_SENTINEL = "[[REFUSE_OUT_OF_SCOPE]]"

        private val WHITESPACE = Regex("""\s+""")

        private val DIAGNOSTIC_PHRASES = listOf(
            "you have ", "you are diagnosed", "your diagnosis is", "you suffer from",
            "patient has been diagnosed", "you are suffering from",
            "আপনার আছে", "রোগ নির্ণয়", "আপনার রোগ হয়েছে",
        )
        private val DRUG_NAMES = listOf(
            "metformin", "insulin", "amlodipine", "enalapril", "losartan",
            "aspirin", "nifedipine", "atenolol", "glibenclamide", "lisinopril",
            "hydrochlorothiazide", "furosemide", "methyldopa", "labetalol",
            "glimepiride", "ramipril", "valsartan", "bisoprolol",
        )
        private val DOSAGE_PATTERNS = listOf(
            " mg", " mcg", " ml", " tablet", " tablets",
            " dose ", " doses", " unit ", " units", " capsule", " capsules",
            "মিগ্রা", "মিলি", "ট্যাবলেট",
        )
    }
}
