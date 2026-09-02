package com.medtroniclabs.microcoaching.ui.podashboard.drilldown

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.medtroniclabs.microcoaching.R
import com.medtroniclabs.microcoaching.ui.common.CenterProgress
import com.medtroniclabs.microcoaching.ui.common.SdkScreenHeader
import com.medtroniclabs.microcoaching.ui.common.SegmentedToggle
import com.medtroniclabs.microcoaching.ui.podashboard.DateRange
import com.medtroniclabs.microcoaching.ui.podashboard.DocumentReaderItem
import com.medtroniclabs.microcoaching.ui.podashboard.DocumentUsageDetail
import com.medtroniclabs.microcoaching.ui.podashboard.DocumentUsageDetailUiState
import com.medtroniclabs.microcoaching.ui.podashboard.DocumentUsageDetailViewModel
import com.medtroniclabs.microcoaching.ui.podashboard.DocumentViewEventItem
import com.medtroniclabs.microcoaching.ui.podashboard.components.MutedText
import com.medtroniclabs.microcoaching.ui.podashboard.components.StatusGreen
import com.medtroniclabs.microcoaching.ui.podashboard.components.poCard


private const val TAB_READERS = 0
private const val TAB_OPENS = 1

/**
 * Document-usage drill-down: total opens / unique readers for one knowledge
 * document, then either the distinct readers (default — one row each, with how
 * many times they opened it) or every individual open. View-only; reader names
 * are rendered and never logged.
 */
@Composable
fun DocumentUsageDetailScreen(
    documentId: String,
    range: DateRange,
    onBack: () -> Unit,
    onHome: () -> Unit,
) {
    val vm: DocumentUsageDetailViewModel =
        viewModel(factory = DocumentUsageDetailViewModel.factory(documentId, range))
    val state by vm.uiState.collectAsState()
    val networkAvailable by vm.networkAvailable.collectAsState()

    Column(modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surfaceContainerLow)) {
        val title = (state as? DocumentUsageDetailUiState.Ready)?.detail?.title
            ?: stringResource(R.string.po_section_document_usage)
        SdkScreenHeader(title = title, onBack = onBack, onHome = onHome)
        when (val s = state) {
            is DocumentUsageDetailUiState.Loading -> CenterProgress()
            is DocumentUsageDetailUiState.Error ->
                DashboardErrorState(offline = !networkAvailable, message = s.message, onRetry = vm::retry, isAuth = s.isAuth)
            is DocumentUsageDetailUiState.Ready -> Content(s.detail)
        }
    }
}

@Composable
private fun Content(detail: DocumentUsageDetail) {
    // Readers first in both the KPI card and the toggle, so the two read
    // left-to-right the same way.
    var tab by rememberSaveable { mutableIntStateOf(TAB_READERS) }

    Column(modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth().poCard().padding(16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            StatCell(stringResource(R.string.po_document_unique_readers), detail.uniqueUsers, StatusGreen, Modifier.weight(1f))
            StatCell(stringResource(R.string.po_document_total_opens), detail.totalViews, MaterialTheme.colorScheme.primary, Modifier.weight(1f))
        }

        Spacer(Modifier.height(16.dp))
        // The toggle labels the list below it, so no separate section heading.
        SegmentedToggle(
            options = listOf(
                stringResource(R.string.po_document_unique_readers),
                stringResource(R.string.po_document_opens_list),
            ),
            selectedIndex = tab,
            onSelect = { tab = it },
            modifier = Modifier.fillMaxWidth(),
        )

        Spacer(Modifier.height(12.dp))
        when (tab) {
            TAB_OPENS -> CardList(
                items = detail.events,
                empty = stringResource(R.string.po_document_detail_empty_opens),
            ) { OpenEventRow(it) }
            else -> CardList(
                items = detail.readers,
                empty = stringResource(R.string.po_document_detail_empty_readers),
            ) { ReaderRow(it) }
        }

        if (detail.eventsTruncated) {
            Spacer(Modifier.height(8.dp))
            Text(
                stringResource(R.string.po_document_events_truncated, detail.events.size, detail.totalEvents),
                color = MutedText,
                style = MaterialTheme.typography.labelSmall,
            )
        }
        Spacer(Modifier.height(64.dp))
    }
}

/** Divider-separated rows on one card, or the tab's empty note when there are none. */
@Composable
private fun <T> CardList(items: List<T>, empty: String, row: @Composable (T) -> Unit) {
    if (items.isEmpty()) {
        Text(empty, color = MutedText, style = MaterialTheme.typography.bodyMedium)
        return
    }
    Column(modifier = Modifier.fillMaxWidth().poCard()) {
        items.forEachIndexed { i, item ->
            if (i > 0) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            row(item)
        }
    }
}

@Composable
private fun StatCell(label: String, value: Int, color: Color, modifier: Modifier = Modifier) {
    Column(modifier = modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Text("$value", color = color, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.headlineSmall)
        Text(label, color = MutedText, style = MaterialTheme.typography.labelMedium)
    }
}

/** A reader's name with their role appended, when the wire carried one. */
@Composable
private fun readerLabel(name: String, role: String?): String =
    role?.let { stringResource(R.string.po_document_reader_with_role, name, it) } ?: name

/** One reader: who they are on the left, their open count and latest open on the right. */
@Composable
private fun ReaderRow(reader: DocumentReaderItem) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                text = readerLabel(reader.userName, reader.userRole),
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            reader.geography?.let {
                Text(
                    text = it,
                    color = MutedText,
                    style = MaterialTheme.typography.labelMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        Spacer(Modifier.width(12.dp))
        Column(horizontalAlignment = Alignment.End) {
            Text(
                text = pluralStringResource(R.plurals.po_document_open_count, reader.opens, reader.opens),
                color = MaterialTheme.colorScheme.primary,
                fontWeight = FontWeight.SemiBold,
                style = MaterialTheme.typography.labelMedium,
                maxLines = 1,
            )
            if (reader.lastViewedAtLabel.isNotBlank()) {
                Text(
                    reader.lastViewedAtLabel,
                    color = MutedText,
                    style = MaterialTheme.typography.labelSmall,
                    maxLines = 1,
                )
            }
        }
    }
}

/** One open: reader (and role) on the left, geography underneath, when on the right. */
@Composable
private fun OpenEventRow(event: DocumentViewEventItem) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                text = readerLabel(event.userName, event.userRole),
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            event.geography?.let {
                Text(
                    text = it,
                    color = MutedText,
                    style = MaterialTheme.typography.labelMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        Spacer(Modifier.width(8.dp))
        Text(event.viewedAtLabel, color = MutedText, style = MaterialTheme.typography.labelMedium, maxLines = 1)
    }
}
