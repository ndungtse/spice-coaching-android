package com.medtroniclabs.microcoaching.ui.chat

import android.util.Log
import androidx.fragment.app.FragmentActivity
import androidx.fragment.app.FragmentManager
import com.medtroniclabs.microcoaching.MicroCoachingSDK

/**
 * Centralised entry point for opening the CHW AI chat from any host surface.
 *
 * Two call sites share this logic:
 *   1. SPICE host's home-screen FAB
 *   2. The SDK's persistent FAB overlay inside the coaching navigation graph
 *
 * Behaviour: always opens [CoachingChatBottomSheet]. The sheet renders the
 * right UI based on the current [com.medtroniclabs.microcoaching.ai.model.ModelState]
 * it observes from [com.medtroniclabs.microcoaching.MicroCoachingSDK.modelManager]:
 *
 *   - **Model ready** → chat surface (message list, input, suggestion chips)
 *   - **Model missing** → "Download AI Model" CTA carrying the active variant's
 *     size (from [com.medtroniclabs.microcoaching.ai.model.ModelCatalog]); the CHW
 *     must explicitly tap to start
 *   - **Download in flight** → progress UI
 *
 * Why a single entry point? The chat sheet is the *only* place download progress
 * is visible, so every entry point must arrive there rather than at a Toast or a
 * dialog of its own. Chat itself does not wait on the model, so opening
 * mid-download is not a race.
 */
object ChatLaunchController {

    private const val TAG = "ChatLaunchController"

    @Suppress("UNUSED_PARAMETER")
    fun launchOrPromptDownload(
        activity: FragmentActivity,
        fragmentManager: FragmentManager,
    ) {
        if (!MicroCoachingSDK.isInitialized()) {
            Log.w(TAG, "SDK not initialized — cannot open chat")
            return
        }
        CoachingChatBottomSheet.show(fragmentManager)
    }
}
