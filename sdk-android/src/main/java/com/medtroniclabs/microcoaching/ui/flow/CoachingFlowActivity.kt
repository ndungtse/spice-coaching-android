package com.medtroniclabs.microcoaching.ui.flow

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalContext
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.lifecycleScope
import androidx.navigation.compose.rememberNavController
import com.medtroniclabs.microcoaching.MicroCoachingSDK
import com.medtroniclabs.microcoaching.domain.telemetry.EventRecorder
import com.medtroniclabs.microcoaching.ui.SdkLocaleHelper
import com.medtroniclabs.microcoaching.ui.onboarding.OnboardingPrefs
import com.medtroniclabs.microcoaching.ui.theme.MicroCoachingTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.util.UUID

/**
 * SDK-owned `Activity` that hosts the full coaching flow.
 *
 * All screen transitions (coach mark → onboarding → module list → lesson → quiz → result)
 * are managed internally via [CoachingNavGraph]. SPICE never touches the back stack.
 *
 * **SPICE integration (recommended — passes the CHW identity):**
 * ```kotlin
 * // Minimal hook implementation in SPICE:
 * MicroCoachingSDK.getInstance().onHomeScreenShown(chwId = session.userId)
 * // SDK stores chwId internally and opens CoachingFlowActivity automatically.
 * ```
 *
 * **Direct launch (e.g. from FAB tap):**
 * ```kotlin
 * CoachingFlowActivity.launch(requireActivity(), chwId = session.userId)
 * ```
 */
class CoachingFlowActivity : FragmentActivity() {

    private lateinit var eventRecorder: EventRecorder

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val startRoute = resolveStartRoute()
        val chwId = intent.getStringExtra(EXTRA_CHW_ID)
            ?: MicroCoachingSDK.getInstance().currentCHWId
            ?: FALLBACK_CHW_ID

        val db = MicroCoachingSDK.getInstance().database
        eventRecorder = EventRecorder(
            dao = db.coachingEventDao(),
            sessionId = UUID.randomUUID().toString(),
            chwId = chwId,
        )
        lifecycleScope.launch(Dispatchers.IO) { eventRecorder.recordSessionStart() }

        setContent {
            // MicroCoachingTheme always renders the SDK light scheme; the
            // `config.uiTheme` selector is retained for future use (e.g. when
            // a fully-validated dark palette ships) but currently has no
            // effect on color scheme — see Theme.kt for the rationale.
            val langCtx = SdkLocaleHelper.wrap(
                this@CoachingFlowActivity,
                MicroCoachingSDK.getInstance().language,
            )
            CompositionLocalProvider(LocalContext provides langCtx) {
                MicroCoachingTheme {
                    val navController = rememberNavController()
                    CoachingNavGraph(
                        navController = navController,
                        startRoute = startRoute,
                        chwId = chwId,
                        // `SdkLocaleHelper.wrap()` calls
                        // `createConfigurationContext()` which produces a
                        // standalone ContextImpl with no link back to this
                        // activity. Pass the activity references directly so
                        // CoachingNavGraph doesn't have to guess them from
                        // LocalContext.
                        fragmentManager = supportFragmentManager,
                        viewModelStoreOwner = this@CoachingFlowActivity,
                        onFinish = { finish() },
                    )
                }
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        lifecycleScope.launch(Dispatchers.IO) { eventRecorder.recordSessionEnd() }
    }

    private fun resolveStartRoute(): String {
        val requestedRoute = intent.getStringExtra(EXTRA_START_ROUTE)
        if (requestedRoute != null) return requestedRoute
        val onboarded = OnboardingPrefs.isOnboarded(applicationContext)
        return if (onboarded) CoachingRoute.ModuleReady.route else CoachingRoute.OnboardingSlides.route
    }

    companion object {
        private const val EXTRA_START_ROUTE = "extra_start_route"
        const val EXTRA_CHW_ID = "extra_chw_id"

        /** Fallback ID used when SPICE hasn't supplied the CHW identity yet. */
        const val FALLBACK_CHW_ID = "unknown_chw"

        /**
         * Launch the full coaching flow (generic entry point).
         *
         * @param chwId The CHW's identifier from SPICE's auth session.
         */
        fun launch(context: Context, chwId: String = FALLBACK_CHW_ID) {
            context.startActivity(
                Intent(context, CoachingFlowActivity::class.java)
                    .putExtra(EXTRA_CHW_ID, chwId)
                    .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
            )
        }

        /**
         * Launch from the "Learn & Grow" button.
         *
         * Routing order:
         *   1. First launch (never seen onboarding slides) → CoachMark → slides → assessment → modules
         *   2. Slides seen but assessment not done → assessment → modules
         *   3. Everything done → module list directly
         *
         * @param chwId The CHW's identifier.
         */
        fun launchLearn(context: Context, chwId: String = FALLBACK_CHW_ID) {
            val startRoute = when {
                !OnboardingPrefs.isOnboarded(context) -> CoachingRoute.CoachMark.route
                else -> CoachingRoute.ModuleReady.route
            }
            context.startActivity(
                Intent(context, CoachingFlowActivity::class.java)
                    .putExtra(EXTRA_START_ROUTE, startRoute)
                    .putExtra(EXTRA_CHW_ID, chwId)
                    .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
            )
        }

        /**
         * Launch directly to the learn module list, bypassing onboarding and assessment.
         *
         * @param chwId The CHW's identifier.
         */
        fun launchLearnModule(context: Context, chwId: String = FALLBACK_CHW_ID) {
            context.startActivity(
                Intent(context, CoachingFlowActivity::class.java)
                    .putExtra(EXTRA_START_ROUTE, CoachingRoute.ModuleReady.route)
                    .putExtra(EXTRA_CHW_ID, chwId)
                    .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
            )
        }
    }
}
