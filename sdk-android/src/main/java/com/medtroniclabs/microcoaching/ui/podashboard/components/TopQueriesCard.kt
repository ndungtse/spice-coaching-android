package com.medtroniclabs.microcoaching.ui.podashboard.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.medtroniclabs.microcoaching.ui.podashboard.TopQuery
import com.medtroniclabs.microcoaching.ui.theme.SpiceBlue
import com.medtroniclabs.microcoaching.ui.theme.SpiceBlueContainer
import com.medtroniclabs.microcoaching.ui.theme.SpiceBlueDark

/**
 * Ranked "Top Queries / Top Searched" card (rank badge · text · count).
 *
 * When [onItemClick] is supplied, rows whose [TopQuery.id] is non-null become
 * tappable and show a trailing chevron into their drill-downs.
 */
@Composable
fun TopQueriesCard(
    queries: List<TopQuery>,
    modifier: Modifier = Modifier,
    onItemClick: ((TopQuery) -> Unit)? = null,
) {
    Column(modifier = modifier.fillMaxWidth().poCard()) {
        queries.forEach { q ->
            TopQueryRow(q, onClick = onItemClick?.let { cb -> { cb(q) } }.takeIf { q.id != null })
        }
    }
}

/**
 * A single ranked row (rank badge · text · count). Tappable (with a trailing chevron)
 * when [onClick] is non-null. Reused by [TopQueriesCard] and the "Show all" list screen.
 */
@Composable
fun TopQueryRow(query: TopQuery, onClick: (() -> Unit)? = null, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier.size(24.dp).clip(CircleShape).background(SpiceBlueContainer),
            contentAlignment = Alignment.Center,
        ) {
            Text("${query.rank}", color = SpiceBlueDark, fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.labelSmall)
        }
        Spacer(Modifier.width(12.dp))
        Text(query.text, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
        Text("${query.count}", color = SpiceBlue, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.bodyMedium)
        if (onClick != null) {
            Spacer(Modifier.width(8.dp))
            Icon(
                imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = null,
                tint = MutedText,
            )
        }
    }
}
