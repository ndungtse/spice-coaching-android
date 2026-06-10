package com.medtroniclabs.microcoaching.ui.learn.modules.bottomsheet

import android.content.DialogInterface
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.fragment.app.FragmentManager
import androidx.lifecycle.ViewModelProvider
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.medtroniclabs.microcoaching.MicroCoachingSDK
import com.medtroniclabs.microcoaching.ui.SdkLocalizedTheme
import com.medtroniclabs.microcoaching.ui.flow.CoachingFlowActivity
import com.medtroniclabs.microcoaching.ui.learn.LearnViewModel
import com.medtroniclabs.microcoaching.ui.learn.modules.QuickLearnViewModel
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.launch

/**
 * Bottom sheet for the morning refresher experience.
 *
 * Supports two entry modes controlled by [EntryMode]:
 *
 * - [EntryMode.QUESTION_FIRST] (default, from [QuizRefresherCard] on modules screen):
 *   1 quiz question → lesson cards in sequence → Done / "Next Refresher"
 *
 * - [EntryMode.CARDS_FIRST] (from [MorningCard] on home screen):
 *   lesson cards in sequence → 1 quiz question → Done (no "Next Refresher")
 *
 * The [fromHomeScreen] flag suppresses the "Next Refresher" CTA when true.
 */
class RefresherBottomSheet : BottomSheetDialogFragment() {

    enum class EntryMode { QUESTION_FIRST, CARDS_FIRST }

    override fun getTheme(): Int =
        com.google.android.material.R.style.Theme_Material3_Light_BottomSheetDialog

    override fun onCreateDialog(savedInstanceState: Bundle?) =
        super.onCreateDialog(savedInstanceState).apply {
            (this as? BottomSheetDialog)?.behavior?.apply {
                state = BottomSheetBehavior.STATE_EXPANDED
                skipCollapsed = true
            }
        }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        val chwId = arguments?.getString(ARG_CHW_ID) ?: CoachingFlowActivity.FALLBACK_CHW_ID
        val fromHomeScreen = arguments?.getBoolean(ARG_FROM_HOME_SCREEN, false) ?: false
        val entryModeName = arguments?.getString(ARG_ENTRY_MODE) ?: EntryMode.QUESTION_FIRST.name
        val entryMode = EntryMode.valueOf(entryModeName)
        val targetModuleFamilyId = arguments?.getString(ARG_TARGET_MODULE_FAMILY_ID)

        val viewModel = ViewModelProvider(
            this,
            QuickLearnViewModel.factory(requireContext().applicationContext, chwId),
        )[QuickLearnViewModel::class.java]

        return ComposeView(requireContext()).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
            setContent {
                SdkLocalizedTheme {
                    RefresherContent(
                        viewModel = viewModel,
                        fromHomeScreen = fromHomeScreen,
                        entryMode = entryMode,
                        targetModuleFamilyId = targetModuleFamilyId,
                        onDismiss = { dismissAllowingStateLoss() },
                        modifier = Modifier.fillMaxSize(),
                    )
                }
            }
        }
    }

    override fun onDismiss(dialog: DialogInterface) {
        // Every dismissal path (Done CTA, swipe-down, tap-outside) funnels here.
        // Three surfaces need refreshing after a successful quiz:
        //   1) Refresher tile + QuizRefresherCard on ModulesScreen — driven by
        //      the ACTIVITY-scoped LearnViewModel (the one ModulesScreen
        //      observes), NOT `quickLearnVm.learnViewModel` (that's a
        //      fragment-scoped instance about to be destroyed with the sheet).
        //   2) The SPICE-side MorningCard — driven by SDK.morningModules,
        //      refiltered by sdk.refilterMorningModules.
        //   3) The deferred outbound+inbound sync (events were written to
        //      Room during the quiz with `deferSync = true`; here is where we
        //      actually push them and pull the updated partial-completions).
        val sdk = MicroCoachingSDK.getInstance()
        val chwId = sdk.currentCHWId.orEmpty()
        val activityVm = ViewModelProvider(
            requireActivity(),
            LearnViewModel.factory(requireContext().applicationContext, chwId),
        )[LearnViewModel::class.java]
        activityVm.refreshModuleCounts()
        if (chwId.isNotBlank()) {
            MainScope().launch { sdk.refilterMorningModules(chwId) }
        }
        sdk.flushTelemetryNow()
        sdk.syncCoordinator.triggerNow()
        super.onDismiss(dialog)
    }

    companion object {
        const val TAG = "RefresherBottomSheet"
        private const val ARG_CHW_ID = "chw_id"
        private const val ARG_FROM_HOME_SCREEN = "from_home_screen"
        private const val ARG_ENTRY_MODE = "entry_mode"
        private const val ARG_TARGET_MODULE_FAMILY_ID = "target_module_family_id"

        fun show(
            fm: FragmentManager,
            chwId: String = MicroCoachingSDK.getInstance().currentCHWId ?: "",
            fromHomeScreen: Boolean = false,
            entryMode: EntryMode = EntryMode.QUESTION_FIRST,
            targetModuleFamilyId: String? = null,
        ): String {
            val sheet = RefresherBottomSheet().apply {
                arguments = Bundle().apply {
                    putString(ARG_CHW_ID, chwId)
                    putBoolean(ARG_FROM_HOME_SCREEN, fromHomeScreen)
                    putString(ARG_ENTRY_MODE, entryMode.name)
                    targetModuleFamilyId?.let { putString(ARG_TARGET_MODULE_FAMILY_ID, it) }
                }
            }
            sheet.show(fm, TAG)
            return TAG
        }
    }
}
