package com.medtroniclabs.microcoaching.ui.learn.modules.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * CSS-grid-style responsive column count, keyed off available width.
 * Minimum 2 columns; widens to 3 then 4 on larger screens (tablets, foldables,
 * landscape). Mirror these breakpoints in both the inline [ModuleGrid] (modules
 * screen) and the `LazyVerticalGrid` on `AllModulesScreen` so the two surfaces
 * track each other.
 */
fun moduleGridColumns(maxWidth: Dp): Int = when {
    maxWidth >= 840.dp -> 4
    maxWidth >= 600.dp -> 3
    else -> 2
}

fun moduleTileColumns(maxWidth: Dp): Int = when {
    maxWidth >= 1280.dp -> 4
    maxWidth >= 1024.dp -> 3
    maxWidth >= 600.dp -> 2
    else -> 1
}

/**
 * A simple **non-lazy** grid: lays [items] out in rows of [columns], each cell
 * taking an equal `weight(1f)` share of the width so cards fill their cell.
 *
 * Non-lazy on purpose — the modules screen hosts this inside a `verticalScroll`
 * Column, where a `LazyVerticalGrid` would crash on unbounded-height
 * constraints. Intended for small, bounded lists (e.g. the first 4 modules);
 * `AllModulesScreen` uses a real `LazyVerticalGrid` for the full catalogue.
 *
 * Trailing cells in the last row are filled with weighted spacers so cards stay
 * left-aligned and uniformly sized instead of stretching to fill the row.
 */
@Composable
fun <T> ModuleGrid(
    items: List<T>,
    columns: Int,
    modifier: Modifier = Modifier,
    horizontalSpacing: Dp = 12.dp,
    verticalSpacing: Dp = 12.dp,
    itemContent: @Composable (T) -> Unit,
) {
    if (items.isEmpty()) return
    val cols = columns.coerceAtLeast(1)
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(verticalSpacing),
    ) {
        items.chunked(cols).forEach { rowItems ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(horizontalSpacing),
            ) {
                rowItems.forEach { item ->
                    Column(modifier = Modifier.weight(1f)) {
                        itemContent(item)
                    }
                }
                // Pad the final short row so its cells match the column width.
                repeat(cols - rowItems.size) {
                    Spacer(modifier = Modifier.weight(1f))
                }
            }
        }
    }
}
