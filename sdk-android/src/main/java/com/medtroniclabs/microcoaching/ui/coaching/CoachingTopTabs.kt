package com.medtroniclabs.microcoaching.ui.coaching

import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import com.medtroniclabs.microcoaching.ui.theme.SpiceBlue
import androidx.compose.ui.text.font.FontWeight

/**
 * Coaching-home top tabs (Coaching | Leaderboard, or Coaching | Dashboard).
 * SpiceBlue background with a white underline indicator that slides between tabs;
 * the focused label is white, the others low-opacity white.
 */
@Composable
fun CoachingTopTabs(
    labels: List<String>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    TabRow(
        selectedTabIndex = selectedIndex,
        modifier = modifier,
        containerColor = SpiceBlue,
        contentColor = Color.White, // drives the default (animated) underline + ripple
    ) {
        labels.forEachIndexed { index, label ->
            Tab(
                selected = selectedIndex == index,
                onClick = { onSelect(index) },
                selectedContentColor = Color.White,
                unselectedContentColor = Color.White.copy(alpha = 0.6f),
                text = { 
                    Text(
                        label,
                        fontWeight = if (selectedIndex == index) FontWeight.Bold else null
                    )
                },
           
            )
        }
    }
}
