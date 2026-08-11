package com.medtroniclabs.microcoaching.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.NotificationsActive
import androidx.compose.material.icons.outlined.School
import androidx.compose.material.icons.outlined.SmartDisplay
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.medtroniclabs.microcoaching.MicroCoachingSDK
import com.medtroniclabs.microcoaching.R
import com.medtroniclabs.microcoaching.ui.SdkLocalizedTheme
import com.medtroniclabs.microcoaching.ui.theme.SpiceBlue
import kotlinx.coroutines.flow.MutableStateFlow

/** Bell indicator colour (matches the host's alert red). */
private val AlertRed = Color(0xFFD0342C)

/**
 * Self-contained "Coaching" tile for the SPICE home menu grid. The host drops this
 * into its grid via a `ComposeView` and only forwards [onClick]; the assignment
 * indicators (video + module bell, MED-I629) are observed internally from the
 * SDK's [MicroCoachingSDK.hasIncompleteAssignedVideos] /
 * [MicroCoachingSDK.hasIncompleteTrainingModules] flows. Visuals mirror the host's
 * other grid tiles so it blends in.
 */
@Composable
fun CoachingGridTile(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    label: String = stringResource(R.string.coaching_tile_label),
) {
    val sdk = remember { runCatching { MicroCoachingSDK.getInstance() }.getOrNull() }
    val hasVideos by (sdk?.hasIncompleteAssignedVideos ?: remember { MutableStateFlow(false) })
        .collectAsState()
    val hasModules by (sdk?.hasIncompleteTrainingModules ?: remember { MutableStateFlow(false) })
        .collectAsState()

    SdkLocalizedTheme {
        // `propagateMinConstraints` carries the parent's *min* width down so the tile
        // matches its sibling tiles in both layouts: an exact width (phone grid column,
        // tablet fixed-width flex item) arrives as min == max so the card fills it; a
        // content spec arrives as min == 0 so the card wraps. Outer padding leaves room
        // for the elevation shadow (mirrors the host tiles' cardUseCompatPadding).
        Box(modifier = modifier.padding(6.dp), propagateMinConstraints = true) {
            Card(
                onClick = onClick,
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = Color.White),
                elevation = CardDefaults.cardElevation(defaultElevation = 6.dp),
            ) {
                // Box so the assignment indicators overlay the top-end corner without
                // affecting the tile's measured size (keeps the phone/tablet Flexbox
                // measurement contract identical to the sibling tiles).
                Box(modifier = Modifier.fillMaxSize()) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .fillMaxHeight()
                            .padding(vertical = 16.dp, horizontal = 16.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center,
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.School,
                            contentDescription = null,
                            tint = SpiceBlue,
                            modifier = Modifier.size(72.dp),
                        )
                        Spacer(Modifier.height(20.dp))
                        Text(
                            text = label.uppercase(),
                            style = MaterialTheme.typography.titleMedium
                                .copy(fontWeight = FontWeight.Bold),
                            color = SpiceBlue,
                            textAlign = TextAlign.Center,
                        )
                    }

                    AssignmentIndicators(
                        showVideo = hasVideos,
                        showModule = hasModules,
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .padding(10.dp),
                    )
                }
            }
        }
    }
}

/**
 * Top-end indicator row (MED-I629). Each icon is independent: the video play icon
 * shows while assigned videos remain unwatched; the bell shows while assigned
 * Training modules remain incomplete. Fixed sizes so appearing/disappearing never
 * reflows the grid cell.
 */
@Composable
private fun AssignmentIndicators(
    showVideo: Boolean,
    showModule: Boolean,
    modifier: Modifier = Modifier,
) {
    if (!showVideo && !showModule) return
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (showVideo) {
            Icon(
                imageVector = Icons.Outlined.SmartDisplay,
                contentDescription = stringResource(R.string.coaching_tile_new_videos),
                tint = SpiceBlue,
                modifier = Modifier.size(18.dp),
            )
        }
        if (showModule) {
            Icon(
                imageVector = Icons.Outlined.NotificationsActive,
                contentDescription = stringResource(R.string.coaching_tile_new_modules),
                tint = AlertRed,
                modifier = Modifier.size(18.dp),
            )
        }
    }
}
