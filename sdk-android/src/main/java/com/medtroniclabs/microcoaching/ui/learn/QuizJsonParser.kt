package com.medtroniclabs.microcoaching.ui.learn

import android.util.Log
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

private const val TAG = "QuizJsonParser"

/**
 * Parse the raw `quiz_json` blob stored on `module_cache.quiz_json` into a
 * typed [QuizQuestion] list. Used by both [LearnViewModel] (full quiz flow)
 * and the v0.3.2 Quick learn banner (single-question taste).
 *
 * @param lang ISO-639-1 code for the preferred language ("bn" or "en").
 *   Fields are read as `question_$lang`, falling back to `question_bn` when the
 *   requested lang field is absent. `"bn"` is the default because Bangla is the
 *   primary content language for all current deployments.
 *
 * Backend ships each row as `ModuleQuizQuestionPayload` — see
 * `coaching-platform`'s OpenAPI spec. The `id` field is the question version
 * UUID and is stable enough for local-only telemetry within a single module
 * version.
 *
 * `options_$lang` is flexible on the wire — the schema declares `items: {}`
 * (any JSON value). Backend pilot data ships either:
 * - **Strings**: `["এ", "বি", "সি"]`
 * - **Objects**: `[{"label":"এ","value":0},{"label":"বি","value":1}]`
 *
 * This parser accepts both. Malformed rows are logged and skipped — empty
 * list is the worst case.
 */
internal fun parseInlineQuiz(quizJson: String, lang: String = "bn"): List<QuizQuestion> {
    val arr = try {
        Json.parseToJsonElement(quizJson).jsonArray
    } catch (e: Exception) {
        Log.w(TAG, "quiz_json is not a JSON array (len=${quizJson.length}): ${e.message}")
        return emptyList()
    }

    return arr.mapIndexedNotNull { idx, el ->
        runCatching {
            val obj = el.jsonObject
            val id = obj["id"]?.jsonPrimitive?.contentOrNullSafe()
                ?: error("missing or null `id`")
            val text = obj["question_$lang"]?.jsonPrimitive?.contentOrNullSafe()
                ?: obj["question_bn"]?.jsonPrimitive?.contentOrNullSafe() ?: ""
            val options = (obj["options_$lang"] ?: obj["options_bn"])?.jsonArray
                ?.mapNotNull { it.toOptionLabel() }
                ?: emptyList()
            val correct = obj["correct_indices"]?.jsonArray
                ?.firstOrNull()?.jsonPrimitive?.content?.toIntOrNull() ?: 0
            val explanation = obj["explanation_$lang"]?.jsonPrimitive?.contentOrNullSafe()
                ?: obj["explanation_bn"]?.jsonPrimitive?.contentOrNullSafe() ?: ""
            val caseSetup = obj["case_setup_$lang"]?.jsonPrimitive?.contentOrNullSafe()
                ?: obj["case_setup_bn"]?.jsonPrimitive?.contentOrNullSafe() ?: ""

            QuizQuestion(
                id = id,
                questionText = text,
                answers = options,
                correctIndex = correct,
                explanation = explanation,
                caseSetup = caseSetup,
            )
        }.onFailure { e ->
            Log.w(TAG, "Failed to parse quiz row #$idx: ${e.message}")
        }.getOrNull()
    }
}

/**
 * Normalise an `options_bn` entry to its display label.
 *
 * Backend may ship either a string ("কখনই না") or an object
 * (`{"label":"কখনই না","value":1}`). We accept both shapes so a host with
 * either pilot configuration renders correctly.
 */
private fun JsonElement.toOptionLabel(): String? = when (this) {
    is JsonPrimitive -> contentOrNullSafe()
    is JsonObject -> {
        // Prefer "label" (current shape), fall back to "text" or "answer" for
        // future schema flex.
        this["label"]?.jsonPrimitive?.contentOrNullSafe()
            ?: this["text"]?.jsonPrimitive?.contentOrNullSafe()
            ?: this["answer"]?.jsonPrimitive?.contentOrNullSafe()
    }
    else -> null
}

private fun JsonPrimitive.contentOrNullSafe(): String? =
    if (this is JsonNull) null else content
