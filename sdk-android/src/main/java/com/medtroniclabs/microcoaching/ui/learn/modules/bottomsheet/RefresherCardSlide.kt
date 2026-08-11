package com.medtroniclabs.microcoaching.ui.learn.modules.bottomsheet

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.automirrored.filled.VolumeOff
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.rememberNestedScrollInteropConnection
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.medtroniclabs.microcoaching.R
import com.medtroniclabs.microcoaching.ui.common.translatedText
import com.medtroniclabs.microcoaching.ui.learn.LessonCard
import com.medtroniclabs.microcoaching.ui.learn.LessonCardAutoSpeak
import com.medtroniclabs.microcoaching.ui.markdown.MarkdownDefaults
import com.medtroniclabs.microcoaching.ui.richtext.RichCardBody
import com.medtroniclabs.microcoaching.ui.theme.SpiceBlue

// ── Cards phase ───────────────────────────────────────────────────────────────
// The lesson-card rendering + terminal actions for the refresher bottom sheet,
// extracted verbatim from RefresherContent (which keeps the flow state machine).
// Same package, so [RefresherContent] calls these without an import; both are
// `internal` because the caller lives in a sibling file. The shared
// [RefresherActions] data class stays in RefresherContent (the flow constructs it).

@Composable
internal fun RefresherCardSlide(
    cards: List<LessonCard>,
    cardIndex: Int,
    onNext: () -> Unit,
    modifier: Modifier,
    autoSpeakEnabled: Boolean = false,
    onToggleAutoSpeak: () -> Unit = {},
    onSpeak: (text: String, onDone: () -> Unit) -> Unit = { _, _ -> },
    onStopSpeak: () -> Unit = {},
    refresherActions: RefresherActions? = null,
) {
    if (cards.isEmpty()) return
    val safeIndex = cardIndex.coerceIn(0, cards.size - 1)
    val card = cards[safeIndex]
    val isLast = safeIndex == cards.size - 1
    // Taller footer buttons — more vertical content padding than the Material
    // default (8.dp) so the refresher CTAs have a larger tap target.
    val buttonContentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp)
    val titleText = translatedText(bn = card.titleBn, en = card.titleEn)
    val bodyText = translatedText(bn = card.bodyBn, en = card.bodyEn)

    // Auto-speak the current card; advance via onNext() on completion (except
    // last card). Shared with the full-screen player — see [LessonCardAutoSpeak].
    LessonCardAutoSpeak(
        cardIndex = safeIndex,
        titleText = titleText,
        bodyText = bodyText,
        isLastCard = isLast,
        autoSpeakEnabled = autoSpeakEnabled,
        onSpeak = onSpeak,
        onStopSpeak = onStopSpeak,
        onAutoAdvance = onNext,
    )

    val scrollState = rememberScrollState()
    LaunchedEffect(safeIndex) {
        scrollState.scrollTo(0)
    }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .fillMaxHeight()
            .padding(horizontal = 20.dp)
            .padding(top = 20.dp, bottom = 16.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = translatedText(bn = card.titleBn, en = card.titleEn),
                style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
                color = Color(0xFF101828),
                modifier = Modifier.weight(1f),
            )
            IconButton(onClick = onToggleAutoSpeak) {
                Icon(
                    imageVector = if (autoSpeakEnabled) Icons.AutoMirrored.Filled.VolumeUp
                    else Icons.AutoMirrored.Filled.VolumeOff,
                    contentDescription = stringResource(
                        if (autoSpeakEnabled) R.string.lesson_player_auto_speak_on
                        else R.string.lesson_player_auto_speak_off,
                    ),
                    tint = SpiceBlue,
                )
            }
        }
        Spacer(Modifier.height(12.dp))
        LinearProgressIndicator(
            progress = { (safeIndex + 1f) / cards.size },
            modifier = Modifier.fillMaxWidth(),
            color = SpiceBlue,
            trackColor = Color(0xFFE4E7EC),
        )
        Spacer(Modifier.height(4.dp))
        Text(
            text = "${safeIndex + 1} / ${cards.size}",
            style = MaterialTheme.typography.labelSmall,
            color = Color(0xFF667085),
            modifier = Modifier.align(Alignment.End),
        )
        Spacer(Modifier.height(12.dp))
        Column(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .nestedScroll(rememberNestedScrollInteropConnection())
                .verticalScroll(scrollState),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (bodyText.isNotBlank()) {
                RichCardBody(
                    raw = bodyText,
                    modifier = Modifier.fillMaxWidth(),
                    style = MarkdownDefaults.style(textColor = Color(0xFF344054)),
                )
            }
        }
        Spacer(Modifier.height(12.dp))
        if (isLast && refresherActions != null) {
            // Terminal card (QUESTION_FIRST modules-screen flow): the last lesson
            // card hosts the completion actions. CARDS_FIRST surfaces the same
            // actions on a dedicated completion screen after the quiz instead.
            RefresherTerminalActions(
                actions = refresherActions,
                contentPadding = buttonContentPadding,
            )
        } else {
            // Forward-only lesson navigation — single full-width Next (Done on the
            // last card). No Back button per design.
            Button(
                onClick = onNext,
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(28.dp),
                colors = ButtonDefaults.buttonColors(containerColor = SpiceBlue),
                contentPadding = buttonContentPadding,
            ) {
                Text(
                    text = if (isLast) stringResource(R.string.refresher_card_done)
                           else stringResource(R.string.lesson_player_next),
                    fontWeight = FontWeight.SemiBold,
                )
                if (!isLast) {
                    Spacer(Modifier.padding(start = 4.dp))
                    Icon(imageVector = Icons.AutoMirrored.Filled.ArrowForward, contentDescription = null)
                }
            }
        }
    }
}

/**
 * Forward-only completion buttons shared by the QUESTION_FIRST last lesson card
 * and the CARDS_FIRST last-question footer. When another refresher is queued →
 * "Next refresher" + "I'll do it later"; otherwise a single "Done".
 */
@Composable
internal fun RefresherTerminalActions(
    actions: RefresherActions,
    contentPadding: PaddingValues,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (actions.hasNext) {
            Button(
                onClick = actions.onNextRefresher,
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(28.dp),
                colors = ButtonDefaults.buttonColors(containerColor = SpiceBlue),
                contentPadding = contentPadding,
            ) {
                Text(
                    text = stringResource(R.string.refresher_next),
                    fontWeight = FontWeight.SemiBold,
                )
            }
            OutlinedButton(
                onClick = actions.onDismiss,
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(28.dp),
                contentPadding = contentPadding,
            ) {
                Text(
                    text = stringResource(R.string.refresher_do_it_later),
                    fontWeight = FontWeight.SemiBold,
                )
            }
        } else {
            Button(
                onClick = actions.onDismiss,
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(28.dp),
                colors = ButtonDefaults.buttonColors(containerColor = SpiceBlue),
                contentPadding = contentPadding,
            ) {
                Text(
                    text = stringResource(R.string.refresher_card_done),
                    fontWeight = FontWeight.SemiBold,
                )
            }
        }
    }
}
