package com.medtroniclabs.microcoaching.ui.learn.modules.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.medtroniclabs.microcoaching.ui.theme.SpiceBlueDark

/**
 * Section heading used by Refreshers / Training / Knowledge rows on the
 * v0.3.2 modules screen. Optional right-aligned "See all" link.
 */
@Composable
fun SectionHeader(
    title: String,
    modifier: Modifier = Modifier,
    seeAllLabel: String? = null,
    onSeeAllClick: (() -> Unit)? = null,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
        )
        if (seeAllLabel != null && onSeeAllClick != null) {
            Text(
                text = seeAllLabel,
                color = SpiceBlueDark,
                style = MaterialTheme.typography.labelLarge,
                modifier = Modifier.clickable(onClick = onSeeAllClick),
            )
        }
    }
}
