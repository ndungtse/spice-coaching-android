package com.medtroniclabs.microcoaching.ui.learn.modules.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.medtroniclabs.microcoaching.R
import com.medtroniclabs.microcoaching.ui.learn.LearnModule
import com.medtroniclabs.microcoaching.ui.theme.SpiceNavy

/**
 * Soft background tints cycled across the Practice Zone row (blue / peach / green), matching
 * the module design mock. Callers pick by position:
 * `PracticeZonePalette[index % PracticeZonePalette.size]`.
 */
val PracticeZonePalette = listOf(
    Color(0xFFE7F0FB), // soft blue
    Color(0xFFFBEEE3), // soft peach
    Color(0xFFE7F4EC), // soft green
)

/**
 * Square practice/refresher tile for the horizontal "Practice Zone" row of
 * [com.medtroniclabs.microcoaching.ui.coaching.RefresherSubTab], matching the design mock:
 * a soft [containerColor] fill with a white type pill top-start, the title, and a circular
 * arrow pinned bottom-end. Severity is optional — [CriticalBadge] (when
 * [LearnModule.clinicalDomain] is "emergency", same gate as [RefresherList]) and
 * [SeverityChip] sit at bottom-start opposite the arrow, and the row is just the arrow when
 * neither applies.
 *
 * @param module The refresher/practice module to present.
 * @param onClick Invoked when the whole card is tapped.
 * @param containerColor Soft card fill; cycle [PracticeZonePalette] by position.
 */
@Composable
fun PracticeZoneCard(
    module: LearnModule,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    containerColor: Color = PracticeZonePalette[0],
) {
    val isCritical = module.clinicalDomain.equals("emergency", ignoreCase = true)

    Card(
        onClick = onClick,
        // Square (1:1) tile for the horizontal Practice Zone row.
        modifier = modifier.width(160.dp).aspectRatio(1f),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = containerColor),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
    ) {
        Column(
            modifier = Modifier.fillMaxSize().padding(14.dp),
            verticalArrangement = Arrangement.SpaceBetween,
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    // A quiz-targeted morning card is always a Quiz, even before the
                    // quiz blob is hydrated (questionCount is 0 on the slim list model).
                    TypeTag(isQuiz = module.targetQuizId != null || module.questionCount > 0)
                    ContentDomainTag(module.contentDomain)
                }
                Text(
                    text = module.title,
                    style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                    color = SpiceNavy,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                // Severity/critical is optional — this row may be just the arrow.
                // Row(
                //     horizontalArrangement = Arrangement.spacedBy(6.dp),
                //     verticalAlignment = Alignment.CenterVertically,
                // ) {
                //     if (isCritical) CriticalBadge()
                //     SeverityChip(module.severity)
                // }
                Box(
                    modifier = Modifier.size(38.dp).background(Color.White, CircleShape),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.ArrowForward,
                        contentDescription = null,
                        tint = SpiceNavy,
                        modifier = Modifier.size(18.dp),
                    )
                }
            }
        }
    }
}

/** White pill showing the refresher type — "Quiz" when the module has questions, else "Learning". */
@Composable
private fun TypeTag(isQuiz: Boolean, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(50))
            .background(Color.White)
            .padding(horizontal = 10.dp, vertical = 4.dp),
    ) {
        Text(
            text = stringResource(
                if (isQuiz) R.string.refresher_type_quiz else R.string.practice_zone_tag_learning,
            ),
            color = SpiceNavy,
            style = MaterialTheme.typography.labelMedium.copy(
                fontWeight = FontWeight.SemiBold,
                letterSpacing = 0.3.sp,
            ),
        )
    }
}
