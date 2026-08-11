package com.medtroniclabs.microcoaching.ui.podashboard.drilldown

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.medtroniclabs.microcoaching.R
import com.medtroniclabs.microcoaching.ui.common.CenterProgress
import com.medtroniclabs.microcoaching.ui.common.SdkScreenHeader
import com.medtroniclabs.microcoaching.ui.podashboard.PODashboardUiState
import com.medtroniclabs.microcoaching.ui.podashboard.PODashboardViewModel
import com.medtroniclabs.microcoaching.ui.podashboard.components.ModuleCompletionRow
import com.medtroniclabs.microcoaching.ui.theme.SpiceBlue
import com.medtroniclabs.microcoaching.ui.theme.SurfaceMuted

/** "Modules Completed" — per-module accordion with per-SK check rows. */
@Composable
fun ModulesCompletedScreen(chwId: String, onBack: () -> Unit, onHome: () -> Unit) {
    val vm: PODashboardViewModel = viewModel(factory = PODashboardViewModel.factory(chwId))
    val state by vm.uiState.collectAsState()
    val networkAvailable by vm.networkAvailable.collectAsState()
    val expanded = remember { mutableStateMapOf<Int, Boolean>() }

    Column(modifier = Modifier.fillMaxSize().background(SurfaceMuted)) {
        SdkScreenHeader(title = stringResource(R.string.po_drilldown_modules), onBack = onBack, onHome = onHome)
        when (val s = state) {
            is PODashboardUiState.Loading -> CenterProgress()
            is PODashboardUiState.Error ->
                DashboardErrorState(offline = !networkAvailable, message = s.message, onRetry = vm::retry)
            is PODashboardUiState.Ready -> {
                val modules = s.dashboard.moduleCompletion
                Column(modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)) {
                    Text(
                        text = stringResource(R.string.po_modules_subtitle, modules.size, s.dashboard.sks.size),
                        color = SpiceBlue,
                        fontWeight = FontWeight.Bold,
                        style = MaterialTheme.typography.labelMedium,
                        modifier = Modifier.padding(vertical = 8.dp),
                    )
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        modules.forEachIndexed { index, mc ->
                            ModuleCompletionRow(
                                item = mc,
                                expanded = expanded[index] == true,
                                onToggle = { expanded[index] = !(expanded[index] ?: false) },
                            )
                        }
                    }
                    Spacer(Modifier.height(64.dp))
                }
            }
        }
    }
}
