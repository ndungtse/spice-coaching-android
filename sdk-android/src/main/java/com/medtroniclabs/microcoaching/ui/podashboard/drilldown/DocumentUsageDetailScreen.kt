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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.medtroniclabs.microcoaching.R
import com.medtroniclabs.microcoaching.ui.common.CenterProgress
import com.medtroniclabs.microcoaching.ui.common.SdkScreenHeader
import com.medtroniclabs.microcoaching.ui.podashboard.DocumentUsageDetail
import com.medtroniclabs.microcoaching.ui.podashboard.DocumentUsageDetailUiState
import com.medtroniclabs.microcoaching.ui.podashboard.DocumentUsageDetailViewModel
import com.medtroniclabs.microcoaching.ui.podashboard.DocumentViewEventItem
import com.medtroniclabs.microcoaching.ui.podashboard.components.MutedText
import com.medtroniclabs.microcoaching.ui.podashboard.components.StatusGreen
import com.medtroniclabs.microcoaching.ui.podashboard.components.poCard
import com.medtroniclabs.microcoaching.ui.theme.SpiceBlue
import com.medtroniclabs.microcoaching.ui.theme.SurfaceMuted

private val DividerColor = Color(0xFFEFEFF3)

/**
 * Document-usage drill-down: total opens / unique readers for one knowledge
 * document, then the individual opens — who, when, where. View-only; reader
 * names are rendered and never logged.
 */
@Composable
fun DocumentUsageDetailScreen(documentId: String, onBack: () -> Unit, onHome: () -> Unit) {
    val vm: DocumentUsageDetailViewModel =
        viewModel(factory = DocumentUsageDetailViewModel.factory(documentId))
    val state by vm.uiState.collectAsState()
    val networkAvailable by vm.networkAvailable.collectAsState()

    Column(modifier = Modifier.fillMaxSize().background(SurfaceMuted)) {
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
    Column(modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth().poCard().padding(16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            StatCell(stringResource(R.string.po_document_total_opens), detail.totalViews, SpiceBlue, Modifier.weight(1f))
            StatCell(stringResource(R.string.po_document_unique_readers), detail.uniqueUsers, StatusGreen, Modifier.weight(1f))
        }

        Spacer(Modifier.height(16.dp))
        Text(
            stringResource(R.string.po_document_opens_list),
            fontWeight = FontWeight.SemiBold,
            style = MaterialTheme.typography.labelLarge,
            modifier = Modifier.padding(bottom = 8.dp),
        )
        if (detail.events.isEmpty()) {
            Text(
                stringResource(R.string.po_document_empty_opens),
                color = MutedText,
                style = MaterialTheme.typography.bodyMedium,
            )
        } else {
            Column(modifier = Modifier.fillMaxWidth().poCard()) {
                detail.events.forEachIndexed { i, event ->
                    if (i > 0) HorizontalDivider(color = DividerColor)
                    OpenEventRow(event)
                }
            }
        }
        Spacer(Modifier.height(64.dp))
    }
}

@Composable
private fun StatCell(label: String, value: Int, color: Color, modifier: Modifier = Modifier) {
    Column(modifier = modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Text("$value", color = color, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.headlineSmall)
        Text(label, color = MutedText, style = MaterialTheme.typography.labelMedium)
    }
}

/** One open: reader (and role) on the left, geography and when underneath. */
@Composable
private fun OpenEventRow(event: DocumentViewEventItem) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                text = event.userRole
                    ?.let { stringResource(R.string.po_document_reader_with_role, event.userName, it) }
                    ?: event.userName,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            event.geography?.let {
                Text(it, color = MutedText, style = MaterialTheme.typography.labelMedium)
            }
        }
        Spacer(Modifier.width(8.dp))
        Text(event.viewedAtLabel, color = MutedText, style = MaterialTheme.typography.labelMedium)
    }
}
