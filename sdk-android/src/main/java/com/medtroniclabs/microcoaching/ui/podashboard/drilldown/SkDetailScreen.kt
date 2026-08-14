package com.medtroniclabs.microcoaching.ui.podashboard.drilldown

import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.outlined.Circle
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
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
import com.medtroniclabs.microcoaching.ui.podashboard.SkDetail
import com.medtroniclabs.microcoaching.ui.podashboard.SkDetailUiState
import com.medtroniclabs.microcoaching.ui.podashboard.SkDetailViewModel
import com.medtroniclabs.microcoaching.ui.podashboard.SkModuleStatus
import com.medtroniclabs.microcoaching.ui.podashboard.SkStatus
import com.medtroniclabs.microcoaching.ui.podashboard.TopQuery
import com.medtroniclabs.microcoaching.ui.podashboard.components.MutedText
import com.medtroniclabs.microcoaching.ui.podashboard.components.StatusGreen
import com.medtroniclabs.microcoaching.ui.podashboard.components.StatusGreenBg
import com.medtroniclabs.microcoaching.ui.podashboard.components.poCard
import com.medtroniclabs.microcoaching.ui.theme.SpiceBlue
import com.medtroniclabs.microcoaching.ui.theme.SpiceBlueContainer
import com.medtroniclabs.microcoaching.ui.theme.SpiceBlueDark
import com.medtroniclabs.microcoaching.ui.theme.SurfaceMuted

private val HeaderGradientStart = Color(0xFF1E40AF)
private val HeaderGradientEnd = Color(0xFF2563EB)

/** "My SK" — one SK's profile: summary metrics, module checklist, activity, top queries. */
@Composable
fun SkDetailScreen(skId: String, onBack: () -> Unit, onHome: () -> Unit) {
    val vm: SkDetailViewModel = viewModel(factory = SkDetailViewModel.factory(skId))
    val state by vm.uiState.collectAsState()
    val networkAvailable by vm.networkAvailable.collectAsState()

    Column(modifier = Modifier.fillMaxSize().background(SurfaceMuted)) {
        when (val s = state) {
            is SkDetailUiState.Ready -> SkDetailHeader(s.detail, onBack, onHome)
            else -> SdkScreenHeader(title = stringResource(R.string.po_drilldown_sk), onBack = onBack, onHome = onHome)
        }
        Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
            when (val s = state) {
                is SkDetailUiState.Loading -> CenterProgress()
                is SkDetailUiState.Error ->
                    DashboardErrorState(offline = !networkAvailable, message = s.message, onRetry = vm::retry, isAuth = s.isAuth)
                is SkDetailUiState.Ready -> SkDetailBody(s.detail)
            }
        }
    }
}

@Composable
private fun SkDetailBody(d: SkDetail) {
    Column(modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)) {
        // Streak dropped (not backed by the API); the two real stats fill the width.
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            SummaryCard("${d.modulesDone}/${d.modulesTotal}", stringResource(R.string.po_sk_summary_modules), Modifier.weight(1f))
            SummaryCard("${d.queries}", stringResource(R.string.po_sk_summary_queries), Modifier.weight(1f))
        }
        Spacer(Modifier.height(12.dp))
        DetailCard(stringResource(R.string.po_sk_summary_modules)) {
            if (d.modules.isEmpty()) EmptyCardText(stringResource(R.string.po_sk_no_modules))
            else d.modules.forEach { ModuleStatusRow(it) }
        }
        Spacer(Modifier.height(12.dp))
        DetailCard(stringResource(R.string.po_sk_section_activity)) {
            ActivityRow(stringResource(R.string.po_sk_last_chatbot), d.activity.lastChatbotUse)
            ActivityRow(stringResource(R.string.po_sk_last_module), d.activity.lastModule)
        }
        Spacer(Modifier.height(12.dp))
        DetailCard(stringResource(R.string.po_sk_section_top_queries)) {
            if (d.topQueries.isEmpty()) EmptyCardText(stringResource(R.string.po_sk_no_queries))
            else d.topQueries.forEach { TopQueryRow(it) }
        }
        Spacer(Modifier.height(64.dp))
    }
}

