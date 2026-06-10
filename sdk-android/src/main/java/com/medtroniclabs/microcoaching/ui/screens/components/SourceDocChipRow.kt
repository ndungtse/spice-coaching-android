package com.medtroniclabs.microcoaching.ui.screens.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.medtroniclabs.microcoaching.R
import com.medtroniclabs.microcoaching.network.SourceDocumentRef

/**
 * Citation chip row rendered below an assistant chat bubble that came from a
 * BM25-matched module. Each chip dereferences a single source-document UUID
 * via [com.medtroniclabs.microcoaching.network.CoachingApiService.getSourceDocumentPresignedUrls].
 *
 * Per-chip label resolution (first non-blank wins):
 *  1. the document's own `title`,
 *  2. its `original_filename`,
 *  3. the dominant grounding module's title ([moduleTitle]),
 *  4. a generic "Source document" default.
 *
 * Offline → same label but greyed-out with a small "Offline" subtitle; tap is
 * disabled.
 *
 * @param sourceDocuments Rich refs to render — one chip per document, in order.
 * @param moduleTitle Cached title of the dominant grounding module in the
 *   active SDK locale, used only as a fallback when a document has no title.
 * @param online Drives chip enabled state; supplied by the surface as a
 *   collected `StateFlow<Boolean>`.
 * @param onTap Invoked when the user taps an enabled chip. Caller fires the
 *   presigned-url fetch + preview navigation.
 */
@Composable
fun SourceDocChipRow(
    sourceDocuments: List<SourceDocumentRef>,
    moduleTitle: String?,
    online: Boolean,
    onTap: (sourceDocumentId: String, label: String) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (sourceDocuments.isEmpty()) return

    val defaultLabel = stringResource(R.string.chat_source_default)
    val offlineLabel = stringResource(R.string.chat_source_offline)

    LazyRow(
        modifier = modifier.padding(top = 6.dp),
        contentPadding = PaddingValues(horizontal = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        items(sourceDocuments.size) { index ->
            val doc = sourceDocuments[index]
            val label = doc.title?.takeIf { it.isNotBlank() }
                ?: doc.originalFilename?.takeIf { it.isNotBlank() }
                ?: moduleTitle?.takeIf { it.isNotBlank() }
                ?: defaultLabel
            SourceDocChip(
                label = label,
                online = online,
                offlineSubtitle = offlineLabel,
                onClick = { onTap(doc.id, label) },
            )
        }
    }
}

@Composable
private fun SourceDocChip(
    label: String,
    online: Boolean,
    offlineSubtitle: String,
    onClick: () -> Unit,
) {
    Surface(
        shape = RoundedCornerShape(50),
        color = if (online) {
            MaterialTheme.colorScheme.primaryContainer
        } else {
            MaterialTheme.colorScheme.surfaceVariant
        },
        border = BorderStroke(
            width = 1.dp,
            color = if (online) {
                MaterialTheme.colorScheme.primary.copy(alpha = 0.4f)
            } else {
                MaterialTheme.colorScheme.outline.copy(alpha = 0.4f)
            },
        ),
        modifier = Modifier.clickable(enabled = online) { onClick() },
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = Icons.Outlined.Description,
                contentDescription = null,
                modifier = Modifier.size(16.dp),
                tint = if (online) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                },
            )
            Spacer(Modifier.width(6.dp))
            Column {
                Text(
                    text = label,
                    style = MaterialTheme.typography.labelMedium,
                    color = if (online) {
                        MaterialTheme.colorScheme.onPrimaryContainer
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                    },
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                if (!online) {
                    Text(
                        text = offlineSubtitle,
                        style = MaterialTheme.typography.labelSmall,
                        color = Color.Gray,
                    )
                }
            }
        }
    }
}
