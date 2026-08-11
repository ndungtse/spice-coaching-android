package com.medtroniclabs.microcoaching.ui.fab

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.School
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.FloatingActionButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.medtroniclabs.microcoaching.R
import com.medtroniclabs.microcoaching.ui.common.CoachingBadge

/**
 * Coaching FAB with a numeric badge indicating pending modules.
 *
 * The FAB is green (brand colour). The badge is red. In Phase 0.5 the badge
 * count is hardcoded to 1; Phase 3 will drive it from a ViewModel.
 *
 * @param badgeCount Number of pending modules. Pass 0 to hide the badge.
 * @param onClick Invoked when the FAB is tapped.
 */
@Composable
fun CoachingFab(
    badgeCount: Int = 1,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(modifier = modifier) {
        FloatingActionButton(
            onClick = onClick,
            containerColor = MaterialTheme.colorScheme.primary,
            contentColor = MaterialTheme.colorScheme.onPrimary,
            elevation = FloatingActionButtonDefaults.elevation(defaultElevation = 6.dp),
        ) {
            Icon(
                imageVector = Icons.Default.School,
                contentDescription = stringResource(R.string.fab_open_coaching),
                modifier = Modifier.size(28.dp),
            )
        }

        if (badgeCount > 0) {
            CoachingBadge(
                count = badgeCount,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .offset(x = 6.dp, y = (-6).dp),
            )
        }
    }
}
