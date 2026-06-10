package com.medtroniclabs.microcoaching.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.HelpOutline
import androidx.compose.material.icons.outlined.AccessTime
import androidx.compose.material.icons.outlined.MenuBook
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.medtroniclabs.microcoaching.R
import com.medtroniclabs.microcoaching.ui.SdkLocalizedTheme
import com.medtroniclabs.microcoaching.ui.theme.SpiceBlue
import com.medtroniclabs.microcoaching.ui.theme.SpiceBlueDark

/**
 * Home-screen morning refresher banner. Replaces [LearnCard] when morning
 * cards are available.
 *
 * Shows module title, lesson-card count, quiz question count, and estimated
 * duration. Start → begins the full lesson-card → quiz flow. Skip → dismisses.
 *
 * @param cardCount Number of lesson cards in the module.
 * @param questionCount Number of quiz questions.
 * @param estimatedMinutes Estimated completion time.
 */
@Composable
fun MorningCard(
    moduleTitle: String,
    cardCount: Int,
    questionCount: Int,
    estimatedMinutes: Int,
    onStart: () -> Unit,
    onSkip: () -> Unit,
    modifier: Modifier = Modifier,
) {
    SdkLocalizedTheme {
        Column(
            modifier = modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(16.dp))
                .background(Brush.linearGradient(listOf(SpiceBlue, SpiceBlueDark)))
                .padding(20.dp),
        ) {
            Text(
                text = stringResource(R.string.morning_card_eyebrow),
                style = MaterialTheme.typography.labelSmall.copy(
                    fontWeight = FontWeight.SemiBold,
                    letterSpacing = 0.8.sp,
                ),
                color = Color.White.copy(alpha = 0.75f),
            )
            Spacer(Modifier.height(8.dp))
            Text(
                text = moduleTitle,
                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                color = Color.White,
            )
            Spacer(Modifier.height(12.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (cardCount > 0) {
                    MorningMeta(icon = Icons.Outlined.MenuBook,
                        label = pluralStringResource(R.plurals.morning_card_meta_cards, cardCount, cardCount))
                    Spacer(Modifier.width(8.dp))
                }
                if (questionCount > 0) {
                    MorningMeta(icon = Icons.AutoMirrored.Outlined.HelpOutline,
                        label = pluralStringResource(R.plurals.banner_meta_questions, questionCount, questionCount))
                    Spacer(Modifier.width(8.dp))
                }
                MorningMeta(icon = Icons.Outlined.AccessTime,
                    label = stringResource(R.string.banner_meta_minutes, estimatedMinutes))
            }
            Spacer(Modifier.height(16.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Button(
                    onClick = onStart,
                    colors = ButtonDefaults.buttonColors(containerColor = Color.White, contentColor = SpiceBlue),
                    shape = RoundedCornerShape(24.dp),
                ) {
                    Text(stringResource(R.string.banner_action_start), fontWeight = FontWeight.SemiBold)
                }
                Spacer(Modifier.width(16.dp))
                TextButton(onClick = onSkip) {
                    Text(stringResource(R.string.banner_action_skip),
                        color = Color.White.copy(alpha = 0.85f), fontWeight = FontWeight.Medium)
                }
            }
        }
    }
}

@Composable
private fun MorningMeta(icon: ImageVector, label: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(imageVector = icon, contentDescription = null,
            tint = Color.White.copy(alpha = 0.75f), modifier = Modifier.size(13.dp))
        Spacer(Modifier.width(4.dp))
        Text(text = label, style = MaterialTheme.typography.labelSmall,
            color = Color.White.copy(alpha = 0.85f))
    }
}
