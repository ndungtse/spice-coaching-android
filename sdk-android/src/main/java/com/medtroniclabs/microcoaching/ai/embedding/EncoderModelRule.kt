package com.medtroniclabs.microcoaching.ai.embedding

/** What to do about a language model that is ready while the encoder is not. */
internal enum class EncoderWaitAction {
    /** A download is running; keep reporting the encoder phase. */
    HOLD,

    /** Nothing is fetching it — start a download, then keep reporting the phase. */
    SCHEDULE,

    /** Stop waiting and announce the language model. */
    RELEASE,
}

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

    /**
     * Whether the encoder belongs in the download plan.
     *
     * [EncoderVerdict.READY] counts: files already on disk are skipped by the worker but
     * still contribute to the total, so a bar that reads 40% means 40% of what the user
     * agreed to, not 40% of what happens to be missing.
     */
    fun EncoderVerdict.wantsEncoder(): Boolean =
        this == EncoderVerdict.READY || this == EncoderVerdict.DOWNLOAD

    /**
     * Whether the language model should keep waiting before the mode it unlocks is
     * announced as ready.
     *
     * The two halves ride one progress bar, so reporting the language model ready while the
     * encoder is still arriving would flip the label to "On this phone · simple words" with
     * 171 MB left to go, and the download would appear to finish twice.
     *
     * [gaveUp] is the release valve. The encoder improves retrieval; it is not the capability
     * the user asked for, so once it has terminally failed the language model stops waiting
     * on it. Without that, a weak connection could leave a fully downloaded model unusable
     * indefinitely.
     */
    fun shouldAwaitEncoder(wantsEncoder: Boolean, filesPresent: Boolean, gaveUp: Boolean): Boolean =
        wantsEncoder && !filesPresent && !gaveUp

    /**
     * Whether holding the mode back is still honest.
     *
     * [shouldAwaitEncoder] says the encoder is missing; this says whether anything is
     * actually going to fetch it. Reporting the encoder phase while no worker is running
     * leaves the bar at "Adding smarter search · 0%" indefinitely, which is what happens on
     * the upgrade path — a device whose language model was downloaded before dense retrieval
     * existed reaches the ready gate without any download in flight.
     *
     * [autoDownloadAllowed] is false for the PROVIDED and MANUAL strategies, where the SDK
     * must not fetch on its own. Waiting there would strand a language model that is on disk
     * and perfectly usable, so the encoder is simply skipped.
     */
    fun encoderWaitAction(
        awaiting: Boolean,
        downloadActive: Boolean,
        autoDownloadAllowed: Boolean,
    ): EncoderWaitAction = when {
        !awaiting -> EncoderWaitAction.RELEASE
        downloadActive -> EncoderWaitAction.HOLD
        autoDownloadAllowed -> EncoderWaitAction.SCHEDULE
        else -> EncoderWaitAction.RELEASE
    }
}
