package com.medtroniclabs.microcoaching.ui.podashboard.drilldown

import androidx.annotation.StringRes
import androidx.compose.foundation.background
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
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.medtroniclabs.microcoaching.R
import com.medtroniclabs.microcoaching.ui.common.AvatarCircle
import com.medtroniclabs.microcoaching.ui.common.CenterProgress
import com.medtroniclabs.microcoaching.ui.common.SdkScreenHeader
import com.medtroniclabs.microcoaching.ui.podashboard.PODashboardUiState
import com.medtroniclabs.microcoaching.ui.podashboard.PODashboardViewModel
import com.medtroniclabs.microcoaching.ui.podashboard.SkSummary
import com.medtroniclabs.microcoaching.ui.podashboard.components.StatusGreen
import com.medtroniclabs.microcoaching.ui.podashboard.components.StatusGreenBg
import com.medtroniclabs.microcoaching.ui.podashboard.components.StatusRed
import com.medtroniclabs.microcoaching.ui.podashboard.components.StatusRedBg
import com.medtroniclabs.microcoaching.ui.podashboard.components.poCard
import com.medtroniclabs.microcoaching.ui.theme.SurfaceMuted

private val DividerColor = Color(0xFFEFEFF3)

/** "Chatbot Usage" — SKs grouped into Using / Not using, with query counts. */
@Composable
fun ChatbotUsageScreen(chwId: String, onBack: () -> Unit, onHome: () -> Unit) {
    val vm: PODashboardViewModel = viewModel(factory = PODashboardViewModel.factory(chwId))
    val state by vm.uiState.collectAsState()
    val networkAvailable by vm.networkAvailable.collectAsState()

    Column(modifier = Modifier.fillMaxSize().background(SurfaceMuted)) {
        SdkScreenHeader(title = stringResource(R.string.po_drilldown_chatbot), onBack = onBack, onHome = onHome)
        when (val s = state) {
            is PODashboardUiState.Loading -> CenterProgress()
            is PODashboardUiState.Error ->
                DashboardErrorState(offline = !networkAvailable, message = s.message, onRetry = vm::retry)
            is PODashboardUiState.Ready -> {
                val sks = s.dashboard.sks
                Column(modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)) {
                    UsageGroup(R.string.po_group_using_chatbot, sks.filter { it.queries > 0 }, usesChatbot = true)
                    UsageGroup(R.string.po_group_not_using_chatbot, sks.filter { it.queries == 0 }, usesChatbot = false)
                    Spacer(Modifier.height(64.dp))
                }
            }
        }
    }
}

@Composable
private fun UsageGroup(@StringRes titleRes: Int, sks: List<SkSummary>, usesChatbot: Boolean) {
    if (sks.isEmpty()) return
    val fg = if (usesChatbot) StatusGreen else StatusRed
    val bg = if (usesChatbot) StatusGreenBg else StatusRedBg
    Text(
        text = stringResource(titleRes, sks.size),
        color = fg,
        fontWeight = FontWeight.Bold,
        style = MaterialTheme.typography.labelMedium,
        modifier = Modifier.padding(top = 8.dp, bottom = 8.dp),
    )
    Column(modifier = Modifier.fillMaxWidth().poCard()) {
        sks.forEachIndexed { i, sk ->
            if (i > 0) HorizontalDivider(color = DividerColor)
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                AvatarCircle(sk.name, size = 44.dp, containerColor = bg, contentColor = fg)
                Spacer(Modifier.width(12.dp))
                Text(sk.name, modifier = Modifier.weight(1f), fontWeight = FontWeight.Bold, style = MaterialTheme.typography.bodyMedium)
                Text(
                    text = stringResource(R.string.po_queries_count, sk.queries),
                    color = fg,
                    fontWeight = FontWeight.SemiBold,
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }
    }
    Spacer(Modifier.height(16.dp))
}
