package com.medtroniclabs.microcoaching.ui.learn.modules

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.medtroniclabs.microcoaching.MicroCoachingSDK
import com.medtroniclabs.microcoaching.R
import com.medtroniclabs.microcoaching.ui.common.SdkScreenHeader
import com.medtroniclabs.microcoaching.ui.learn.LearnModule
import com.medtroniclabs.microcoaching.ui.learn.modules.components.RefresherList
import com.medtroniclabs.microcoaching.ui.theme.SurfaceBackground

/**
 * Full-screen "Practice Zone" list of EVERY active refresher — reached from the
 * "See all" link on the Coaching tab's Refresher sub-tab. Mirrors
 * [AllModulesScreen] (top header + scrollable body), but renders the full-width
 * [RefresherList] tiles instead of a grid because refresher tiles are
 * list-shaped, not grid cells.
 *
 * Refreshers come straight from the shared
 * [com.medtroniclabs.microcoaching.domain.refresher.CoachingModuleStore] — the
 * same source the Refresher sub-tab's Practice Zone row reads — so the sub-tab
 * and this screen always agree. Tapping a tile runs the exact same path as the
 * sub-tab tiles ([onRefresherStart] then [onShowRefresherQuiz]), so the
 * bottom-sheet / quiz flow is unchanged.
 */
@Composable
fun RefreshersScreen(
    onRefresherStart: (LearnModule) -> Unit,
    onShowRefresherQuiz: (queueFamilyIds: List<String>) -> Unit,
    onBack: () -> Unit,
    onHome: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    val sdk = MicroCoachingSDK.getInstance()
    val refreshers by sdk.coachingModuleStore.refresherModules.collectAsState()
    // Sheet queue = the FULL active pool, so "Next refresher" still chains the whole
    // active set (matching the home screen) regardless of which tile is tapped.
    val poolFamilyIds = refreshers.map { it.moduleFamilyId }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(SurfaceBackground),
    ) {
        SdkScreenHeader(
            title = stringResource(R.string.coaching_section_practice_zone),
            onBack = onBack,
            onHome = onHome,
        )
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(vertical = 12.dp),
        ) {
            RefresherList(
                modules = refreshers,
                onSelect = { module ->
                    onRefresherStart(module)
                    onShowRefresherQuiz(poolFamilyIds)
                },
                showHeader = false,
                emptyMessage = stringResource(R.string.refresher_empty_none),
            )
            Spacer(Modifier.height(80.dp)) // Breathing room for FABs
        }
    }
}
