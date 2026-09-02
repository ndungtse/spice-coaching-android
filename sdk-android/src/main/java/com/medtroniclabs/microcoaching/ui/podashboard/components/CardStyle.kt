package com.medtroniclabs.microcoaching.ui.podashboard.components

import androidx.compose.foundation.background
import androidx.compose.runtime.Composable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.material3.MaterialTheme

/**
 * Card surface with a subtle shadow — used across the PO dashboard so cards lift off the
 * recessed app surface. Composable because it reads the theme's surface token.
 */
@Composable
internal fun Modifier.poCard(corner: Dp = 12.dp): Modifier {
    val shape = RoundedCornerShape(corner)
    return this
        .shadow(elevation = 1.dp, shape = shape)
        .clip(shape)
        .background(MaterialTheme.colorScheme.surface)
}
