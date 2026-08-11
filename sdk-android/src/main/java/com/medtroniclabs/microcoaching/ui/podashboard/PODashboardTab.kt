package com.medtroniclabs.microcoaching.ui.podashboard

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.medtroniclabs.microcoaching.R
import com.medtroniclabs.microcoaching.ui.common.CenterProgress
import com.medtroniclabs.microcoaching.ui.common.NoticeBanner
import com.medtroniclabs.microcoaching.ui.common.NoticeTone
import com.medtroniclabs.microcoaching.ui.podashboard.components.DateRangeSelector
import com.medtroniclabs.microcoaching.ui.podashboard.components.DocumentUsageListRow
import com.medtroniclabs.microcoaching.ui.podashboard.components.MetricCard
import com.medtroniclabs.microcoaching.ui.podashboard.components.ModuleCompletionRow
import com.medtroniclabs.microcoaching.ui.podashboard.components.MutedText
import com.medtroniclabs.microcoaching.ui.podashboard.components.RefresherCompletionRow
import com.medtroniclabs.microcoaching.ui.podashboard.components.SectionTitle
import com.medtroniclabs.microcoaching.ui.podashboard.components.ShowAllRow
import com.medtroniclabs.microcoaching.ui.podashboard.components.SkListRow
import com.medtroniclabs.microcoaching.ui.podashboard.components.TopQueriesCard
import com.medtroniclabs.microcoaching.ui.podashboard.components.poCard
import com.medtroniclabs.microcoaching.ui.theme.SpiceBlue
import com.medtroniclabs.microcoaching.util.shortDateTimeLabel

/**
 * PO Dashboard tab: period toggle, 4 KPI cards, searchable My-SKs list, module-completion
 * accordion, top queries, and per-SK refresher completion. Backed by [PODashboardViewModel].
 *
 * Resilient by section: the date-range picker is always shown, the whole body is
 * pull-to-refreshable, and one failing endpoint (e.g. the analytics "spine" returning
 * 502) shows an inline notice for its sections instead of blanking the tab — the
 * "Top Searched" sections still render if they loaded.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PODashboardTab(
    chwId: String,
    onOpenActiveSks: () -> Unit,
    onOpenChatbotUsage: () -> Unit,
    onOpenModulesCompleted: () -> Unit,
    onOpenSkDetail: (String) -> Unit,
    onOpenSearchedModule: (String) -> Unit,
    onOpenSuggestion: (String) -> Unit,
    onOpenDocument: (String) -> Unit,
    onShowAllSection: (PoDashboardSection, DateRange) -> Unit,
    modifier: Modifier = Modifier,
) {
    val vm: PODashboardViewModel = viewModel(factory = PODashboardViewModel.factory(chwId))
    val state by vm.uiState.collectAsState()
    val range by vm.range.collectAsState()
    val isRefreshing by vm.isRefreshing.collectAsState()
    val lastLoadedAt by vm.lastLoadedAt.collectAsState()
    val networkAvailable by vm.networkAvailable.collectAsState()
    val expandedModules = remember { mutableStateMapOf<Int, Boolean>() }

    PullToRefreshBox(
        isRefreshing = isRefreshing,
        onRefresh = { vm.refresh() },
        modifier = modifier.fillMaxSize(),
    ) {
        Column(modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
            // Date-range filter (From – To) — always visible, even on load/error.
            Column(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(stringResource(R.string.po_showing_data_for), style = MaterialTheme.typography.bodyMedium)
                DateRangeSelector(
                    fromMillis = range.fromMillis,
                    toMillis = range.toMillis,
                    onRangeChange = { from, to -> vm.selectRange(DateRange(from, to)) },
                    modifier = Modifier.fillMaxWidth(),
                    // Freeze the picker offline — offline shows the last-synced snapshot (AC5/D2).
                    enabled = networkAvailable,
                )
                // Freshness of the on-screen dashboard numbers — mirrors the
                // coaching header's "Last synced …" subtitle, but sourced from the
                // dashboard's own live loads (see PODashboardViewModel.lastLoadedAt).
                Text(
                    text = if (lastLoadedAt <= 0L) {
                        stringResource(R.string.modules_last_synced_never)
                    } else {
                        stringResource(R.string.modules_last_synced, shortDateTimeLabel(lastLoadedAt))
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MutedText,
                )
            }
            
            when (val s = state) {
                is PODashboardUiState.Loading -> CenterProgress()

                is PODashboardUiState.Error -> ErrorNotice(
                    if (!networkAvailable) stringResource(R.string.common_error_offline) else s.message,
                )

                is PODashboardUiState.Ready -> {
                    // Online but showing cached data → a refresh failed; tell the user.
                    if (networkAvailable && s.dashboard.fromCache) {
                        NoticeBanner(stringResource(R.string.common_couldnt_refresh))
                    }
                    DashboardBody(
                        dashboard = s.dashboard,
                        expandedModules = expandedModules,
                        onOpenActiveSks = onOpenActiveSks,
                        onOpenChatbotUsage = onOpenChatbotUsage,
                        onOpenModulesCompleted = onOpenModulesCompleted,
                        onOpenSkDetail = onOpenSkDetail,
                        onOpenSearchedModule = onOpenSearchedModule,
                        onOpenSuggestion = onOpenSuggestion,
                        onOpenDocument = onOpenDocument,
                        onShowAllSection = onShowAllSection,
                    )
                }
            }

            Spacer(Modifier.height(80.dp)) // chat-FAB clearance
        }
    }
}

/** How many rows a section shows on the tab before offering "Show all". */
private const val SECTION_PREVIEW_LIMIT = 5

