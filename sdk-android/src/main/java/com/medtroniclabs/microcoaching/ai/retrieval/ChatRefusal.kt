package com.medtroniclabs.microcoaching.ai.retrieval

import android.content.Context
import com.medtroniclabs.microcoaching.R

/**
 * Canned-refusal taxonomy used by chat_plan.md §B4 layers L1, L2, L4, L5.
 *
 * Strings live in `res/values/strings.xml` + `res/values-bn/strings.xml` so the
 * clinical reviewer can adjust the wording without touching code. Every refusal
 * is recorded as a telemetry row (event_family=it-help, validator_status=fail)
 * with [outcomeKey] in the payload so we can mine refusal patterns post-pilot.
 */
sealed class ChatRefusal(
    val outcomeKey: String,
    private val stringRes: Int,
) {
    object Scope : ChatRefusal("refused_scope", R.string.chat_refusal_scope)
    object NoGround : ChatRefusal("refused_no_ground", R.string.chat_refusal_no_ground)
    object Unsafe : ChatRefusal("refused_unsafe", R.string.chat_refusal_unsafe)

    /** Resolve the Bangla / English message for this refusal. */
    fun message(context: Context): String = context.getString(stringRes)
}
