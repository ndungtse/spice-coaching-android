package com.medtroniclabs.microcoaching.ui.learn

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.fragment.app.Fragment
import androidx.lifecycle.ViewModelProvider
import com.medtroniclabs.microcoaching.MicroCoachingSDK
import com.medtroniclabs.microcoaching.ui.SdkLocalizedTheme
import com.medtroniclabs.microcoaching.ui.flow.CoachingFlowActivity

/**
 * Exportable Fragment that embeds the full learn → quiz → result flow
 * directly in an XML host (e.g. SPICE's `fragment_container`).
 *
 * Navigation stays internal to the Fragment's hosted [ModuleReadyScreen].
 * When the CHW completes the quiz and taps "Back to HOME", [onFinish] is invoked
 * which pops this Fragment off the host's back stack.
 *
 * **Prefer [CoachingFlowActivity.launchLearnModule] in most cases** — it gives a
 * clean full-screen experience. Use this Fragment only when SPICE needs to embed
 * the learn flow inside an existing layout.
 *
 * **SPICE integration:**
 * ```kotlin
 * supportFragmentManager.beginTransaction()
 *     .replace(R.id.coaching_container, LearnFragment.newInstance())
 *     .addToBackStack(null)
 *     .commit()
 * ```
 */
class LearnFragment : Fragment() {

    private lateinit var viewModel: LearnViewModel

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val chwId = MicroCoachingSDK.getInstance().currentCHWId
            ?: CoachingFlowActivity.FALLBACK_CHW_ID
        viewModel = ViewModelProvider(
            this,
            LearnViewModel.factory(requireContext(), chwId),
        )[LearnViewModel::class.java]
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        return ComposeView(requireContext()).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
            setContent {
                SdkLocalizedTheme {
                    val uiState by viewModel.uiState.collectAsState()
                    ModuleReadyScreen(
                        uiState = uiState,
                        onModuleSelected = { module -> viewModel.selectModule(module) },
                        onStartLearning = {
                            viewModel.startLesson()
                            // Full flow requires CoachingFlowActivity; launch it from here.
                            CoachingFlowActivity.launchLearnModule(requireActivity())
                        },
                    )
                }
            }
        }
    }

    companion object {
        /** Create a new instance of [LearnFragment]. */
        fun newInstance(): LearnFragment = LearnFragment()
    }
}
