package com.medtroniclabs.microcoaching.ui.chat

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.fragment.app.Fragment
import com.medtroniclabs.microcoaching.ui.theme.MicroCoachingTheme

/**
 * Exportable AI coaching chat Fragment.
 *
 * Uses Jetpack Compose internally via [ComposeView], making it embeddable
 * in SPICE's existing XML-based layouts without any Compose migration.
 *
 * SDK is DI-framework-agnostic — no Hilt required. The Fragment hosts a
 * [CoachingChatSurface] composable which owns the ViewModel, voice controller
 * resolution, and `ChatScreen` rendering — the same surface used by
 * [CoachingChatBottomSheet] so both embed paths stay in sync.
 *
 * **SPICE integration — embed in any Activity or Fragment:**
 * ```kotlin
 * supportFragmentManager.beginTransaction()
 *     .add(R.id.coaching_container,
 *          CoachingChatFragment.newInstance(patientId = patientTrackId.toString()))
 *     .commit()
 * ```
 *
 * **With patient context (for UC-2 Apply):**
 * ```kotlin
 * CoachingChatFragment.newInstance(
 *     patientId = patientTrackId.toString(),
 *     systemContext = "Focus on hypertension medication adherence counseling.",
 * )
 * ```
 *
 * The header's close icon is hidden in this embed path — the host already
 * controls dismissal (e.g. by popping the fragment). For a self-dismissing
 * sheet UX use [CoachingChatBottomSheet] instead.
 */
class CoachingChatFragment : Fragment() {

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        val patientId = arguments?.getString(ARG_PATIENT_ID).orEmpty()
        val systemContext = arguments?.getString(ARG_SYSTEM_CONTEXT).orEmpty()
        return ComposeView(requireContext()).apply {
            setViewCompositionStrategy(
                ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed,
            )
            setContent {
                MicroCoachingTheme {
                    CoachingChatSurface(
                        patientId = patientId,
                        systemContext = systemContext,
                        onClose = {},
                        showCloseIcon = false,
                    )
                }
            }
        }
    }

    companion object {
        private const val ARG_PATIENT_ID = "patient_id"
        private const val ARG_SYSTEM_CONTEXT = "system_context"

        /**
         * Create a new instance of [CoachingChatFragment].
         *
         * @param patientId Anonymized patient ID (use patientTrackId from SPICE).
         *   Pass empty string for non-patient-specific coaching.
         * @param systemContext Optional coaching context / system prompt override.
         */
        fun newInstance(
            patientId: String = "",
            systemContext: String = "",
        ): CoachingChatFragment {
            return CoachingChatFragment().apply {
                arguments = Bundle().apply {
                    putString(ARG_PATIENT_ID, patientId)
                    putString(ARG_SYSTEM_CONTEXT, systemContext)
                }
            }
        }
    }
}
