package com.medtroniclabs.microcoaching.ui.podashboard.drilldown

import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.medtroniclabs.microcoaching.R
import com.medtroniclabs.microcoaching.ui.common.CenterProgress
import com.medtroniclabs.microcoaching.ui.common.SdkScreenHeader
import com.medtroniclabs.microcoaching.ui.podashboard.SuggestionDetail
import com.medtroniclabs.microcoaching.ui.podashboard.SuggestionDetailUiState
import com.medtroniclabs.microcoaching.ui.podashboard.SuggestionDetailViewModel
import com.medtroniclabs.microcoaching.ui.podashboard.SuggestionEvidenceItem
import com.medtroniclabs.microcoaching.ui.podashboard.components.MutedText
import com.medtroniclabs.microcoaching.ui.podashboard.components.poCard
import com.medtroniclabs.microcoaching.ui.theme.SpiceBlue
import com.medtroniclabs.microcoaching.ui.theme.SurfaceMuted

private val DividerColor = Color(0xFFEFEFF3)

/**
 * "Top Searched Suggested" drill-down: the reason the suggestion was
 * generated, then the queries and free-text requests behind it as accordion rows.
 * View-only — Admin Publish / Create Module are web workflows, not on PO mobile.
 */
@Composable
fun SuggestionDetailScreen(suggestionId: String, onBack: () -> Unit, onHome: () -> Unit) {
    val vm: SuggestionDetailViewModel =
        viewModel(factory = SuggestionDetailViewModel.factory(suggestionId))
    val state by vm.uiState.collectAsState()
    val networkAvailable by vm.networkAvailable.collectAsState()

    Column(modifier = Modifier.fillMaxSize().background(SurfaceMuted)) {
        val title = (state as? SuggestionDetailUiState.Ready)?.detail?.title
            ?: stringResource(R.string.po_searched_suggested_title)
        SdkScreenHeader(title = title, onBack = onBack, onHome = onHome)
        when (val s = state) {
            is SuggestionDetailUiState.Loading -> CenterProgress()
            is SuggestionDetailUiState.Error ->
                DashboardErrorState(offline = !networkAvailable, message = s.message, onRetry = vm::retry, isAuth = s.isAuth)
            is SuggestionDetailUiState.Ready -> Content(s.detail)
        }
    }
}

@Composable
private fun Content(detail: SuggestionDetail) {
    Column(modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)) {
        if (!detail.rationale.isNullOrBlank()) {
            Column(modifier = Modifier.fillMaxWidth().poCard().padding(16.dp)) {
                Text(
                    stringResource(R.string.po_searched_reason),
                    color = MutedText,
                    fontWeight = FontWeight.SemiBold,
                    style = MaterialTheme.typography.labelMedium,
                )
                Spacer(Modifier.height(4.dp))
                Text(detail.rationale, style = MaterialTheme.typography.bodyMedium)
            }
            Spacer(Modifier.height(16.dp))
        }

        EvidenceSection(R.string.po_searched_queries, R.string.po_searched_empty_queries, detail.questions, "q")
        Spacer(Modifier.height(16.dp))
        EvidenceSection(R.string.po_searched_requests, R.string.po_searched_empty_requests, detail.requests, "r")
        Spacer(Modifier.height(64.dp))
    }
}

@Composable
private fun EvidenceSection(
    @StringRes titleRes: Int,
    @StringRes emptyRes: Int,
    items: List<SuggestionEvidenceItem>,
    keyPrefix: String,
) {
    Text(
        stringResource(titleRes),
        fontWeight = FontWeight.SemiBold,
        style = MaterialTheme.typography.labelLarge,
        modifier = Modifier.padding(bottom = 8.dp),
    )
    if (items.isEmpty()) {
        Text(stringResource(emptyRes), color = MutedText, style = MaterialTheme.typography.bodyMedium)
        return
    }
    val expanded = remember(keyPrefix) { mutableStateMapOf<Int, Boolean>() }
    Column(modifier = Modifier.fillMaxWidth().poCard()) {
        items.forEachIndexed { i, item ->
            if (i > 0) HorizontalDivider(color = DividerColor)
            EvidenceAccordionRow(item, expanded[i] == true) { expanded[i] = !(expanded[i] ?: false) }
        }
    }
}

@Composable
private fun EvidenceAccordionRow(item: SuggestionEvidenceItem, expanded: Boolean, onToggle: () -> Unit) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth().clickable(onClick = onToggle).padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(item.text, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
            Spacer(Modifier.width(8.dp))
            Text("${item.occurrenceCount}", color = SpiceBlue, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.bodyMedium)
            Icon(
                imageVector = if (expanded) Icons.Filled.KeyboardArrowUp else Icons.Filled.KeyboardArrowDown,
                contentDescription = null,
                tint = MutedText,
            )
        }
        if (expanded) {
            Column(modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, bottom = 12.dp)) {
                Text(
                    stringResource(R.string.po_searched_occurrences, item.occurrenceCount),
                    color = MutedText,
                    style = MaterialTheme.typography.labelMedium,
                )
                if (item.lastSeenLabel.isNotBlank()) {
                    Text(item.lastSeenLabel, color = MutedText, style = MaterialTheme.typography.labelMedium)
                }
            }
        }
    }
}
