package com.medtroniclabs.microcoaching.ui.coaching

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.medtroniclabs.microcoaching.ui.theme.SpiceBlue
import com.medtroniclabs.microcoaching.ui.theme.SpiceNavy

/** One Coaching sub-tab: a leading icon and its label. */
data class SubTabChip(val icon: ImageVector, val label: String)

/** Soft neutral fill for unselected chips (no hard outline). */
private val UnselectedChip = Color(0xFFEFF1F6)

/**
 * Sub-tab chip row for the Coaching tab (Training | Refresher | Knowledge): centred,
 * icon-led rounded pills where the selected chip fills [SpiceBlue] with white icon + label
 * and the rest sit on a soft neutral fill with [SpiceNavy] content. A third shape,
 * deliberately distinct from [CoachingTopTabs] (full-width underline TabRow) and the
 * connected-track SegmentedToggle.
 */
@Composable
fun CoachingSubTabChips(
    chips: List<SubTabChip>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        chips.forEachIndexed { index, chip ->
            val selected = index == selectedIndex
            Surface(
                onClick = { onSelect(index) },
                modifier = Modifier.semantics {
                    role = Role.Tab
                    this.selected = selected
                },
                shape = RoundedCornerShape(50),
                color = if (selected) SpiceBlue else UnselectedChip,
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        imageVector = chip.icon,
                        contentDescription = null,
                        tint = if (selected) Color.White else SpiceNavy,
                        modifier = Modifier.size(16.dp),
                    )
                    Text(
                        text = chip.label,
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
                        color = if (selected) Color.White else SpiceNavy,
                   
                    )
                }
            }
        }
    }
}
