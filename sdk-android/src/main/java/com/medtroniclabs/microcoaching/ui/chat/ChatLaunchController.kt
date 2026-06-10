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
 *   - **Model missing** → "Download AI Model" CTA with the ~600 MB size warning
 *     (effectively the confirm dialog — the CHW must explicitly tap to start)
 *   - **Download in flight** → progress UI (chat_plan.md §D)
 *
 * Why a single entry point? Before this lived in two places: SPICE's
 * `HomeScreenFragment` (Dialog + Toast) and the SDK's coaching-flow FAB
 * (Toast only). Both should converge so the chat sheet is the *only* place
 * where download progress is visible — removing the "tap Download → see Toast
 * → tap FAB again → see Download button with no progress" race called out in
 * QA. The race itself is fixed in [ChatViewModel.currentModelNotReadyState];
 * this controller just makes sure every entry point arrives at the right
 * surface.
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
