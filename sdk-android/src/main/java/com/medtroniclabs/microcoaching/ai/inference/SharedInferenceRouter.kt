package com.medtroniclabs.microcoaching.ai.inference

import android.util.Log
import com.medtroniclabs.microcoaching.MicroCoachingConfig

/**
 * Process-wide, ref-counted holder for the single [InferenceRouter].
 *
 * Two chat surfaces can be alive at once — a host-embedded
 * [com.medtroniclabs.microcoaching.ui.chat.CoachingChatFragment] plus the
 * [com.medtroniclabs.microcoaching.ui.chat.CoachingChatBottomSheet] — each with
 * its own `ChatViewModel`. With per-ViewModel routers that meant two engines
 * loading the same `.task`, which doubles model memory and crashes MediaPipe's
 * native engine. Ref-counting keeps exactly one router (and one loaded model)
 * alive while ANY chat surface is open, and unloads it when the last one closes.
 *
 * The bottom sheet's tag-based dedup still prevents sheet-vs-sheet duplicates;
 * this guards the cross-surface case it cannot see.
 */
internal object SharedInferenceRouter {

    private const val TAG = "SharedInferenceRouter"

    private var router: InferenceRouter? = null
    private var acquiredWith: MicroCoachingConfig? = null
    private var refCount = 0

    /**
     * Returns the shared router, creating it on first acquire. Every `acquire`
     * must be paired with exactly one [release] (ChatViewModel does this in
     * `onCleared`).
     *
     * If the SDK was rebuilt since the router was created (different config
     * identity) and no surface holds it, the stale router is released and a
     * fresh one is built against the new config. While surfaces DO hold it,
     * the existing router is reused — swapping a loaded engine under a live
     * chat is worse than serving it with the previous model settings.
     */
    @Synchronized
    fun acquire(config: MicroCoachingConfig): InferenceRouter {
        if (router != null && refCount == 0 && acquiredWith !== config) {
            Log.i(TAG, "Config changed since last use — releasing stale router before rebuild.")
            router?.release()
            router = null
        }
        val r = router ?: InferenceRouter(config).also {
            router = it
            acquiredWith = config
        }
        refCount++
        Log.d(TAG, "acquire — refCount=$refCount")
        return r
    }

    /** Drops one reference; unloads the engine when the last holder releases. */
    @Synchronized
    fun release() {
        if (refCount == 0) {
            Log.w(TAG, "release() without matching acquire — ignoring.")
            return
        }
        refCount--
        Log.d(TAG, "release — refCount=$refCount")
        if (refCount == 0) {
            router?.release()
            router = null
            acquiredWith = null
        }
    }
}
