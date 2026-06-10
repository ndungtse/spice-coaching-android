package com.medtroniclabs.microcoaching.ui.learn

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.automirrored.filled.VolumeOff
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import com.medtroniclabs.microcoaching.content.richtext.bodyToSpokenText
import com.medtroniclabs.microcoaching.ui.markdown.MarkdownDefaults
import com.medtroniclabs.microcoaching.ui.richtext.RichCardBody
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.medtroniclabs.microcoaching.Language
import com.medtroniclabs.microcoaching.MicroCoachingSDK
import com.medtroniclabs.microcoaching.R
import com.medtroniclabs.microcoaching.ui.common.SdkScreenHeader
import com.medtroniclabs.microcoaching.ui.common.translatedText
import com.medtroniclabs.microcoaching.ui.theme.SpiceBlue

/**
 * Card-by-card lesson player. Renders each [LessonCard] in sequence with a
 * dark-blue gradient header and a numbered list body content area.
 *
 * Layout matches `docs/v3/designs/module_content_1.png` and `module_content_2.png`:
 * - Dark-blue gradient header: back arrow, "Learning X of N", bold white title,
 *   decorative semi-transparent circle blob top-right.
 * - White rounded content card: body text split on `\n` → numbered items in red,
 *   separated by HorizontalDividers.
 * - Fixed bottom: "Next →" (not last card) / "Start Quiz →" (last card).
 *
 * @param cards The ordered list of lesson cards to display.
 * @param initialIndex Starting card index (0-based). Defaults to 0.
 * @param lang SDK language code — "bn" or "en".
 * @param onBack Navigate back to [ModuleDetailScreen].
 * @param onStartQuiz Navigate to the quiz (called when the CHW taps "Start Quiz" on last card).
 * @param onCardShown Callback fired on each card display for telemetry.
 * @param readOnly When true, the last-card primary CTA becomes "Back to modules"
 *   (no quiz path) and [onFinishReading] is invoked instead of [onStartQuiz].
 *   Driven by `QuizRetryGate.isRetryWindowClosed(module)` — see
 *   [com.medtroniclabs.microcoaching.ui.flow.CoachingNavGraph]'s `readOnly`
 *   computation. Completion alone no longer triggers read-only mode;
 *   passed modules within the 7-day publication window remain re-quizzable.
 * @param onFinishReading Invoked when [readOnly] is true and the CHW taps the
 *   primary CTA on the last card. Defaults to [onStartQuiz] for callers that
 *   don't supply it — the [readOnly] guard prevents that fallback from
 *   activating in revisit mode.
 */
