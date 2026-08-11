package com.medtroniclabs.microcoaching.ui.markdown.blocks

import androidx.compose.material3.HorizontalDivider
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.medtroniclabs.microcoaching.ui.markdown.MarkdownStyle

@Composable
internal fun HorizontalRuleBlock(
    style: MarkdownStyle,
    modifier: Modifier = Modifier,
) {
    HorizontalDivider(
        modifier = modifier,
        thickness = 1.dp,
        color = style.tableBorderColor,
    )
}
