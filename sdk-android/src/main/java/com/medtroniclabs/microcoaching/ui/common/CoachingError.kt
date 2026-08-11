package com.medtroniclabs.microcoaching.ui.common

import androidx.annotation.StringRes
import com.medtroniclabs.microcoaching.R
import com.medtroniclabs.microcoaching.sync.SyncErrorKind
import java.io.IOException

/**
 * User-facing failure taxonomy.
 *
 * Mirrors [com.medtroniclabs.microcoaching.ai.retrieval.ChatRefusal]: a stable [outcomeKey]
 * for telemetry plus a localized [stringRes] for the CHW. Raw `Throwable.message` is
 * developer-facing (OkHttp prose like `Unable to resolve host "api…"`, or backend
 * `problem+json` detail) and is unlocalized — it belongs in logs and telemetry, never on a
 * screen a Bangla-speaking CHW reads.
 *
 * Classify once, at the point where the cause is actually known (the ViewModel or a sync
 * mapper); composables then render [stringRes] without re-deciding anything.
 */
sealed class CoachingError(val outcomeKey: String, @get:StringRes val stringRes: Int) {

    /** No usable connection — the device is offline, or the call failed with an I/O error. */
    data object Offline : CoachingError("error_offline", R.string.common_error_offline)

    /** Backend 5xx. Transient; retrying later is reasonable. */
    data object Server : CoachingError("error_server", R.string.common_error_server)

    /** Backend 4xx. Permanent from the CHW's side (auth, misconfiguration). */
    data object NotAllowed : CoachingError("error_not_allowed", R.string.common_error_not_allowed)

    /** The SDK was built without a backend URL — nothing can ever load. */
    data object NoBackend : CoachingError("error_no_backend", R.string.common_error_no_backend)

    /** Anything unclassified (deserialization, unexpected runtime failure). */
    data object Unknown : CoachingError("error_unknown", R.string.common_error_generic)

    /**
     * Drives the muted-vs-red tone split: an offline device is a *condition*, not a fault,
     * so it renders muted with a cloud icon rather than in error red.
     */
    val isOffline: Boolean get() = this === Offline

    companion object {
        /** Pure. Maps the sync layer's classifier onto user-facing copy. */
        fun from(kind: SyncErrorKind?, offline: Boolean): CoachingError = when {
            offline || kind == SyncErrorKind.NETWORK -> Offline
            kind == SyncErrorKind.HTTP_SERVER -> Server
            kind == SyncErrorKind.HTTP_CLIENT -> NotAllowed
            else -> Unknown
        }

        /**
         * Pure. For call sites that only have a caught exception. HTTP status is not
         * recoverable from a [Throwable] here — the SDK's Retrofit services return
         * `Response<T>` rather than throwing, so a non-successful response reaches us as a
         * plain [IllegalStateException] with prose. Those land as [Unknown]; the
         * [SyncErrorKind] overload is the one that can distinguish 4xx from 5xx.
         */
        fun from(t: Throwable, offline: Boolean): CoachingError = when {
            offline || t is IOException -> Offline
            else -> Unknown
        }
    }
}
