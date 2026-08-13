package com.medtroniclabs.microcoaching.ui.badges

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Lock
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.medtroniclabs.microcoaching.R
import com.medtroniclabs.microcoaching.ui.badges.components.BadgeArtwork
import com.medtroniclabs.microcoaching.ui.theme.MutedText
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

/** Screen margin on a milestone label's outer side. */
private val EdgePadding = 20.dp

/** Breathing room between a label's text edge and the medallion it points at. */
private val LabelGap = 12.dp

/**
 * The "Your Journey" learning-path screen, opened from the Badges tab's
 * [com.medtroniclabs.microcoaching.ui.badges.components.YourJourneyBanner]. Renders the
 * milestones as a winding path — each node the badge medallion, joined by a connector
 * coloured by the reached milestone's state (green earned, grey locked).
 *
 * Hosted in-place inside [BadgesTab] (not a nav route), so [onBack] returns to the badge
 * grid; the coaching chat FAB keeps overlaying as on any Badges-tab content.
 */
@Composable
fun YourJourneyScreen(
    snapshot: BadgesSnapshot,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
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
            JourneyPath(milestones = snapshot.milestones)
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
private fun JourneyPath(milestones: List<JourneyMilestone>) {
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
                )
            }
        }
    }
}

/**
 * One path row: the badge node on its ¼ / ¾ column, and the label filling the space from the
 * screen edge up to the node.
 *
 * The node's centre is placed by fraction rather than by layout weight so it stays on the
 * column the connectors are drawn between. The label then takes whatever is left on the
 * node's inner side and aligns its text toward the node — so the text reads as belonging to
 * that badge rather than sitting against the far margin.
 */
@Composable
private fun MilestoneRow(
    milestone: JourneyMilestone,
    nodeOnRight: Boolean,
) {
    BoxWithConstraints(
        modifier = Modifier
            .fillMaxWidth()
            .height(RowHeight),
    ) {
        val nodeCentre = maxWidth * if (nodeOnRight) 1f - NodeColumnFraction else NodeColumnFraction
        val nodeStart = nodeCentre - NodeSize / 2
        val nodeEnd = nodeCentre + NodeSize / 2
        val labelStart = if (nodeOnRight) EdgePadding else nodeEnd + LabelGap
        val labelEnd = if (nodeOnRight) nodeStart - LabelGap else maxWidth - EdgePadding

        MilestoneLabel(
            milestone = milestone,
            alignTowardEnd = nodeOnRight,
            modifier = Modifier
                .align(Alignment.CenterStart)
                .offset(x = labelStart)
                .width((labelEnd - labelStart).coerceAtLeast(0.dp)),
        )
        NodeCell(
            milestone = milestone,
            modifier = Modifier
                .align(Alignment.CenterStart)
                .offset(x = nodeStart),
        )
    }
}

/**
 * The badge medallion with a lock marker when locked. The marker is the one thing allowed to
 * break the medallion's circle — [BadgeArtwork] clips the artwork itself to it.
 */
@Composable
private fun NodeCell(milestone: JourneyMilestone, modifier: Modifier = Modifier) {
    Box(modifier = modifier.size(NodeSize)) {
        BadgeArtwork(
            imageUrl = milestone.imageUrl,
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

/**
 * The milestone's number and name, aligned toward the node it belongs to: text ends against a
 * node on the right, starts against a node on the left. Wrapped titles follow the same edge,
 * so a two-line name still reads as one block pointing at its badge.
 */
@Composable
private fun MilestoneLabel(
    milestone: JourneyMilestone,
    alignTowardEnd: Boolean,
    modifier: Modifier = Modifier,
) {
    val earned = milestone.state == BadgeState.EARNED
    val textAlign = if (alignTowardEnd) TextAlign.End else TextAlign.Start
    Column(
        modifier = modifier,
        horizontalAlignment = if (alignTowardEnd) Alignment.End else Alignment.Start,
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(
            text = milestone.code,
            style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.Bold),
            color = if (earned) SpiceGreen else MutedText,
            textAlign = textAlign,
            modifier = Modifier.fillMaxWidth(),
        )
        Text(
            text = milestone.title,
            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
            color = if (earned) SpiceNavy else MutedText,
            textAlign = textAlign,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}
