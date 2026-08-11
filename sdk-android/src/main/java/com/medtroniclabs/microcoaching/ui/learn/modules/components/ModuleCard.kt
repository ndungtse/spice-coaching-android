package com.medtroniclabs.microcoaching.ui.learn.modules.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.medtroniclabs.microcoaching.R
import com.medtroniclabs.microcoaching.ui.learn.LearnModule

/**
 * Card tile for a learning module. Used in the legacy [ModuleListContent]
 * single-column list inside [ModuleReadyScreen].
 *
 * @param module The module to display.
 * @param onClick Tap handler — pass null to render a non-clickable card
 *   (e.g. when displayed inside [FocusedModuleContent]).
 */
@Composable
fun ModuleCard(
    module: LearnModule,
    onClick: (() -> Unit)?,
    modifier: Modifier = Modifier,
) {
    Card(
        modifier = modifier
            .fillMaxWidth()
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = Color.White),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
    ) {
      Column {
        if (!module.thumbnailUrl.isNullOrBlank()) {
            ModuleThumbnail(
                thumbnailUrl = module.thumbnailUrl,
                contentDescription = module.title,
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(16f / 9f)
                    .clip(RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp)),
            )
        }
        Column(modifier = Modifier.padding(20.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.Top,
            ) {
                Text(
                    text = module.title,
                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                    color = Color(0xFF0A3D27),
                    modifier = Modifier.weight(1f),
                )
                if (module.status == "completed") {
                    Icon(
                        imageVector = Icons.Default.CheckCircle,
                        contentDescription = stringResource(R.string.learn_completed_cd),
                        tint = Color(0xFF1B6B4A),
                        modifier = Modifier.size(20.dp),
                    )
                }
            }

            if (module.body.isNotBlank()) {
                Spacer(Modifier.height(6.dp))
                Text(
                    text = module.body,
                    style = MaterialTheme.typography.bodySmall,
                    color = Color(0xFF555555),
                    maxLines = 3,
                )
            }

            Spacer(Modifier.height(12.dp))

            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                DomainChip(domain = module.clinicalDomain)
                StatusChip(status = module.status)
            }
        }
      }
    }
}

/** Domain label pill — maps backend domain codes to localised strings. */
@Composable
fun DomainChip(domain: String) {
    val label = when (domain) {
        "hypertension"   -> stringResource(R.string.domain_hypertension)
        "diabetes"       -> stringResource(R.string.domain_diabetes)
        "maternal_health" -> stringResource(R.string.domain_maternal_health)
        "emergency"      -> stringResource(R.string.domain_emergency)
        "spice_digital"  -> stringResource(R.string.domain_spice_digital)
        else             -> domain
    }
    Text(
        text = label,
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onPrimaryContainer,
        modifier = Modifier
            .background(
                color = MaterialTheme.colorScheme.primaryContainer,
                shape = RoundedCornerShape(6.dp),
            )
            .padding(horizontal = 8.dp, vertical = 3.dp),
    )
}

@Composable
fun StatusChip(status: String) {
    val (label, bg, fg) = when (status) {
        "completed"   -> Triple(stringResource(R.string.status_completed), Color(0xFFD7F0E5), Color(0xFF0A3D27))
        "in_progress" -> Triple(stringResource(R.string.status_in_progress), Color(0xFFFFF3CD), Color(0xFF856404))
        else -> return
    }
    Text(
        text = label,
        style = MaterialTheme.typography.labelSmall,
        color = fg,
        modifier = Modifier
            .background(color = bg, shape = RoundedCornerShape(6.dp))
            .padding(horizontal = 8.dp, vertical = 3.dp),
    )
}
