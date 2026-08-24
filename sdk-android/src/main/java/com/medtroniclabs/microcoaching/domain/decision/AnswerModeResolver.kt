package com.medtroniclabs.microcoaching.domain.decision

import com.medtroniclabs.microcoaching.ai.model.LocalModelChoice

/** Which pipeline answers a chat message. */
enum class AnswerMode {

    /** Backend RAG over the full library. Requires connectivity and the user's preference. */
    ONLINE,

    /** On-device retrieval, with the small language model rewording the card it selects. */
    ON_DEVICE_ASSISTED,

    /**
     * On-device retrieval, serving the clinician-authored card text as written.
     *
     * The floor every other mode falls back to. Retrieval picks the same card here as in
     * [ON_DEVICE_ASSISTED] — the model rewords the result, it does not choose it — so this
     * mode gives up presentation, not accuracy.
     */
    ON_DEVICE_DIRECT,
}

/**
 * Everything the routing decision depends on, gathered at one instant.
 *
 * @param preferOnline the user's stored mode preference, independent of connectivity.
 * @param deviceEligible whether the hardware can host the model at all.
 * @param modelReady whether a validated model file is present.
 * @param engineLoaded whether the inference engine currently holds that file. Separate from
 *   [modelReady] because a present, valid file can still fail to load, and a mode that
 *   promises rewording without a live engine has nothing to reword with.
 */
data class AnswerModeInputs(
    val preferOnline: Boolean,
    val networkAvailable: Boolean,
    val deviceEligible: Boolean,
    val choice: LocalModelChoice,
    val modelReady: Boolean,
    val engineLoaded: Boolean,
)

/**
 * Resolves how the next message will be answered.
 *
 * Exists as one pure function because the inputs are independent and the combinations are
 * many: connectivity, a stored preference, hardware eligibility, consent, a file, and an
 * engine. Re-deriving that at each call site is what lets the header disagree with the router,
 * or a message reach an engine that was unloaded a moment earlier. Deliberately free of
 * Android and config types so every combination can be asserted directly.
 *
 * The ladder is strict: each rung names an additional thing that must be true, and anything
 * unmet falls to [AnswerMode.ON_DEVICE_DIRECT], which needs nothing but the local index.
 */
fun resolveAnswerMode(inputs: AnswerModeInputs): AnswerMode = when {
    // Connectivity, not just the preference: a stored "online" is honoured the moment a
    // network returns, and ignored while there isn't one.
    inputs.preferOnline && inputs.networkAvailable -> AnswerMode.ONLINE

    inputs.deviceEligible &&
        inputs.choice == LocalModelChoice.ENABLED &&
        inputs.modelReady &&
        inputs.engineLoaded -> AnswerMode.ON_DEVICE_ASSISTED

    else -> AnswerMode.ON_DEVICE_DIRECT
}
