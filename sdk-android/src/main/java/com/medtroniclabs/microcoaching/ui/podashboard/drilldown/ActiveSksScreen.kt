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
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.medtroniclabs.microcoaching.R
import com.medtroniclabs.microcoaching.ui.common.AvatarCircle
import com.medtroniclabs.microcoaching.ui.common.CenterProgress
import com.medtroniclabs.microcoaching.ui.common.SdkScreenHeader
import com.medtroniclabs.microcoaching.ui.podashboard.PODashboardUiState
import com.medtroniclabs.microcoaching.ui.podashboard.PODashboardViewModel
import com.medtroniclabs.microcoaching.ui.podashboard.SkStatus
import com.medtroniclabs.microcoaching.ui.podashboard.SkSummary
import com.medtroniclabs.microcoaching.ui.podashboard.components.MutedText
import com.medtroniclabs.microcoaching.ui.podashboard.components.poCard
import com.medtroniclabs.microcoaching.ui.podashboard.components.statusBg
import com.medtroniclabs.microcoaching.ui.podashboard.components.statusFg
import com.medtroniclabs.microcoaching.ui.theme.SurfaceMuted

private val DividerColor = Color(0xFFEFEFF3)

/** "Active this week" — SKs grouped by status (Active / Needs attention / Inactive). */
/**
 * SKs for one KPI card, scoped to a single [status] (responsive vs non-responsive) — the card
 * the PO tapped decides which. Only that group renders; an empty group shows a message rather
 * than the other status's SKs.
 */
@Composable
fun ActiveSksScreen(
    chwId: String,
    status: SkStatus,
    onBack: () -> Unit,
    onHome: () -> Unit,
    onOpenSkDetail: (String) -> Unit,
) {
    val vm: PODashboardViewModel = viewModel(factory = PODashboardViewModel.factory(chwId))
    val state by vm.uiState.collectAsState()
    val networkAvailable by vm.networkAvailable.collectAsState()

    Column(modifier = Modifier.fillMaxSize().background(SurfaceMuted)) {
        SdkScreenHeader(title = stringResource(headerTitleRes(status)), onBack = onBack, onHome = onHome)
        when (val s = state) {
            is PODashboardUiState.Loading -> CenterProgress()
            is PODashboardUiState.Error ->
                DashboardErrorState(offline = !networkAvailable, message = s.message, onRetry = vm::retry)
            is PODashboardUiState.Ready -> {
                val group = s.dashboard.sks.filter { it.status == status }
                Column(
                    modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
                ) {
                    if (group.isEmpty()) {
                        Text(
                            text = stringResource(R.string.po_group_no_sks),
                            color = MutedText,
                            style = MaterialTheme.typography.bodyMedium,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.fillMaxWidth().padding(vertical = 32.dp),
                        )
                    } else {
                        StatusGroup(groupTitleRes(status), status, group, onOpenSkDetail)
                    }
                    Spacer(Modifier.height(64.dp))
                }
            }
        }
    }
}

@StringRes
private fun headerTitleRes(status: SkStatus): Int = when (status) {
    SkStatus.ACTIVE -> R.string.po_drilldown_active
    SkStatus.INACTIVE -> R.string.po_metric_inactive
    SkStatus.NEEDS_ATTENTION -> R.string.po_status_needs_attention
}

@StringRes
private fun groupTitleRes(status: SkStatus): Int = when (status) {
    SkStatus.ACTIVE -> R.string.po_group_active
    SkStatus.INACTIVE -> R.string.po_group_inactive
    SkStatus.NEEDS_ATTENTION -> R.string.po_group_needs_attention
}

@Composable
private fun StatusGroup(
    @StringRes titleRes: Int,
    status: SkStatus,
    sks: List<SkSummary>,
    onOpenSkDetail: (String) -> Unit,
) {
    if (sks.isEmpty()) return
    Text(
        text = stringResource(titleRes, sks.size),
        color = statusFg(status),
        fontWeight = FontWeight.Bold,
        style = MaterialTheme.typography.labelMedium,
        modifier = Modifier.padding(top = 8.dp, bottom = 8.dp),
    )
    Column(modifier = Modifier.fillMaxWidth().poCard()) {
        sks.forEachIndexed { i, sk ->
            if (i > 0) HorizontalDivider(color = DividerColor)
            Row(
                modifier = Modifier.fillMaxWidth().clickable { onOpenSkDetail(sk.id) }.padding(horizontal = 16.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                AvatarCircle(sk.name, size = 44.dp, containerColor = statusBg(status), contentColor = statusFg(status))
                Spacer(Modifier.width(12.dp))
                Text(sk.name, modifier = Modifier.weight(1f), fontWeight = FontWeight.Bold, style = MaterialTheme.typography.bodyMedium)
                Text(sk.lastSeenLabel, color = MutedText, style = MaterialTheme.typography.bodySmall)
                Spacer(Modifier.width(4.dp))
                Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null, tint = MutedText)
            }
        }
    }
    Spacer(Modifier.height(16.dp))
}