@Composable
private fun DashboardBody(
    dashboard: PoDashboard,
    expandedModules: androidx.compose.runtime.snapshots.SnapshotStateMap<Int, Boolean>,
    onOpenActiveSks: () -> Unit,
    onOpenChatbotUsage: () -> Unit,
    onOpenModulesCompleted: () -> Unit,
    onOpenSkDetail: (String) -> Unit,
    onOpenSearchedModule: (String) -> Unit,
    onOpenSuggestion: (String) -> Unit,
    onOpenDocument: (String) -> Unit,
    onShowAllSection: (PoDashboardSection, DateRange) -> Unit,
) {
    val d = dashboard

    // Spine sections (KPIs, My SKs, module completion) — replaced by an inline notice
    // when team-activity failed but the rest of the dashboard loaded.
    if (d.spineError != null) {
        ErrorNotice(d.spineError)
    } else {
        // 4 KPI cards — horizontal scroller so the longer labels stay on one line.
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            d.metrics.forEach { metric ->
                MetricCard(
                    metric = metric,
                    onClick = {
                        when (metric.key) {
                            MetricKey.ACTIVE_NOW, MetricKey.INACTIVE -> onOpenActiveSks()
                            MetricKey.FINISHED_MODULES -> onOpenModulesCompleted()
                            MetricKey.CHATBOT_ENGAGED -> onOpenChatbotUsage()
                        }
                    },
                )
            }
        }

        Spacer(Modifier.height(16.dp))
        SectionTitle(stringResource(R.string.po_section_my_sks))
        Column(
            modifier = Modifier.padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            d.sks.take(SECTION_PREVIEW_LIMIT).forEach { sk ->
                SkListRow(sk = sk, onClick = { onOpenSkDetail(sk.id) })
            }
        }
        if (d.sks.size > SECTION_PREVIEW_LIMIT) {
            ShowAllRow(d.sks.size) { onShowAllSection(PoDashboardSection.MY_SKS, d.range) }
        }

        Spacer(Modifier.height(16.dp))
        SectionTitle(stringResource(R.string.po_section_module_completion))
        Column(
            modifier = Modifier.padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            d.moduleCompletion.take(SECTION_PREVIEW_LIMIT).forEachIndexed { index, mc ->
                ModuleCompletionRow(
                    item = mc,
                    expanded = expandedModules[index] == true,
                    onToggle = { expandedModules[index] = !(expandedModules[index] ?: false) },
                )
            }
        }
        if (d.moduleCompletion.size > SECTION_PREVIEW_LIMIT) {
            ShowAllRow(d.moduleCompletion.size) { onShowAllSection(PoDashboardSection.MODULE_COMPLETION, d.range) }
        }
    }

    // Chatbot module-search analytics. Loaded independently of the spine, so
    // they render even if team-activity failed above; each section shows an empty message
    // when there's no search activity in the range. Rows are tappable → drill-down
    // (view-only on PO mobile; Admin actions are web workflows).
    Spacer(Modifier.height(16.dp))
    SectionTitle(stringResource(R.string.po_section_top_searched_existing_modules))
    if (d.topSearchedExisting.isNotEmpty()) {
        TopQueriesCard(
            d.topSearchedExisting.take(SECTION_PREVIEW_LIMIT),
            modifier = Modifier.padding(horizontal = 16.dp),
            onItemClick = { it.id?.let(onOpenSearchedModule) },
        )
        if (d.topSearchedExistingTotal > SECTION_PREVIEW_LIMIT) {
            ShowAllRow(d.topSearchedExistingTotal) { onShowAllSection(PoDashboardSection.SEARCHED_EXISTING, d.range) }
        }
    } else {
        TopSearchedEmptyRow()
    }

    Spacer(Modifier.height(16.dp))
    SectionTitle(stringResource(R.string.po_section_top_searched_suggested_modules))
    if (d.topSearchedSuggested.isNotEmpty()) {
        TopQueriesCard(
            d.topSearchedSuggested.take(SECTION_PREVIEW_LIMIT),
            modifier = Modifier.padding(horizontal = 16.dp),
            onItemClick = { it.id?.let(onOpenSuggestion) },
        )
        if (d.topSearchedSuggestedTotal > SECTION_PREVIEW_LIMIT) {
            ShowAllRow(d.topSearchedSuggestedTotal) { onShowAllSection(PoDashboardSection.SEARCHED_SUGGESTED, d.range) }
        }
    } else {
        TopSearchedEmptyRow()
    }

    // Loaded independently of the spine like the sections above, so an outage
    // here leaves the rest of the tab intact.
    Spacer(Modifier.height(16.dp))
    SectionTitle(stringResource(R.string.po_section_document_usage))
    if (d.documentUsage.isNotEmpty()) {
        d.documentUsageSummary?.let { summary ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                DocumentUsageStat(stringResource(R.string.po_document_total_opens), summary.totalViews)
                DocumentUsageStat(stringResource(R.string.po_document_unique_documents), summary.uniqueDocuments)
                DocumentUsageStat(stringResource(R.string.po_document_unique_readers), summary.uniqueUsers)
            }
            Spacer(Modifier.height(8.dp))
        }
        Column(
            modifier = Modifier.padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            d.documentUsage.take(SECTION_PREVIEW_LIMIT).forEach { doc ->
                DocumentUsageListRow(row = doc, onClick = { onOpenDocument(doc.documentId) })
            }
        }
        if (d.documentUsageTotal > SECTION_PREVIEW_LIMIT) {
            ShowAllRow(d.documentUsageTotal) { onShowAllSection(PoDashboardSection.DOCUMENT_USAGE, d.range) }
        }
    } else {
        DocumentUsageEmptyRow()
    }

    if (d.spineError == null && d.sks.isNotEmpty()) {
        Spacer(Modifier.height(16.dp))
        SectionTitle(stringResource(R.string.po_section_refreshers_completed))
        Column(
            modifier = Modifier.padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            d.sks.take(SECTION_PREVIEW_LIMIT).forEach { sk -> RefresherCompletionRow(sk) }
        }
        if (d.sks.size > SECTION_PREVIEW_LIMIT) {
            ShowAllRow(d.sks.size) { onShowAllSection(PoDashboardSection.REFRESHERS, d.range) }
        }
    }
}

