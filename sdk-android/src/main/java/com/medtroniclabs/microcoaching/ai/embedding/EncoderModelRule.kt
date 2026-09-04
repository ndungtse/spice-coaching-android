package com.medtroniclabs.microcoaching.ai.embedding

/** What should happen to the query encoder on this device, and why. */
internal enum class EncoderVerdict {
    /** Both files are on disk and this device may load them. */
    READY,

    /** Eligible, nothing on disk — schedule the download. */
    DOWNLOAD,

    /** `enableDenseRetrieval` is off, so the dense branch never runs. */
    SKIPPED_FLAG_OFF,

    /** Below the RAM tier that can hold the encoder. */
    SKIPPED_LOW_END,

    /** The model repo is gated and the host supplied no Hugging Face token. */
    BLOCKED_NO_TOKEN,
}

/**
 * The gate in front of a 171 MB download, kept pure so the reasons are testable
 * without a device.
 *
 * Order matters: the flag is checked before the tier, and the tier before what is on
 * disk. A file already present is not permission to load it — the encoder's ~110 MB
 * resident cost is unaffordable below the 3 GB tier whether or not the download
 * already happened on some other build.
 */
internal object EncoderModelRule {

    fun evaluate(
        enableDenseRetrieval: Boolean,
        isLowEndDevice: Boolean,
        filesPresent: Boolean,
        hasAccessToken: Boolean,
    ): EncoderVerdict = when {
        !enableDenseRetrieval -> EncoderVerdict.SKIPPED_FLAG_OFF
        isLowEndDevice -> EncoderVerdict.SKIPPED_LOW_END
        filesPresent -> EncoderVerdict.READY
        !hasAccessToken -> EncoderVerdict.BLOCKED_NO_TOKEN
        else -> EncoderVerdict.DOWNLOAD
    }
}
