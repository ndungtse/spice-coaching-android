package com.medtroniclabs.microcoaching.ui.badges

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.medtroniclabs.microcoaching.R
import com.medtroniclabs.microcoaching.ui.badges.components.BadgeArtwork
import com.medtroniclabs.microcoaching.ui.theme.MutedText
import com.medtroniclabs.microcoaching.ui.theme.SpiceBlue
import com.medtroniclabs.microcoaching.ui.theme.SpiceGreen
import com.medtroniclabs.microcoaching.ui.theme.SpiceNavy
import com.medtroniclabs.microcoaching.ui.theme.SurfaceBackground
import com.medtroniclabs.microcoaching.ui.theme.SurfaceMuted

private val NodeSize = 76.dp
private val RowHeight = 152.dp
private val ConnectorWidth = 11.dp
private val CornerRadius = 30.dp

/**
 * Node column centres as a fraction of width — two inset columns (¼ and ¾) so the path reads
 * as a centred, rounded-rectangle snake rather than edge-to-edge diagonals.
 */
private const val NodeColumnFraction = 0.25f

/** Locked connector/segment colour — a light neutral so upcoming path recedes. */
private val LockedPath = Color(0xFFD7DCE4)

/**
 * The "Your Journey" learning-path screen, opened from the Badges tab's
 * [com.medtroniclabs.microcoaching.ui.badges.components.YourJourneyBanner]. Renders the
 * milestones as a winding path — each node the badge medallion, joined by a connector
 * coloured by the reached milestone's state (green earned → blue current → grey locked). The
 * current milestone shows a "Start lesson" action.
 *
 * Hosted in-place inside [BadgesTab] (not a nav route), so [onBack] returns to the badge
 * grid; the coaching chat FAB keeps overlaying as on any Badges-tab content.
 */
@Composable
fun YourJourneyScreen(
    snapshot: BadgesSnapshot,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    onStartMilestone: (JourneyMilestone) -> Unit = {},
) {
    Column(modifier = modifier.fillMaxSize().background(SurfaceBackground)) {
        JourneyHeader(
            title = stringResource(R.string.badges_journey_title),
            subtitle = stringResource(
                R.string.badges_milestones_count,
                snapshot.earnedCount,
                snapshot.totalCount,
            ),
            onBack = onBack,
        )
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState()),
        ) {
            JourneyPath(
                milestones = snapshot.milestones,
                onStartMilestone = onStartMilestone,
            )
            Spacer(Modifier.height(80.dp)) // chat-FAB clearance
        }
    }
}

/** Light content sub-header (back + title + milestone count), below the app's blue bar. */
@Composable
private fun JourneyHeader(
    title: String,
    subtitle: String,
    onBack: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(SurfaceMuted)
            .padding(end = 16.dp, top = 8.dp, bottom = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onBack) {
            Icon(
                imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                contentDescription = stringResource(R.string.common_back),
                tint = SpiceNavy,
            )
        }
        Column {
            Text(
                text = title,
                style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
                color = SpiceNavy,
            )
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodyMedium,
                color = MutedText,
            )
        }
    }
}

/**
 * Alternating-node path: two inset columns (¼ / ¾ of width) joined by rounded right-angle
 * connectors drawn behind the nodes and coloured by the reached milestone's state.
 */
