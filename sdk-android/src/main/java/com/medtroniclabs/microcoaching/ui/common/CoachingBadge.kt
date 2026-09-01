package com.medtroniclabs.microcoaching.ui.common

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Red numeric badge overlaid on the coaching FAB.
 *
 * Shows [count] inside a filled circle. Hidden when [count] is 0. The count comes
 * from the caller; nothing in the SDK derives it from a pending-module query.
 *
 * @param count Number of pending items to show. Pass 0 to hide.
 */
@Composable
fun CoachingBadge(
    count: Int,
    modifier: Modifier = Modifier,
) {
    if (count <= 0) return

    val label = if (count > 9) "9+" else count.toString()

    Box(
        modifier = modifier
            .size(18.dp)
            .background(color = MaterialTheme.colorScheme.error, shape = CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            color = MaterialTheme.colorScheme.onError,
            fontSize = 10.sp,
            lineHeight = 10.sp,
        )
    }
}
