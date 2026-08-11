package com.medtroniclabs.microcoaching.ui.learn

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import com.medtroniclabs.microcoaching.content.richtext.bodyToSpokenText

/**
 * Shared "listen-aloud" behaviour for a single lesson card, used by both the
 * full-screen [LessonPlayerScreen] and the in-sheet `RefresherCardSlide`.
 *
 * This is the one genuinely-duplicated, behaviour-critical piece the two card
 * renderers had in common — their *layouts* diverge (title size, auto-speak
 * toggle placement, RichCardBody heading styles, pinned- vs scrolling-title,
 * `Column` vs `LazyColumn`, progress + nav chrome), so those stay in each host.
 * Only the TTS effect is shared here so a change to how a card is spoken (the
 * "`<title>. <body>`" assembly, the auto-advance-unless-last rule, the on-dispose
 * cleanup) happens in exactly one place.
 *
 * Speaks `"<title>. <body>"` (title first, so the CHW hears the heading before
 * the content), auto-advancing on completion via [onAutoAdvance] unless this is
 * the last card. Re-keys on ([cardIndex], [autoSpeakEnabled]) so manual
 * navigation or a toggle cleanly cancels the current utterance and starts the
 * new card. Stops speech when auto-speak is turned off and on disposal.
 *
 * @param onAutoAdvance invoked when the current card's utterance finishes and
 *   [isLastCard] is false — the host advances its own card index.
 */
@Composable
internal fun LessonCardAutoSpeak(
    cardIndex: Int,
    titleText: String,
    bodyText: String,
    isLastCard: Boolean,
    autoSpeakEnabled: Boolean,
    onSpeak: (text: String, onDone: () -> Unit) -> Unit,
    onStopSpeak: () -> Unit,
    onAutoAdvance: () -> Unit,
) {
    LaunchedEffect(cardIndex, autoSpeakEnabled) {
        if (autoSpeakEnabled) {
            val spokenBody = bodyToSpokenText(bodyText)
            val spoken =
                if (titleText.isBlank()) spokenBody
                else "$titleText. $spokenBody"
            if (spoken.isNotBlank()) {
                onSpeak(spoken) {
                    if (!isLastCard) onAutoAdvance()
                }
            }
        } else {
            onStopSpeak()
        }
    }
    DisposableEffect(Unit) {
        onDispose { onStopSpeak() }
    }
}
