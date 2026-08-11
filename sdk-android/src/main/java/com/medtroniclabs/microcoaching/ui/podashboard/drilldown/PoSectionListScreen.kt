package com.medtroniclabs.microcoaching.ui.podashboard.drilldown

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.annotation.StringRes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.lifecycle.viewmodel.compose.viewModel
import com.medtroniclabs.microcoaching.R
import com.medtroniclabs.microcoaching.ui.common.CenterProgress
import com.medtroniclabs.microcoaching.ui.common.SdkScreenHeader
import com.medtroniclabs.microcoaching.ui.common.SegmentedToggle
import com.medtroniclabs.microcoaching.ui.podashboard.DateRange
import com.medtroniclabs.microcoaching.ui.podashboard.PoDashboardSection
import com.medtroniclabs.microcoaching.ui.podashboard.PoSectionListUiState
import com.medtroniclabs.microcoaching.ui.podashboard.PoSectionListViewModel
import com.medtroniclabs.microcoaching.ui.podashboard.SectionListPayload
import com.medtroniclabs.microcoaching.ui.podashboard.SkStatusFilter
import com.medtroniclabs.microcoaching.ui.podashboard.matchesFilter
import com.medtroniclabs.microcoaching.ui.podashboard.components.DocumentUsageListRow
import com.medtroniclabs.microcoaching.ui.podashboard.components.MutedText
import com.medtroniclabs.microcoaching.ui.podashboard.components.ModuleCompletionRow
import com.medtroniclabs.microcoaching.ui.podashboard.components.RefresherCompletionRow
import com.medtroniclabs.microcoaching.ui.podashboard.components.SkListRow
import com.medtroniclabs.microcoaching.ui.podashboard.components.SkSearchBar
import com.medtroniclabs.microcoaching.ui.podashboard.components.TopQueryRow
import com.medtroniclabs.microcoaching.ui.podashboard.components.poCard
import com.medtroniclabs.microcoaching.ui.theme.SurfaceMuted

/**
 * Flat, searchable "Show all" list for one dashboard [section] over the selected [range].
 * Load-all + virtualized [LazyColumn] (matches AllModulesScreen). SK sections add a status
 * filter. Reuses the section's row components; tapping a row goes to its by-id detail page.
 */
