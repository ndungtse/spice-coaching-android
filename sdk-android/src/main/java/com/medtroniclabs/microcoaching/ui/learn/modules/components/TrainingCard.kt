package com.medtroniclabs.microcoaching.ui.learn.modules.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.medtroniclabs.microcoaching.R
import com.medtroniclabs.microcoaching.ui.theme.SpiceBlue
import com.medtroniclabs.microcoaching.ui.theme.SpiceBlueContainer
import com.medtroniclabs.microcoaching.ui.theme.SpiceBlueDark

/**
 * Horizontally-scrolled card used in the Training row (module_type ==
 * "digital_proficiency"). Width 240.dp, height 220.dp per the design.
 *
 * Hero is a gradient placeholder block until real assets ship — see the
 * v0.3.2 plan's "Out of scope" section.
 *
 * @param title Module title to display, two-line ellipsis if long.
 * @param meta Pre-formatted meta line ("4 min · 3 questions").
 * @param progressFraction Quiz score as 0.0–1.0; drives the bottom progress bar.
 * @param thumbnailUrl Presigned thumbnail URL; falls back to the gradient hero when null.
 * @param showNewBadge When true, overlays a small red "NEW" pill (string
 *   [R.string.badge_new]) at the top-start corner of the thumbnail.
 * @param contentDomain Content-domain taxonomy ("clinical"/"digital"/"operational",
 *   null → Clinical); rendered as a [ContentDomainTag] at the top-end of the hero.
 * @param onClick Routes to the existing ModuleReady → LessonContent → Quiz flow.
 */
@Composable
fun TrainingCard(
    title: String,
    meta: String,
    progressFraction: Float,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    thumbnailUrl: String? = null,
    showNewBadge: Boolean = false,
    contentDomain: String? = null,
) {
    Card(
        onClick = onClick,
        modifier = modifier
            .fillMaxWidth()
            .height(220.dp),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = Color.White),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(110.dp),
            ) {
                ModuleThumbnail(
                    thumbnailUrl = thumbnailUrl,
                    contentDescription = title,
                    modifier = Modifier
                        .fillMaxSize()
                        .clip(RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp)),
                    fallback = {
                        Box(
                            modifier = Modifier
                                .matchParentSize()
                                .background(
                                    Brush.linearGradient(
                                        colors = listOf(SpiceBlueContainer, SpiceBlue.copy(alpha = 0.3f)),
                                    ),
                                ),
                        )
                    },
                )
                if (showNewBadge) {
                    TrainingNewBadge(
                        modifier = Modifier
                            .align(Alignment.TopStart)
                            .padding(8.dp),
                    )
                }
                ContentDomainTag(
                    contentDomain = contentDomain,
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(8.dp),
                )
            }
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(12.dp),
            ) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = meta,
                    style = MaterialTheme.typography.bodySmall,
                    color = Color(0xFF6B7280),
                    modifier = Modifier.padding(top = 4.dp),
                )
                Box(modifier = Modifier.weight(1f))
                LinearProgressIndicator(
                    progress = { progressFraction.coerceIn(0f, 1f) },
                    modifier = Modifier.fillMaxWidth(),
                    color = SpiceBlueDark,
                )
                Text(
                    text = "${(progressFraction.coerceIn(0f, 1f) * 100).toInt()}%",
                    style = MaterialTheme.typography.labelSmall,
                    color = SpiceBlueDark,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
        }
    }
}

/**
 * Small red "NEW" pill overlaid on a card thumbnail. Same shape/colour idiom as
 * `CriticalBadge` in RefresherTile.kt (4.dp rounded corners, solid red fill,
 * bold white label) — kept file-local here since the label text differs.
 */
@Composable
private fun TrainingNewBadge(modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(4.dp))
            .background(Color(0xFFB91C1C))
            .padding(horizontal = 6.dp, vertical = 2.dp),
    ) {
        Text(
            text = stringResource(R.string.badge_new),
            color = Color.White,
            style = MaterialTheme.typography.labelSmall.copy(
                fontWeight = FontWeight.Bold,
                letterSpacing = 0.5.sp,
            ),
        )
    }
}
