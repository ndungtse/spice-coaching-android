package com.medtroniclabs.microcoaching.ai.model

import com.medtroniclabs.microcoaching.ai.embedding.EncoderVerdict

/**
 * What the user's one on-device opt-in unlocks, and on which devices.
 *
 * There is one consent ([LocalModelChoice]). By default one thing sits behind it: the query
 * encoder, which makes offline search match what a question means as well as its words
 * ("smarter search"). With [com.medtroniclabs.microcoaching.MicroCoachingConfig.enableOnDeviceRewrite]
 * on, the same consent also covers the language model that rewords the served card ("simple
 * words"). Kept pure so every combination can be asserted without a device.
 */
internal object OnDeviceSetup {

    /** Whether the language model may be downloaded, loaded and used to answer. */
    fun rewriteActive(rewriteEnabled: Boolean, isLowEndDevice: Boolean, choice: LocalModelChoice): Boolean =
        rewriteEnabled && !isLowEndDevice && choice == LocalModelChoice.ENABLED

    /**
     * Whether this device can run the query encoder.
     *
     * Separate from the language model's 3 GB tier
     * ([com.medtroniclabs.microcoaching.domain.system.DeviceCapability.isLowEndDevice]): the
     * encoder holds about 110 MB while it runs, against about 1.7 GB for the language model, so
     * a phone too small for one can run the other.
     *
     * @param forceLowEnd [com.medtroniclabs.microcoaching.MicroCoachingConfig.forceLowEndMode]:
     *   true makes the device ineligible, false skips the RAM check (QA), null measures.
     * @param is64BitProcess required in every case: the SDK ships its native code for arm64-v8a
     *   only, so a 32-bit install would download the encoder and never load it.
     * @param minRamMb [com.medtroniclabs.microcoaching.MicroCoachingConfig.smarterSearchMinRamMb].
     */
    fun encoderDeviceEligible(
        forceLowEnd: Boolean?,
        is64BitProcess: Boolean,
        totalMemBytes: Long,
        minRamMb: Int,
    ): Boolean = is64BitProcess && when (forceLowEnd) {
        true -> false
        false -> true
        null -> totalMemBytes >= minRamMb.toLong() * 1024L * 1024L
    }

    /**
     * Whether a chat turn may use dense retrieval.
     *
     * Consent, not files on disk: a user who opts out but keeps the download has asked for
     * standard search, and an encoder that happens to be present must not overrule that.
     *
     * @param deviceEligible the result of [encoderDeviceEligible].
     */
    fun smarterSearchActive(denseEnabled: Boolean, deviceEligible: Boolean, choice: LocalModelChoice): Boolean =
        denseEnabled && deviceEligible && choice == LocalModelChoice.ENABLED

    /**
     * Whether this device may be offered the opt-in at all.
     *
     * With the rewrite on, the language model is the capability and the encoder an extra, so the
     * language model's RAM tier alone decides. With it off, the encoder is the whole download, so
     * anything that stops it (the dense flag, the encoder's device rule, a missing Hugging Face
     * token) leaves nothing to offer. [encoderVerdict] carries all three.
     */
    fun setupAvailable(rewriteEnabled: Boolean, isLowEndDevice: Boolean, encoderVerdict: EncoderVerdict): Boolean =
        if (rewriteEnabled) {
            !isLowEndDevice
        } else {
            encoderVerdict == EncoderVerdict.READY || encoderVerdict == EncoderVerdict.DOWNLOAD
        }

    /**
     * Whether opening chat should start the smarter-search download.
     *
     * Covers a phone whose user consented before the encoder could arrive: a "simple words"
     * download whose encoder half gave up, or one made before dense retrieval existed. Only
     * [ModelState.Idle] qualifies: anything in flight is already moving, Ready needs nothing, and
     * a failure waits for the user's retry instead of re-queuing on every open. A download the
     * user cancelled stays cancelled until they start it again.
     */
    fun scheduleOnChatOpen(
        rewriteEnabled: Boolean,
        setupEnabled: Boolean,
        state: ModelState,
        cancelledByUser: Boolean,
    ): Boolean = !rewriteEnabled && setupEnabled && !cancelledByUser && state is ModelState.Idle
}