@Composable
fun PoSectionListScreen(
    section: PoDashboardSection,
    range: DateRange,
    chwId: String,
    onBack: () -> Unit,
    onHome: () -> Unit,
    onOpenSkDetail: (String) -> Unit,
    onOpenSearchedModule: (String) -> Unit,
    onOpenSuggestion: (String) -> Unit,
    onOpenDocument: (String) -> Unit,
) {
    val vm: PoSectionListViewModel =
        viewModel(factory = PoSectionListViewModel.factory(section, range, chwId))
    val state by vm.uiState.collectAsState()
    val networkAvailable by vm.networkAvailable.collectAsState()

    var query by rememberSaveable(section) { mutableStateOf("") }
    var filterIndex by rememberSaveable(section) { mutableIntStateOf(0) }
    val expanded = remember(section) { mutableStateMapOf<Int, Boolean>() }

    Column(modifier = Modifier.fillMaxSize().background(SurfaceMuted)) {
        SdkScreenHeader(title = stringResource(section.titleRes()), onBack = onBack, onHome = onHome)
        when (val s = state) {
            is PoSectionListUiState.Loading -> CenterProgress()
            is PoSectionListUiState.Error ->
                DashboardErrorState(offline = !networkAvailable, message = s.message, onRetry = vm::retry)
            is PoSectionListUiState.Ready -> {
                Column(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    SkSearchBar(
                        query = query,
                        onQueryChange = { query = it },
                        hint = stringResource(section.searchHintRes()),
                        showFilterButton = false,
                    )
                    if (section.hasStatusFilter()) {
                        SegmentedToggle(
                            options = Sk_FILTER_LABELS.map { stringResource(it) },
                            selectedIndex = filterIndex,
                            onSelect = { filterIndex = it },
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }
                SectionList(
                    section = section,
                    payload = s.payload,
                    query = query.trim(),
                    filter = SkStatusFilter.entries[filterIndex],
                    expanded = expanded,
                    onOpenSkDetail = onOpenSkDetail,
                    onOpenSearchedModule = onOpenSearchedModule,
                    onOpenSuggestion = onOpenSuggestion,
                    onOpenDocument = onOpenDocument,
                )
            }
        }
    }
}

@Composable
private fun androidx.compose.foundation.layout.ColumnScope.SectionList(
    section: PoDashboardSection,
    payload: SectionListPayload,
    query: String,
    filter: SkStatusFilter,
    expanded: androidx.compose.runtime.snapshots.SnapshotStateMap<Int, Boolean>,
    onOpenSkDetail: (String) -> Unit,
    onOpenSearchedModule: (String) -> Unit,
    onOpenSuggestion: (String) -> Unit,
    onOpenDocument: (String) -> Unit,
) {
    val listModifier = Modifier.weight(1f).fillMaxWidth()
    val padding = PaddingValues(horizontal = 16.dp, vertical = 4.dp)
    val spacing = Arrangement.spacedBy(8.dp)

    when (section) {
        PoDashboardSection.MY_SKS -> {
            val rows = payload.sks.filter { it.name.contains(query, true) && it.matchesFilter(filter) }
            LazyColumn(listModifier, contentPadding = padding, verticalArrangement = spacing) {
                if (rows.isEmpty()) item { EmptyRow() }
                items(rows, key = { it.id }) { sk -> SkListRow(sk = sk, onClick = { onOpenSkDetail(sk.id) }) }
            }
        }
        PoDashboardSection.REFRESHERS -> {
            val rows = payload.sks.filter { it.name.contains(query, true) && it.matchesFilter(filter) }
            LazyColumn(listModifier, contentPadding = padding, verticalArrangement = spacing) {
                if (rows.isEmpty()) item { EmptyRow() }
                items(rows, key = { it.id }) { sk -> RefresherCompletionRow(sk) }
            }
        }
        PoDashboardSection.MODULE_COMPLETION -> {
            val rows = payload.moduleCompletion.filter { it.moduleName.contains(query, true) }
            LazyColumn(listModifier, contentPadding = padding, verticalArrangement = spacing) {
                if (rows.isEmpty()) item { EmptyRow() }
                itemsIndexed(rows, key = { i, _ -> i }) { i, mc ->
                    ModuleCompletionRow(
                        item = mc,
                        expanded = expanded[i] == true,
                        onToggle = { expanded[i] = !(expanded[i] ?: false) },
                    )
                }
            }
        }
        PoDashboardSection.DOCUMENT_USAGE -> {
            val rows = payload.documentUsage.filter { it.title.contains(query, true) }
            LazyColumn(listModifier, contentPadding = padding, verticalArrangement = spacing) {
                if (rows.isEmpty()) item { EmptyRow() }
                items(rows, key = { it.documentId }) { doc ->
                    DocumentUsageListRow(row = doc, onClick = { onOpenDocument(doc.documentId) })
                }
            }
        }
        PoDashboardSection.SEARCHED_EXISTING, PoDashboardSection.SEARCHED_SUGGESTED -> {
            val rows = payload.topQueries.filter { it.text.contains(query, true) }
            val onClick: (String) -> Unit =
                if (section == PoDashboardSection.SEARCHED_EXISTING) onOpenSearchedModule else onOpenSuggestion
            LazyColumn(listModifier, contentPadding = padding, verticalArrangement = spacing) {
                if (rows.isEmpty()) item { EmptyRow() }
                items(rows, key = { it.id ?: "${it.rank}-${it.text}" }) { q ->
                    TopQueryRow(
                        query = q,
                        onClick = q.id?.let { id -> { onClick(id) } },
                        modifier = Modifier.poCard(),
                    )
                }
            }
        }
    }
}

@Composable
private fun EmptyRow() {
    Text(
        text = stringResource(R.string.po_section_empty),
        color = MutedText,
        style = MaterialTheme.typography.bodyMedium,
        textAlign = TextAlign.Center,
        modifier = Modifier.fillMaxWidth().padding(vertical = 32.dp),
    )
}

private val Sk_FILTER_LABELS = listOf(
    R.string.po_filter_all,
    R.string.po_filter_active,
    R.string.po_filter_inactive,
    R.string.po_filter_chatbot,
)

private fun PoDashboardSection.hasStatusFilter(): Boolean =
    this == PoDashboardSection.MY_SKS || this == PoDashboardSection.REFRESHERS

@StringRes
private fun PoDashboardSection.titleRes(): Int = when (this) {
    PoDashboardSection.MY_SKS -> R.string.po_section_my_sks
    PoDashboardSection.REFRESHERS -> R.string.po_section_refreshers_completed
    PoDashboardSection.MODULE_COMPLETION -> R.string.po_section_module_completion
    PoDashboardSection.SEARCHED_EXISTING -> R.string.po_section_top_searched_existing_modules
    PoDashboardSection.SEARCHED_SUGGESTED -> R.string.po_section_top_searched_suggested_modules
    PoDashboardSection.DOCUMENT_USAGE -> R.string.po_section_document_usage
}

@StringRes
private fun PoDashboardSection.searchHintRes(): Int = when (this) {
    PoDashboardSection.MY_SKS, PoDashboardSection.REFRESHERS -> R.string.po_search_hint
    PoDashboardSection.DOCUMENT_USAGE -> R.string.po_search_hint_documents
    else -> R.string.po_search_hint_modules
}
