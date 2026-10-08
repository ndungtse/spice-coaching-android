package com.medtroniclabs.microcoaching.ai.model

/** What a process start makes of the smarter-search files on disk. */
internal enum class SearchSetupVerdict {
    /** Both encoder files are complete: announce smarter search ready. */
    READY,

    /** A download is writing; it reports its own outcome. */
    LEAVE_IN_FLIGHT,

    /** The user paused a transfer; any partial is still on disk to resume onto. */
    RESUMABLE_PARTIAL,

    /** Nothing usable. A partial, if any, stays for the next download to resume onto. */
    IDLE,
}

/**
 * The smarter-search counterpart of [modelFileVerdict], used when the on-device rewrite is off
 * and the encoder is the whole download.
 *
 * Simpler than the language-model verdict: the encoder files have no container to validate, so
 * exact length is the whole completeness check, and a short file is a partial to resume, never
 * a corrupt file to delete.
 */
internal object SearchSetupRule {

    fun verdict(
        encoderComplete: Boolean,
        userPaused: Boolean,
        downloadWorkActive: Boolean,
    ): SearchSetupVerdict = when {
        encoderComplete -> SearchSetupVerdict.READY
        downloadWorkActive -> SearchSetupVerdict.LEAVE_IN_FLIGHT
        // A pause made before the first bytes landed is still a pause: read as idle, chat open
        // would restart the download.
        userPaused -> SearchSetupVerdict.RESUMABLE_PARTIAL
        else -> SearchSetupVerdict.IDLE
    }

    /**
     * Whether a language model on disk should be deleted. With the rewrite off nothing can load
     * it, so it is hundreds of megabytes the user gets nothing for.
     */
    fun removeLegacyLanguageModel(rewriteEnabled: Boolean, languageModelOnDisk: Boolean): Boolean =
        !rewriteEnabled && languageModelOnDisk
}
