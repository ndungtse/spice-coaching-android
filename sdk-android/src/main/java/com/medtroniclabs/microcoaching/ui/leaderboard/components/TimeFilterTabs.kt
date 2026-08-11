package com.medtroniclabs.microcoaching.ui.leaderboard.components

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.medtroniclabs.microcoaching.ui.common.SegmentedToggle
import com.medtroniclabs.microcoaching.ui.leaderboard.LeaderboardPeriod

/** All Time / This Month / This Week selector (a [SegmentedToggle] over the periods). */
@Composable
fun TimeFilterTabs(
    selected: LeaderboardPeriod,
    onSelect: (LeaderboardPeriod) -> Unit,
    modifier: Modifier = Modifier,
) {
    val periods = LeaderboardPeriod.entries
    SegmentedToggle(
        options = periods.map { stringResource(it.labelRes) },
        selectedIndex = periods.indexOf(selected),
        onSelect = { onSelect(periods[it]) },
        modifier = modifier,
    )
}
