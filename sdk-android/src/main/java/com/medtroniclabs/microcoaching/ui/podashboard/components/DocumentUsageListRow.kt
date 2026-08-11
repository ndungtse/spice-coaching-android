package com.medtroniclabs.microcoaching.ui.podashboard.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.medtroniclabs.microcoaching.R
import com.medtroniclabs.microcoaching.ui.podashboard.DocumentUsageRow

/**
 * Document-usage list row: title · total opens · unique readers · last opened
 * (when, by whom) · chevron into the drill-down. `lastViewedBy` is a person's
 * name — rendered only, never logged.
 */
@Composable
fun DocumentUsageListRow(
    row: DocumentUsageRow,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier.fillMaxWidth().poCard().clickable(onClick = onClick).padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                text = row.title,
                fontWeight = FontWeight.Bold,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = stringResource(
                    R.string.po_document_opens_and_readers,
                    row.totalViews,
                    row.uniqueUsers,
                ),
                style = MaterialTheme.typography.labelSmall,
                color = MutedText,
            )
            // A document with no opens in the range has neither a date nor a reader.
            if (row.lastViewedLabel.isNotBlank()) {
                Spacer(Modifier.height(2.dp))
                Text(
                    text = row.lastViewedBy
                        ?.let { stringResource(R.string.po_document_last_opened_by, row.lastViewedLabel, it) }
                        ?: stringResource(R.string.po_document_last_opened, row.lastViewedLabel),
                    style = MaterialTheme.typography.labelSmall,
                    color = MutedText,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        Spacer(Modifier.width(4.dp))
        Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null, tint = MutedText)
    }
}
