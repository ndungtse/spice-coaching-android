package com.medtroniclabs.microcoaching.ui.learn.modules.bottomsheet

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.fragment.app.FragmentManager
import androidx.lifecycle.ViewModelProvider
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.medtroniclabs.microcoaching.MicroCoachingSDK
import com.medtroniclabs.microcoaching.ui.SdkLocalizedTheme
import com.medtroniclabs.microcoaching.ui.flow.CoachingFlowActivity
import com.medtroniclabs.microcoaching.ui.learn.modules.QuickLearnViewModel

/**
 * Partial-height bottom sheet hosting [QuickLearnContent]. Surfaces a single
 * quiz question from the highest-priority morning module. After the CHW
 * answers, the feedback overlay auto-dismisses the sheet ~2 s later.
 *
 * Unlike [com.medtroniclabs.microcoaching.ui.chat.CoachingChatBottomSheet] this
 * sheet does **not** force `STATE_EXPANDED` — Material's default peek height
 * gives the partial-height look the designs ask for. The sheet is still
 * draggable to expand or dismiss.
 *
 * **Host usage:**
 * ```kotlin
 * QuickLearnBottomSheet.show(parentFragmentManager, chwId)
 * ```
 */
class QuickLearnBottomSheet : BottomSheetDialogFragment() {

    override fun getTheme(): Int =
        com.google.android.material.R.style.Theme_Material3_Light_BottomSheetDialog

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        val chwId = arguments?.getString(ARG_CHW_ID)
            ?: CoachingFlowActivity.FALLBACK_CHW_ID
        val viewModel = ViewModelProvider(
            this,
            QuickLearnViewModel.factory(requireContext().applicationContext, chwId),
        )[QuickLearnViewModel::class.java]

        return ComposeView(requireContext()).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
            setContent {
                // SdkLocalizedTheme applies SdkLocaleHelper.wrap so
                // stringResource(...) inside the sheet resolves from the
                // SDK-configured language, not the device locale.
                SdkLocalizedTheme {
                    QuickLearnContent(
                        viewModel = viewModel,
                        onDismiss = { dismissAllowingStateLoss() },
                    )
                }
            }
        }
    }

    companion object {
        const val TAG = "QuickLearnBottomSheet"
        private const val ARG_CHW_ID = "chw_id"

        fun show(fm: FragmentManager, chwId: String = MicroCoachingSDK.getInstance().currentCHWId ?: ""): String {
            val sheet = QuickLearnBottomSheet().apply {
                arguments = Bundle().apply { putString(ARG_CHW_ID, chwId) }
            }
            sheet.show(fm, TAG)
            return TAG
        }
    }
}
