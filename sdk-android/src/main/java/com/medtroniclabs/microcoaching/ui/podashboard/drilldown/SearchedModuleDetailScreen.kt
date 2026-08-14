package com.medtroniclabs.microcoaching.ui.podashboard.drilldown

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.medtroniclabs.microcoaching.R
import com.medtroniclabs.microcoaching.ui.common.CenterProgress
import com.medtroniclabs.microcoaching.ui.common.SdkScreenHeader
import com.medtroniclabs.microcoaching.ui.podashboard.ModuleQuestionItem
import com.medtroniclabs.microcoaching.ui.podashboard.SearchedModuleDetail
import com.medtroniclabs.microcoaching.ui.podashboard.SearchedModuleDetailUiState
import com.medtroniclabs.microcoaching.ui.podashboard.SearchedModuleDetailViewModel
import com.medtroniclabs.microcoaching.ui.podashboard.components.MutedText
import com.medtroniclabs.microcoaching.ui.podashboard.components.StatusGreen
import com.medtroniclabs.microcoaching.ui.podashboard.components.poCard
import com.medtroniclabs.microcoaching.ui.theme.SpiceBlue
import com.medtroniclabs.microcoaching.ui.theme.SurfaceMuted

private val DividerColor = Color(0xFFEFEFF3)

/**
 * "Top Searched Existing" drill-down: served/requested split at the top,
 * then the served queries as accordion rows (expand → last-asked + occurrences).
 * View-only — Admin Assign is a web workflow, not exposed on PO mobile.
 */
@Composable
fun SearchedModuleDetailScreen(moduleId: String, onBack: () -> Unit, onHome: () -> Unit) {
    val vm: SearchedModuleDetailViewModel =
        viewModel(factory = SearchedModuleDetailViewModel.factory(moduleId))
    val state by vm.uiState.collectAsState()
    val networkAvailable by vm.networkAvailable.collectAsState()

    Column(modifier = Modifier.fillMaxSize().background(SurfaceMuted)) {
        val title = (state as? SearchedModuleDetailUiState.Ready)?.detail?.title
            ?: stringResource(R.string.po_searched_existing_title)
        SdkScreenHeader(title = title, onBack = onBack, onHome = onHome)
        when (val s = state) {
            is SearchedModuleDetailUiState.Loading -> CenterProgress()
            is SearchedModuleDetailUiState.Error ->
                DashboardErrorState(offline = !networkAvailable, message = s.message, onRetry = vm::retry, isAuth = s.isAuth)
            is SearchedModuleDetailUiState.Ready -> Content(s.detail)
        }
    }
}

@Composable
private fun Content(detail: SearchedModuleDetail) {
    val expanded = remember { mutableStateMapOf<Int, Boolean>() }
    Column(modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)) {
        // Served / requested split
        Row(
            modifier = Modifier.fillMaxWidth().poCard().padding(16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            StatCell(stringResource(R.string.po_searched_served), detail.servedCount, SpiceBlue, Modifier.weight(1f))
            StatCell(stringResource(R.string.po_searched_requested), detail.requestedCount, StatusGreen, Modifier.weight(1f))
        }

        Spacer(Modifier.height(16.dp))
        Text(
            stringResource(R.string.po_searched_queries),
            fontWeight = FontWeight.SemiBold,
            style = MaterialTheme.typography.labelLarge,
            modifier = Modifier.padding(bottom = 8.dp),
        )
        if (detail.questions.isEmpty()) {
            Text(
                stringResource(R.string.po_searched_empty_queries),
                color = MutedText,
                style = MaterialTheme.typography.bodyMedium,
            )
        } else {
            Column(modifier = Modifier.fillMaxWidth().poCard()) {
                detail.questions.forEachIndexed { i, q ->
                    if (i > 0) HorizontalDivider(color = DividerColor)
                    QueryAccordionRow(q, expanded[i] == true) { expanded[i] = !(expanded[i] ?: false) }
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
        Text(
            text = label,
            color = MutedText,
            style = MaterialTheme.typography.labelMedium,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

/** Accordion row: query text; expand to reveal metadata. */
@Composable
private fun QueryAccordionRow(item: ModuleQuestionItem, expanded: Boolean, onToggle: () -> Unit) {
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
                MetaLine(stringResource(R.string.po_searched_occurrences, item.occurrenceCount))
                if (item.lastAskedLabel.isNotBlank()) {
                    MetaLine(stringResource(R.string.po_sk_last_chatbot) + ": " + item.lastAskedLabel)
                }
            }
        }
    }
}

@Composable
private fun MetaLine(text: String) {
    Text(text, color = MutedText, style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(top = 2.dp))
}
