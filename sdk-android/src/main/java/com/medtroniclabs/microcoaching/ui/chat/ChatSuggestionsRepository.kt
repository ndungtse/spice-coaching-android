package com.medtroniclabs.microcoaching.ui.chat

import android.content.Context
import android.content.SharedPreferences
import com.medtroniclabs.microcoaching.Language
import com.medtroniclabs.microcoaching.R
import com.medtroniclabs.microcoaching.ui.SdkLocaleHelper

/**
 * Backs the seed-suggestion chips above the chat input.
 *
 * Reads the curated `R.array.chat_seed_questions` resource — five hand-
 * picked clinical questions in EN + BN parallel — and shuffles the remaining
 * unused entries on every call. Tapping a chip persists its English text in
 * SharedPreferences so we don't re-offer the same prompt to the same CHW.
 *
 * Once all five have been used the chip row stays empty until the prefs
 * file is cleared (typically app-data wipe). That's the strict reading of
 * "if a user used a suggestion we don't have to show it again" — easy to
 * relax later by adding a `resetIfAllUsed()` branch in [nextBatch].
 */
internal class ChatSuggestionsRepository(private val appContext: Context) {

    private val prefs: SharedPreferences =
        appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /** Loaded once per repo instance — the array is bundled, not network-fetched. */
    private val allSuggestions: List<SuggestedQuestion> by lazy { loadAll() }

    /**
     * Returns the unused-and-shuffled list. Caller renders all entries; the
     * `SuggestionRow` composable already lives inside a `LazyRow`, so the
     * full set (up to 5) is fine.
     */
    fun nextBatch(): List<SuggestedQuestion> {
        val used = prefs.getStringSet(KEY_USED, emptySet()).orEmpty()
        return allSuggestions
            .filter { it.question !in used }
            .shuffled()
    }

    /** Persists the English text of a tapped suggestion so it never re-appears. */
    fun markUsed(suggestion: SuggestedQuestion) {
        if (suggestion.question.isBlank()) return
        val existing = prefs.getStringSet(KEY_USED, emptySet()).orEmpty()
        if (suggestion.question in existing) return
        prefs.edit()
            .putStringSet(KEY_USED, existing + suggestion.question)
            .apply()
    }

    private fun loadAll(): List<SuggestedQuestion> {
        // Load BOTH locales explicitly so a Bengali-mode SDK still has the
        // English text on hand as the stable identifier (and vice versa).
        // SdkLocaleHelper.wrap returns a Configuration-scoped Context so the
        // resource lookup honours the requested locale regardless of system.
        val enCtx = SdkLocaleHelper.wrap(appContext, Language.ENGLISH)
        val bnCtx = SdkLocaleHelper.wrap(appContext, Language.BANGLA)
        val en = enCtx.resources.getStringArray(R.array.chat_seed_questions)
        val bn = bnCtx.resources.getStringArray(R.array.chat_seed_questions)
        return en.mapIndexed { i, q ->
            SuggestedQuestion(
                question = q,
                banglaQuestion = bn.getOrNull(i) ?: q,
                moduleFamilyId = null,
            )
        }
    }

    private companion object {
        const val PREFS_NAME = "microcoaching_chat_suggestions"
        const val KEY_USED = "used_question_keys"
    }
}