@Composable
private fun SkDetailHeader(d: SkDetail, onBack: () -> Unit, onHome: () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .background(Brush.horizontalGradient(listOf(HeaderGradientStart, HeaderGradientEnd)))
            .statusBarsPadding()
            .padding(horizontal = 8.dp, vertical = 8.dp),
    ) {
        Column {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = null, tint = Color.White)
                }
                Text(stringResource(R.string.po_drilldown_sk), color = Color.White.copy(alpha = 0.85f), style = MaterialTheme.typography.bodyMedium)
                Spacer(Modifier.weight(1f))
                IconButton(onClick = onHome) {
                    Icon(Icons.Filled.Home, contentDescription = null, tint = Color.White)
                }
            }
            Row(
                modifier = Modifier.padding(start = 8.dp, end = 8.dp, top = 4.dp, bottom = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                AvatarCircle(d.name, size = 64.dp, containerColor = Color.White.copy(alpha = 0.2f), contentColor = Color.White)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(d.name, color = Color.White, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleLarge)
                    /*Text(
                        text = d.location.ifBlank { stringResource(R.string.po_na) },
                        color = Color.White.copy(alpha = 0.8f),
                        style = MaterialTheme.typography.bodyMedium,
                    )*/
                }
                Text(
                    text = stringResource(skStatusLabel(d.status)),
                    color = Color.White,
                    fontWeight = FontWeight.SemiBold,
                    style = MaterialTheme.typography.labelSmall,
                    modifier = Modifier
                        .clip(RoundedCornerShape(percent = 50))
                        .background(Color.White.copy(alpha = 0.2f))
                        .padding(horizontal = 12.dp, vertical = 6.dp),
                )
            }
        }
    }
}

@Composable
private fun SummaryCard(value: String, label: String, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.poCard().padding(vertical = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(value, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, color = SpiceBlue)
        Spacer(Modifier.height(4.dp))
        Text(label, style = MaterialTheme.typography.labelSmall, color = MutedText)
    }
}

@Composable
private fun DetailCard(title: String, content: @Composable ColumnScope.() -> Unit) {
    Column(modifier = Modifier.fillMaxWidth().poCard().padding(16.dp)) {
        Text(title, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.bodyLarge)
        Spacer(Modifier.height(8.dp))
        content()
    }
}

@Composable
private fun ModuleStatusRow(m: SkModuleStatus) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = if (m.done) Icons.Filled.CheckCircle else Icons.Outlined.Circle,
            contentDescription = null,
            tint = if (m.done) StatusGreen else MutedText,
        )
        Spacer(Modifier.width(12.dp))
        Text(m.name, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
        val (labelRes, fg, bg) = if (m.done) {
            Triple(R.string.po_sk_module_done, StatusGreen, StatusGreenBg)
        } else {
            Triple(R.string.po_sk_module_pending, MutedText, Color(0xFFEFEFF3))
        }
        Text(
            text = stringResource(labelRes),
            color = fg,
            fontWeight = FontWeight.SemiBold,
            style = MaterialTheme.typography.labelSmall,
            modifier = Modifier.clip(RoundedCornerShape(percent = 50)).background(bg).padding(horizontal = 10.dp, vertical = 4.dp),
        )
    }
}

/**
 * One "Activity" key/value line, split strictly down the middle.
 *
 * Both halves are weighted rather than laid out with `SpaceBetween`: an unweighted
 * value takes whatever width it wants first, so a long module title (Bengali titles
 * routinely run past a line) squeezed the label into a ragged column instead of
 * wrapping itself. Equal weights give the value a fixed half to wrap inside, and
 * keep the labels aligned down the card whatever the values do.
 */
@Composable
private fun ActivityRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            text = label,
            color = MutedText,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.weight(1f),
        )
        Text(
            text = value.ifBlank { stringResource(R.string.po_na) },
            fontWeight = FontWeight.Bold,
            style = MaterialTheme.typography.bodyMedium,
            textAlign = TextAlign.End,
            modifier = Modifier.weight(1f),
        )
    }
}

/** Muted placeholder shown inside a [DetailCard] when its list has no data. */
@Composable
private fun EmptyCardText(message: String) {
    Text(
        text = message,
        color = MutedText,
        style = MaterialTheme.typography.bodyMedium,
        modifier = Modifier.padding(vertical = 8.dp),
    )
}

@Composable
private fun TopQueryRow(q: TopQuery) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier.width(24.dp).height(24.dp).clip(RoundedCornerShape(percent = 50)).background(SpiceBlueContainer),
            contentAlignment = Alignment.Center,
        ) {
            Text("${q.rank}", color = SpiceBlueDark, fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.labelSmall)
        }
        Spacer(Modifier.width(12.dp))
        Text(q.text, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
        Text("${q.count}", color = SpiceBlue, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.bodyMedium)
    }
}

@StringRes
private fun skStatusLabel(status: SkStatus): Int = when (status) {
    SkStatus.ACTIVE -> R.string.po_status_active
    SkStatus.NEEDS_ATTENTION -> R.string.po_status_needs_attention
    SkStatus.INACTIVE -> R.string.po_status_inactive
}