@Composable
private fun JourneyPath(
    milestones: List<JourneyMilestone>,
    onStartMilestone: (JourneyMilestone) -> Unit,
) {
    Box(modifier = Modifier.fillMaxWidth()) {
        Canvas(modifier = Modifier.matchParentSize()) {
            val rowPx = RowHeight.toPx()
            val leftX = size.width * NodeColumnFraction
            val rightX = size.width * (1f - NodeColumnFraction)
            val radius = CornerRadius.toPx()
            fun columnX(index: Int) = if (index % 2 == 0) rightX else leftX // first node on the right

            for (i in 0 until milestones.size - 1) {
                val y0 = i * rowPx + rowPx / 2f
                val y1 = (i + 1) * rowPx + rowPx / 2f
                val x0 = columnX(i)
                val x1 = columnX(i + 1)
                val midY = (y0 + y1) / 2f
                val dir = if (x1 >= x0) 1f else -1f
                // Down out of this node, a rounded corner into a horizontal run, then a rounded
                // corner back down into the next node — a squared, rounded-corner snake.
                val path = Path().apply {
                    moveTo(x0, y0)
                    lineTo(x0, midY - radius)
                    quadraticTo(x0, midY, x0 + dir * radius, midY)
                    lineTo(x1 - dir * radius, midY)
                    quadraticTo(x1, midY, x1, midY + radius)
                    lineTo(x1, y1)
                }
                val color = when (milestones[i + 1].state) {
                    BadgeState.EARNED -> SpiceGreen
                    BadgeState.CURRENT -> SpiceBlue
                    BadgeState.LOCKED -> LockedPath
                }
                drawPath(
                    path = path,
                    color = color,
                    style = Stroke(width = ConnectorWidth.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round),
                )
            }
        }

        Column(modifier = Modifier.fillMaxWidth()) {
            milestones.forEachIndexed { index, milestone ->
                MilestoneRow(
                    milestone = milestone,
                    nodeOnRight = index % 2 == 0,
                    onStart = { onStartMilestone(milestone) },
                )
            }
        }
    }
}

/**
 * One path row split into two equal halves — the badge node centred in one, its
 * code/title/action in the other — so nodes land on the ¼ / ¾ columns the connectors run
 * between and the text fills the space toward the centre.
 */
@Composable
private fun MilestoneRow(
    milestone: JourneyMilestone,
    nodeOnRight: Boolean,
    onStart: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(RowHeight),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (nodeOnRight) {
            MilestoneLabel(milestone, onStart, Modifier.weight(1f).padding(start = 20.dp, end = 8.dp))
            NodeCell(milestone, Modifier.weight(1f))
        } else {
            NodeCell(milestone, Modifier.weight(1f))
            MilestoneLabel(milestone, onStart, Modifier.weight(1f).padding(start = 8.dp, end = 20.dp))
        }
    }
}

/** The badge medallion centred in its half of the row, with a lock marker when locked. */
@Composable
private fun NodeCell(milestone: JourneyMilestone, modifier: Modifier = Modifier) {
    Box(modifier = modifier, contentAlignment = Alignment.Center) {
        Box(modifier = Modifier.size(NodeSize)) {
            BadgeArtwork(
                image = milestone.image,
                state = milestone.state,
                contentDescription = milestone.title,
                size = NodeSize,
                showRing = true,
            )
            if (milestone.state == BadgeState.LOCKED) {
                Box(
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .size(24.dp)
                        .border(2.dp, Color.White, CircleShape)
                        .background(Color(0xFFE4E8EF), CircleShape),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(Icons.Filled.Lock, null, tint = MutedText, modifier = Modifier.size(13.dp))
                }
            }
        }
    }
}

@Composable
private fun MilestoneLabel(
    milestone: JourneyMilestone,
    onStart: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val accent = when (milestone.state) {
        BadgeState.EARNED -> SpiceGreen
        BadgeState.CURRENT -> SpiceBlue
        BadgeState.LOCKED -> MutedText
    }
    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.Start,
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(
            text = milestone.code,
            style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.Bold),
            color = accent,
        )
        Text(
            text = milestone.title,
            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
            color = if (milestone.state == BadgeState.LOCKED) MutedText else SpiceNavy,
        )
        if (milestone.state == BadgeState.CURRENT) {
            Button(
                onClick = onStart,
                shape = RoundedCornerShape(50),
                colors = ButtonDefaults.buttonColors(containerColor = SpiceBlue),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
            ) {
                Text(
                    text = stringResource(R.string.journey_start_lesson),
                    color = Color.White,
                    style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.SemiBold),
                )
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                    contentDescription = null,
                    tint = Color.White,
                    modifier = Modifier.size(18.dp),
                )
            }
        }
    }
}
