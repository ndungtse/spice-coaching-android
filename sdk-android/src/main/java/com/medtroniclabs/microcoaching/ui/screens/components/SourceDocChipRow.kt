package com.medtroniclabs.microcoaching.ui.screens.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.medtroniclabs.microcoaching.R
import com.medtroniclabs.microcoaching.network.SourceDocumentRef

/**
 * Citation chip rendered below an assistant chat bubble that came from a BM25-matched or
 * backend-RAG answer. The chip dereferences a source-document UUID via
 * [com.medtroniclabs.microcoaching.network.CoachingApiService.getSourceDocumentPresignedUrls].
 *
 * Only the **first** document is shown. Retrieval routinely cites several, but they are
 * ranked, so the rest add width without adding much: the reader wants the one place to
 * look, and the tail was pushing the chip row into a horizontal scroll.
 *
 * Label resolution (first non-blank wins):
 *  1. the document's own `title`,
 *  2. its `original_filename`,
 *  3. the dominant grounding module's title ([moduleTitle]),
 *  4. a generic "Source document" default.
 *
 * @param sourceDocuments Ranked refs; all but the first are ignored.
 * @param moduleTitle Cached title of the dominant grounding module in the
 *   active SDK locale, used only as a fallback when the document has no title.
 * @param onTap Invoked when the user taps the chip. Caller fires the
 *   presigned-url fetch + preview navigation.
 * @param startPage Cited page, forwarded to [onTap] so the viewer can land on it.
 *   Deliberately not shown in the label: a page number is a retrieval detail the
 *   reader can't act on, and it crowded out the document title it was appended to.
 */
@Composable
fun SourceDocChipRow(
    sourceDocuments: List<SourceDocumentRef>,
    moduleTitle: String?,
    onTap: (sourceDocumentId: String, label: String, startPage: Int?) -> Unit,
    startPage: Int? = null,
    modifier: Modifier = Modifier,
) {
    val doc = sourceDocuments.firstOrNull() ?: return

    val label = doc.title?.takeIf { it.isNotBlank() }
        ?: doc.originalFilename?.takeIf { it.isNotBlank() }
        ?: moduleTitle?.takeIf { it.isNotBlank() }
        ?: stringResource(R.string.chat_source_default)

    Row(modifier = modifier.padding(top = 6.dp, start = 4.dp, end = 4.dp)) {
        SourceDocChip(label = label, onClick = { onTap(doc.id, label, startPage) })
    }
}

@Composable
private fun SourceDocChip(
    label: String,
    onClick: () -> Unit,
) {
    Surface(
        shape = RoundedCornerShape(50),
        color = MaterialTheme.colorScheme.primaryContainer,
        border = BorderStroke(
            width = 1.dp,
            color = MaterialTheme.colorScheme.primary.copy(alpha = 0.4f),
        ),
        modifier = Modifier.clickable { onClick() },
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = Icons.Outlined.Description,
                contentDescription = null,
                modifier = Modifier.size(16.dp),
                tint = MaterialTheme.colorScheme.primary,
            )
            Spacer(Modifier.width(6.dp))
            Text(
                text = label,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onPrimaryContainer,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}
