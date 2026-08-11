package com.medtroniclabs.microcoaching.ui.learn.modules.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.medtroniclabs.microcoaching.ui.common.ModuleTileSkeleton
import com.medtroniclabs.microcoaching.ui.document.DocumentFileType
import com.medtroniclabs.microcoaching.ui.document.labelRes
import com.medtroniclabs.microcoaching.ui.learn.KnowledgeDocument

/** How many shimmer tiles to show while the grid's first load is in flight. */
private const val SKELETON_TILE_COUNT = 6

/**
 * Responsive grid of Knowledge source-document tiles — extracted from
 * [com.medtroniclabs.microcoaching.ui.learn.modules.AllModulesScreen]'s knowledge
 * mode so the Coaching tab's Knowledge sub-tab
 * (`com.medtroniclabs.microcoaching.ui.coaching.KnowledgeSubTab`) can render the
 * identical grid. Column count tracks [moduleTileColumns].
 *
 * [bottomPadding] only changes the grid's bottom content inset, letting a caller
 * reserve clearance below it (e.g. the chat FAB) without touching the top/side
 * insets.
 *
 * Grid-only: callers own the surrounding chrome — header, pull-to-refresh, and
 * any sync state stay with them.
 */
@Composable
fun KnowledgeDocGrid(
    documents: List<KnowledgeDocument>,
    onDocSelect: (KnowledgeDocument) -> Unit,
    cachedDocIds: Set<String>,
    isLoading: Boolean,
    modifier: Modifier = Modifier,
    bottomPadding: Dp = 12.dp,
) {
    BoxWithConstraints(modifier = modifier) {
        val columns = moduleTileColumns(maxWidth)
        LazyVerticalGrid(
            columns = GridCells.Fixed(columns),
            modifier = Modifier.fillMaxWidth(),
            // Outer screen margin; inter-tile spacing is the spacedBy() below.
            contentPadding = PaddingValues(start = 16.dp, top = 12.dp, end = 16.dp, bottom = bottomPadding),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (isLoading && documents.isEmpty()) {
                // Shape-matched shimmer placeholders while the first list load
                // is in flight — no bare empty grid. Real tiles replace these
                // the moment data arrives.
                itemsIndexed(List(SKELETON_TILE_COUNT) { it }) { _, _ ->
                    ModuleTileSkeleton()
                }
            } else {
                items(items = documents, key = { it.sourceDocumentId }) { doc ->
                    ModuleTile(
                        title = doc.title,
                        subtitle = stringResource(DocumentFileType.fromFilename(doc.fileName).labelRes),
                        variant = ModuleTileVariant.KNOWLEDGE,
                        knowledgeCached = doc.sourceDocumentId in cachedDocIds,
                        onClick = { onDocSelect(doc) },
                        onDownloadClick = { onDocSelect(doc) },
                        thumbnailUrl = doc.thumbnailUrl,
                        fileName = doc.fileName,
                    )
                }
            }
        }
    }
}
