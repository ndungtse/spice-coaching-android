package com.medtroniclabs.microcoaching.ui.screens.components

import android.text.format.Formatter
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.medtroniclabs.microcoaching.R

/**
 * Dismissible card offering the optional on-device model.
 *
 * Shown only on hardware that can host it, only while connected, and only before the user has
 * decided — accepting it starts a download of hundreds of megabytes, so offering it offline
 * would invite the one action that cannot then happen.
 *
 * The copy promises what the model actually delivers: answers phrased in everyday language.
 * It does not claim better or more accurate answers, because retrieval picks the same guidance
 * either way. Both the size and the fact that it can be removed are stated up front — they are
 * what the decision turns on, and finding them out afterwards is what makes an optional
 * download feel imposed.
 */
@Composable
fun LocalModelOfferCard(
    sizeBytes: Long?,
    onSetUp: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    Surface(
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.secondaryContainer,
        modifier = modifier.fillMaxWidth(),
    ) {
        // Asymmetric on purpose: the buttons carry their own touch-target padding, so a
        // symmetric inset stacks on top of it and leaves a visibly dead strip along the bottom.
        Column(
            modifier = Modifier.padding(
                start = 14.dp,
                end = 14.dp,
                top = 12.dp,
                bottom = 6.dp,
            ),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Filled.AutoAwesome,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSecondaryContainer,
                    modifier = Modifier.size(18.dp),
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = stringResource(R.string.local_model_offer_title),
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                )
            }
            Spacer(Modifier.height(6.dp))
            Text(
                // Falls back to a size-free sentence rather than printing the catalog's
                // approximation as fact: the figure is the main thing being consented to.
                text = if (sizeBytes != null && sizeBytes > 0L) {
                    stringResource(
                        R.string.local_model_offer_body_sized,
                        Formatter.formatShortFileSize(context, sizeBytes),
                    )
                } else {
                    stringResource(R.string.local_model_offer_body)
                },
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(2.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(4.dp, Alignment.End),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TextButton(
                    onClick = onDismiss,
                    contentPadding = CompactButtonPadding,
                    modifier = Modifier.heightIn(min = CompactButtonHeight),
                ) {
                    Text(stringResource(R.string.local_model_offer_dismiss))
                }
                // A filled Button, not FilledTonalButton: the tonal variant's container is
                // `secondaryContainer`, the same colour this card is painted with, so the
                // primary action rendered as bare text with no affordance at all.
                Button(
                    onClick = onSetUp,
                    contentPadding = CompactButtonPadding,
                    modifier = Modifier.heightIn(min = CompactButtonHeight),
                ) {
                    Text(stringResource(R.string.local_model_offer_accept))
                }
            }
        }
    }
}

/**
 * Button metrics for this card. Material's defaults are sized for standalone buttons; inside a
 * compact banner their vertical padding is what produces the dead strip under the actions.
 * Kept at 36dp so the row still reads as tappable — Compose keeps the 48dp touch target
 * regardless of the visual height.
 */
private val CompactButtonHeight = 36.dp
private val CompactButtonPadding = PaddingValues(horizontal = 16.dp, vertical = 0.dp)