@Composable
fun LessonPlayerScreen(
    cards: List<LessonCard>,
    initialIndex: Int = 0,
    lang: String = "bn",
    onBack: () -> Unit,
    onStartQuiz: () -> Unit,
    onCardShown: (Int) -> Unit,
    autoSpeakEnabled: Boolean = false,
    onToggleAutoSpeak: () -> Unit = {},
    onSpeak: (text: String, onDone: () -> Unit) -> Unit = { _, _ -> },
    onStopSpeak: () -> Unit = {},
    onHome: () -> Unit = {},
    readOnly: Boolean = false,
    onFinishReading: () -> Unit = onStartQuiz,
) {
    if (cards.isEmpty()) {
        // No content — exit straight away. In read-only mode this lands on
        // the modules list; in quiz mode the existing quiz transition fires.
        LaunchedEffect(Unit) {
            if (readOnly) onFinishReading() else onStartQuiz()
        }
        return
    }

    var currentIndex by rememberSaveable { mutableIntStateOf(initialIndex.coerceIn(0, cards.size - 1)) }
    val card = cards[currentIndex]
    val isLast = currentIndex == cards.size - 1

    // Intercept system back so we route through onBack — keeps state in sync
    // (avoids the blank-screen blip where uiState is stale after navigation pops).
    BackHandler(onBack = {
        onStopSpeak()
        onBack()
    })

    LaunchedEffect(currentIndex) { onCardShown(currentIndex) }

    val listState = rememberLazyListState()
    LaunchedEffect(currentIndex) {
        listState.scrollToItem(0)
    }

    // Auto-speak: when enabled, speak the current card; advance on completion.
    // Re-keys on (currentIndex, autoSpeakEnabled) so manual Next/Prev cleanly
    // cancels the previous utterance and immediately starts the new card.
    val currentTitle = translatedText(bn = card.titleBn, en = card.titleEn)
    val currentBody = translatedText(bn = card.bodyBn, en = card.bodyEn)
    LaunchedEffect(currentIndex, autoSpeakEnabled) {
        if (autoSpeakEnabled) {
            // Prepend the title so TTS announces "<title>. <body>" — the period
            // gives the engine a natural pause between the two.
            val spokenBody = bodyToSpokenText(currentBody)
            val spoken =
                if (currentTitle.isBlank()) spokenBody
                else "$currentTitle. $spokenBody"
            if (spoken.isNotBlank()) {
                onSpeak(spoken) {
                    // Auto-advance only if we're not on the last card. On the last card,
                    // stop and let the user manually tap "Start Quiz".
                    if (currentIndex < cards.lastIndex) currentIndex++
                }
            }
        } else {
            onStopSpeak()
        }
    }

    DisposableEffect(Unit) {
        onDispose { onStopSpeak() }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        Column(modifier = Modifier.fillMaxSize()) {
            // ── Consistent blue header ──────────────────────────────────────────
            SdkScreenHeader(
                title = stringResource(R.string.lesson_player_progress, currentIndex + 1, cards.size),
                onBack = {
                    onStopSpeak()
                    onBack()
                },
                onHome = {
                    onStopSpeak()
                    onHome()
                },
                trailing = {
                    // Sits just to the left of the Home icon (which the header
                    // anchors at CenterEnd with 4.dp end padding).
                    IconButton(
                        onClick = onToggleAutoSpeak,
                        modifier = Modifier
                            .align(Alignment.CenterEnd)
                            .padding(end = 52.dp),
                    ) {
                        Icon(
                            imageVector = if (autoSpeakEnabled) Icons.AutoMirrored.Filled.VolumeUp
                            else Icons.AutoMirrored.Filled.VolumeOff,
                            contentDescription = stringResource(
                                if (autoSpeakEnabled) R.string.lesson_player_auto_speak_on
                                else R.string.lesson_player_auto_speak_off,
                            ),
                            tint = Color.White,
                        )
                    }
                },
            )

            // ── Body card ──────────────────────────────────────────────────────
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .background(Color.White),
            ) {
                val bodyText = currentBody

                LazyColumn(
                    state = listState,
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(horizontal = 20.dp)
                        .padding(top = 16.dp, bottom = 96.dp),
                ) {
                    item {
                        Text(
                            text = translatedText(bn = card.titleBn, en = card.titleEn),
                            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                            color = Color(0xFF101828),
                        )
                        Spacer(Modifier.height(12.dp))
                        RichCardBody(
                            raw = bodyText,
                            modifier = Modifier.fillMaxWidth(),
                            style = MarkdownDefaults.style(
                                textColor = BodyTextColor,
                                h1 = MaterialTheme.typography.titleLarge.copy(
                                    fontWeight = FontWeight.Bold,
                                ),
                                h2 = MaterialTheme.typography.titleMedium.copy(
                                    fontWeight = FontWeight.Bold,
                                ),
                                h3 = MaterialTheme.typography.titleSmall.copy(
                                    fontWeight = FontWeight.Bold,
                                ),
                            ),
                        )
                    }
                }
            }
        }

        // ── Fixed bottom nav row ───────────────────────────────────────────────
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .align(Alignment.BottomCenter)
                .navigationBarsPadding()
                .padding(horizontal = 20.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            val isFirst = currentIndex == 0
            OutlinedButton(
                onClick = { if (!isFirst) currentIndex-- },
                enabled = !isFirst,
                modifier = Modifier.weight(1f),
                shape = RoundedCornerShape(28.dp),
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                )
                Spacer(Modifier.size(6.dp))
                Text(
                    text = stringResource(R.string.lesson_player_previous),
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 16.sp,
                )
            }
            Button(
                onClick = {
                    if (isLast) {
                        if (readOnly) onFinishReading() else onStartQuiz()
                    } else currentIndex++
                },
                modifier = Modifier.weight(1f),
                shape = RoundedCornerShape(28.dp),
                colors = ButtonDefaults.buttonColors(containerColor = SpiceBlue),
            ) {
                Text(
                    text = if (isLast) {
                        stringResource(
                            if (readOnly) R.string.lesson_player_back_to_modules
                            else R.string.lesson_player_start_quiz,
                        )
                    } else {
                        stringResource(R.string.lesson_player_next)
                    },
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 16.sp,
                )
                if (!isLast) {
                    Spacer(Modifier.size(6.dp))
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.ArrowForward,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                    )
                }
            }
        }
    }
}

private val BodyTextColor = Color(0xFF344054)

/** Convenience overload that reads the SDK's current language automatically. */
@Composable
fun LessonPlayerScreen(
    module: LearnModule,
    initialIndex: Int = 0,
    onBack: () -> Unit,
    onStartQuiz: () -> Unit,
    onCardShown: (Int) -> Unit,
    autoSpeakEnabled: Boolean = false,
    onToggleAutoSpeak: () -> Unit = {},
    onSpeak: (text: String, onDone: () -> Unit) -> Unit = { _, _ -> },
    onStopSpeak: () -> Unit = {},
    onHome: () -> Unit = {},
    readOnly: Boolean = false,
    onFinishReading: () -> Unit = onStartQuiz,
) {
    val lang = if (MicroCoachingSDK.getInstance().config.language == Language.ENGLISH) "en" else "bn"
    LessonPlayerScreen(
        cards = parseLessonCards(module.cardsJson),
        initialIndex = initialIndex,
        lang = lang,
        onBack = onBack,
        onStartQuiz = onStartQuiz,
        onCardShown = onCardShown,
        autoSpeakEnabled = autoSpeakEnabled,
        onToggleAutoSpeak = onToggleAutoSpeak,
        onSpeak = onSpeak,
        onStopSpeak = onStopSpeak,
        onHome = onHome,
        readOnly = readOnly,
        onFinishReading = onFinishReading,
    )
}
