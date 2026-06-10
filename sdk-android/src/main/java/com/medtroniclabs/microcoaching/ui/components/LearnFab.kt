package com.medtroniclabs.microcoaching.ui.components

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
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.medtroniclabs.microcoaching.R
import com.medtroniclabs.microcoaching.ui.SdkLocalizedTheme
import com.medtroniclabs.microcoaching.ui.common.CoachingBadge
import com.medtroniclabs.microcoaching.ui.theme.MicroCoachingTheme

/**
 * Floating action button for the **Learn & grow** entry point (UC-1).
 *
 * Designed to be placed in the bottom-left corner of the host's home screen.
 * On tap the host launches `CoachingFlowActivity.launchLearn(context, chwId)`,
 * which routes to onboarding (first install only) → modules list → module
 * detail → quiz.
 *
 * The optional [badgeCount] surfaces pending modules in the corner of the FAB;
 * pass 0 to hide. In Phase 3 the count is host-driven (host observes
 * `MicroCoachingSDK` learning paths and computes the count); a future phase
 * may move this into a SDK-driven StateFlow.
 *
 * **Host integration:**
 * ```kotlin
 * binding.learnFabComposeView.setContent {
 *     MicroCoachingTheme {
 *         LearnFab(onClick = {
 *             CoachingFlowActivity.launchLearn(requireContext(), chwId)
 *         })
 *     }
 * }
 * ```
 */
@Composable
fun LearnFab(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    badgeCount: Int = 0,
) {
    SdkLocalizedTheme {
        Box(modifier = modifier) {
            FloatingActionButton(
                onClick = onClick,
                containerColor = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary,
                elevation = FloatingActionButtonDefaults.elevation(defaultElevation = 6.dp),
            ) {
                Icon(
                    imageVector = Icons.Default.School,
                    contentDescription = stringResource(R.string.fab_open_learn),
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
}

@Preview(showBackground = true)
@Composable
private fun LearnFabPreview() {
    MicroCoachingTheme {
        LearnFab(onClick = {}, badgeCount = 2)
    }
}
