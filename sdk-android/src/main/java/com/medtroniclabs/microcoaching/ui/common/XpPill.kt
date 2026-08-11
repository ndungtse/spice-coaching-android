package com.medtroniclabs.microcoaching.ui.common

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.medtroniclabs.microcoaching.R
import com.medtroniclabs.microcoaching.ui.theme.SpiceBlueContainer
import com.medtroniclabs.microcoaching.ui.theme.SpiceBlueDark

/** Rounded XP badge, e.g. "1,840 XP". */
@Composable
fun XpPill(xp: Int, modifier: Modifier = Modifier) {
    Text(
        text = stringResource(R.string.leaderboard_xp_value, "%,d".format(xp)),
        color = SpiceBlueDark,
        fontWeight = FontWeight.SemiBold,
        style = MaterialTheme.typography.labelSmall,
        modifier = modifier
            .clip(RoundedCornerShape(percent = 50))
            .background(SpiceBlueContainer)
            .padding(horizontal = 12.dp, vertical = 6.dp),
    )
}
