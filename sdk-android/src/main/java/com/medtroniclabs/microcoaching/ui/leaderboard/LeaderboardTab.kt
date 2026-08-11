package com.medtroniclabs.microcoaching.ui.leaderboard

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.medtroniclabs.microcoaching.ui.leaderboard.components.AchievementBanner
import com.medtroniclabs.microcoaching.ui.leaderboard.components.LeaderboardGroupHeader
import com.medtroniclabs.microcoaching.ui.leaderboard.components.LeaderboardPodium
import com.medtroniclabs.microcoaching.ui.leaderboard.components.LeaderboardRow
import com.medtroniclabs.microcoaching.ui.leaderboard.components.TimeFilterTabs

/**
 * SK leaderboard tab: time filter, group header, podium, ranked list, achievement banner.
 *
 * Dormant since the sub-tab split.
 */
@Deprecated("Dormant: replaced by BadgesTab on the SK home. Package retained pending the final leaderboard decision — see docs/_coaching/01_navigation_and_screens.md")
@Composable
fun LeaderboardTab(
    uiState: LeaderboardUiState,
    onSelectPeriod: (LeaderboardPeriod) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
    ) {
        val period = (uiState as? LeaderboardUiState.Ready)?.period ?: LeaderboardPeriod.ALL_TIME
        TimeFilterTabs(selected = period, onSelect = onSelectPeriod, modifier = Modifier.fillMaxWidth())
        Spacer(Modifier.height(16.dp))

        when (uiState) {
            is LeaderboardUiState.Loading -> Box(
                modifier = Modifier.fillMaxWidth().padding(32.dp),
                contentAlignment = Alignment.Center,
            ) { CircularProgressIndicator() }

            is LeaderboardUiState.Error -> Text(
                text = uiState.message,
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodyMedium,
            )

            is LeaderboardUiState.Ready -> {
                val snapshot = uiState.snapshot
                LeaderboardGroupHeader(snapshot.group)
                Spacer(Modifier.height(16.dp))
                LeaderboardPodium(snapshot.entries.take(3))
                Spacer(Modifier.height(16.dp))
                if (snapshot.isLeading) {
                    AchievementBanner(snapshot.group.name)
                    Spacer(Modifier.height(16.dp))
                }
                snapshot.entries.forEach { entry ->
                    LeaderboardRow(entry)
                    Spacer(Modifier.height(8.dp))
                }
            }
        }
        Spacer(Modifier.height(80.dp)) // breathing room for the chat FAB
    }
}
