package com.medtroniclabs.microcoaching.ai.model

/** What should happen to the model file found on disk. */
internal enum class ModelFileVerdict {

    /** The file is usable — persist the ready flag and emit [ModelState.Ready]. */
    ADOPT_READY,

    /** The persisted ready flag names a file that is gone — drop the flag, change nothing else. */
    CLEAR_STALE_FLAG,

    /** A worker is still writing this file. Touching it would corrupt a healthy transfer. */
    LEAVE_IN_FLIGHT,

    /** A paused transfer's partial. Keep the bytes so the resume can continue from them. */
    RESUMABLE_PARTIAL,

    /** The bytes are wrong and nothing is coming to fix them — delete and report corruption. */
    DELETE_CORRUPT,

    /** Nothing on disk and no flag to reconcile. */
    NO_OP,
}

/**
 * Decides the fate of a model file from the state around it, with no file or preference access
 * of its own.
 *
 * The hard part is that an incomplete file and a corrupt file are byte-for-byte
 * indistinguishable: a `.task` bundle keeps its central directory at the end, so every
 * partial download fails the structural check by definition. Only the surrounding state says
 * whether more bytes are coming, which is why [downloadWorkActive] and [userPaused] are
 * inputs rather than something this decision could infer.
 *
 * [userPaused] is the load-bearing one. Pausing cancels the WorkManager job to stop network
 * activity, so after a process restart the work reads as finished while the partial sits on
 * disk looking exactly like a failed download. Without a durable record of the pause, the
 * only available reading is corruption, and the partial gets deleted along with whatever
 * progress it held.
 *
 * @param structurallyValid whether the file opens as a complete bundle.
 * @param downloadWorkActive whether a worker may still be writing to it.
 * @param userPaused whether the user paused a transfer that has not since been cancelled.
 */
internal fun modelFileVerdict(
    prefsReady: Boolean,
    fileExists: Boolean,
    structurallyValid: Boolean,
    downloadWorkActive: Boolean,
    userPaused: Boolean,
): ModelFileVerdict {
    if (!fileExists) {
        return if (prefsReady) ModelFileVerdict.CLEAR_STALE_FLAG else ModelFileVerdict.NO_OP
    }
    // Adoption is decided by the file itself, so a sideloaded model and one this manager
    // downloaded reach Ready by the same route regardless of what the flag says.
    if (structurallyValid) return ModelFileVerdict.ADOPT_READY
    if (downloadWorkActive) return ModelFileVerdict.LEAVE_IN_FLIGHT
    if (userPaused) return ModelFileVerdict.RESUMABLE_PARTIAL
    return ModelFileVerdict.DELETE_CORRUPT
}