/** Empty-state row for a "Top Searched" section that has no activity in the range. */
@Composable
private fun TopSearchedEmptyRow() {
    Text(
        text = stringResource(R.string.po_section_empty),
        color = MutedText,
        style = MaterialTheme.typography.bodyMedium,
        textAlign = TextAlign.Center,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 24.dp),
    )
}

/** Empty-state row for the document-usage section when nothing was opened in the range. */
@Composable
private fun DocumentUsageEmptyRow() {
    Text(
        text = stringResource(R.string.po_document_empty_opens),
        color = MutedText,
        style = MaterialTheme.typography.bodyMedium,
        textAlign = TextAlign.Center,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 24.dp),
    )
}

/** One headline number in the document-usage summary strip (total / documents / readers). */
@Composable
private fun DocumentUsageStat(label: String, value: Int) {
    Column(
        modifier = Modifier.poCard().padding(horizontal = 16.dp, vertical = 12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = "$value",
            color = SpiceBlue,
            fontWeight = FontWeight.Bold,
            style = MaterialTheme.typography.titleLarge,
        )
        Text(text = label, color = MutedText, style = MaterialTheme.typography.labelMedium)
    }
}

/** Inline error card with a pull-to-refresh hint (used for full and spine-only failures). */
@Composable
private fun ErrorNotice(message: String) {
    NoticeBanner(
        text = message,
        tone = NoticeTone.Warning,
        hint = stringResource(R.string.po_error_pull_to_refresh),
    )
}
