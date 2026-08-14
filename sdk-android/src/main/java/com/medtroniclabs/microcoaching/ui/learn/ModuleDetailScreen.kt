package com.medtroniclabs.microcoaching.ui.learn

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.automirrored.filled.MenuBook
import androidx.compose.material.icons.filled.AccessTime
import androidx.compose.material.icons.filled.HelpOutline
import androidx.compose.material.icons.filled.Hearing
import androidx.compose.material.icons.outlined.HearingDisabled
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.medtroniclabs.microcoaching.R
import com.medtroniclabs.microcoaching.ui.common.SdkScreenHeader
import com.medtroniclabs.microcoaching.ui.common.translatedText
import com.medtroniclabs.microcoaching.ui.theme.SpiceBlue
import com.medtroniclabs.microcoaching.ui.theme.SpiceBlueContainer

/**
 * Module detail screen — flat single-scroll layout:
 *
 * - Back arrow + title, content-domain tag, assignment date
 * - Stats row: Cards | Questions | Duration
 * - Curriculum section: "Learning cards" list + optional "Quiz" entry
 * - "Listen" toggle, which arms auto-speak for the lesson player rather than
 *   speaking here
 * - "Start Course →" (primary) + "Do a Quiz →" (outlined) CTAs
 *
 * @param uiState Must be [LearnUiState.LessonContent].
 */
@Composable
fun ModuleDetailScreen(
    uiState: LearnUiState,
    onContinueToQuiz: () -> Unit,
    onStartCourse: () -> Unit,
    onBack: () -> Unit,
    autoSpeakEnabled: Boolean = false,
    onToggleAutoSpeak: () -> Unit = {},
    onHome: () -> Unit = {},
    onReadAgain: () -> Unit = {},
    quizEnabled: Boolean = true,
) {
    // Keep the last-known module visible while the back transition is animating.
    // Without this, popToModuleList() flips state to ModuleList before navController
    // pops, causing a brief spinner flash on this screen.
    val currentModule = (uiState as? LearnUiState.LessonContent)?.module
    var cachedModule by remember { mutableStateOf<LearnModule?>(null) }
    LaunchedEffect(currentModule) {
        if (currentModule != null) cachedModule = currentModule
    }
    val module = currentModule ?: cachedModule
    if (module == null) {
        // Never compose NOTHING — a bare `return` painted the route white when
        // both the live and cached module were unavailable.
        Box(
            modifier = Modifier.fillMaxSize().background(Color.White),
            contentAlignment = Alignment.Center,
        ) {
            CircularProgressIndicator()
        }
        return
    }

    // Parsed once per blob, not on every recomposition: JSON parsing has no business
    // running inside composition (Compose can't contain a throw from there), and this also
    // drops a per-frame parse cost.
    val cards = remember(module.cardsJson) { parseLessonCards(module.cardsJson) }
    // questionCount is always populated (even on the slim list model); the active
    // module here is hydrated with its cards blob for the card list above.
    val questionCount = module.questionCount
    val hasQuiz = questionCount > 0

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.White),
    ) {
        SdkScreenHeader(title = module.title, onBack = onBack, onHome = onHome)

        Column(
            modifier = Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 16.dp),
        ) {
            // Domain subtitle hidden per QA — domain codes like "rmnch" were confusing.
            // moduleSubtitle() helper retained below for future use.

            // ── Header thumbnail ───────────────────────────────────────────────
            // Only rendered when the module has a resolved thumbnail URL, so the
            // layout is unchanged for modules without one.
            if (!module.thumbnailUrl.isNullOrBlank()) {
                com.medtroniclabs.microcoaching.ui.learn.modules.components.ModuleThumbnail(
                    thumbnailUrl = module.thumbnailUrl,
                    contentDescription = module.title,
                    modifier = Modifier
                        .fillMaxWidth()
                        .aspectRatio(16f / 9f)
                        .clip(RoundedCornerShape(16.dp)),
                )
                Spacer(Modifier.height(16.dp))
            }

            // Title
            Text(
                text = module.title,
                style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.Bold, fontSize = 20.sp),
                color = TitleColor,
            )

            // Content-domain tag: Clinical / Digital / Operational.
            Spacer(Modifier.height(8.dp))
            com.medtroniclabs.microcoaching.ui.learn.modules.components.ContentDomainTag(
                contentDomain = module.contentDomain,
            )

            // Assignment date — only for modules reached via the assigned-training
            // list (assignedAtMs is null otherwise), shown as a friendly date under
            // the title.
            module.assignedAtMs?.let { assignedAtMs ->
                Spacer(Modifier.height(6.dp))
                Text(
                    text = stringResource(
                        R.string.module_detail_assigned_on,
                        com.medtroniclabs.microcoaching.util.friendlyDateLabel(assignedAtMs),
                    ),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            Spacer(Modifier.height(16.dp))

            // ── Stats row ──────────────────────────────────────────────────────
            StatsRow(
                cardCount = cards.size,
                questionCount = questionCount,
                estimatedMinutes = module.estimatedMinutes ?: (cards.size * 1),
            )

            Spacer(Modifier.height(24.dp))

            // ── Curriculum ─────────────────────────────────────────────────────
            Text(
                text = stringResource(R.string.module_detail_curriculum),
                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                color = TitleColor,
            )
            Spacer(Modifier.height(12.dp))

            if (cards.isNotEmpty()) {
                Text(
                    text = stringResource(R.string.module_detail_learning_cards),
                    style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold),
                    color = SpiceBlue,
                )
                Spacer(Modifier.height(8.dp))

                cards.forEachIndexed { index, card ->
                    CurriculumRow(
                        number = index + 1,
                        title = translatedText(bn = card.titleBn, en = card.titleEn),
                    )
                    if (index < cards.size - 1) {
                        HorizontalDivider(color = DividerColor, thickness = 0.5.dp)
                    }
                }
            }

            if (hasQuiz) {
                Spacer(Modifier.height(16.dp))
                Text(
                    text = stringResource(R.string.module_detail_quiz_section),
                    style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold),
                    color = SpiceBlue,
                )
                Spacer(Modifier.height(8.dp))
                CurriculumRow(
                    number = cards.size + 1,
                    title = stringResource(R.string.module_detail_knowledge_check),
                    subtitle = stringResource(R.string.module_detail_questions_stat) + " · $questionCount",
                    isQuiz = true,
                )
            }

            Spacer(Modifier.height(20.dp))

            // ── Listen toggle ───────────────────────────────────────────────
            // Language-neutral: the voice follows the script of each card as it plays
            // (see LearnViewModel.speakAloud), so naming a language here would be a
            // guess the playback doesn't honour.
            OutlinedButton(
                onClick = onToggleAutoSpeak,
                shape = RoundedCornerShape(20.dp),
            ) {
                Icon(
                    imageVector = if (autoSpeakEnabled) Icons.Filled.Hearing
                    else Icons.Outlined.HearingDisabled,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp),
                    tint = if (autoSpeakEnabled) SpiceBlue else MetadataColor,
                )
                Spacer(Modifier.width(6.dp))
                Text(
                    text = stringResource(R.string.module_detail_listen),
                    style = MaterialTheme.typography.labelLarge,
                )
            }
            if (autoSpeakEnabled) {
                Spacer(Modifier.height(6.dp))
                Text(
                    text = stringResource(R.string.module_detail_listen_caption),
                    style = MaterialTheme.typography.bodySmall,
                    color = MetadataColor,
                )
            }

            Spacer(Modifier.height(96.dp))
        }

        // ── CTA row ────────────────────────────────────────────────────────────
        // For un-passed modules: the normal Start Course / Do a Quiz row.
        // For completed modules: a single "Read course" CTA that opens the
        // lesson player in read-only mode (no quiz path is reachable, so the
        // pass state in `chw_module_completion` can't be overwritten).
        if (module.status == "completed") {
            if (cards.isNotEmpty()) ReadAgainCta(onReadAgain = onReadAgain)
        } else {
            CtaRow(
                onStartCourse = onStartCourse,
                onContinueToQuiz = onContinueToQuiz,
                hasCards = cards.isNotEmpty(),
                hasQuiz = hasQuiz,
                quizEnabled = quizEnabled,
            )
        }
    }
}

@Composable
private fun StatsRow(
    cardCount: Int,
    questionCount: Int,
    estimatedMinutes: Int,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(SpiceBlueContainer, RoundedCornerShape(12.dp))
            .padding(horizontal = 16.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.SpaceEvenly,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        StatItem(
            icon = { Icon(Icons.AutoMirrored.Filled.MenuBook, null, Modifier.size(16.dp), tint = SpiceBlue) },
            value = "$cardCount",
            label = stringResource(R.string.module_detail_cards_stat),
        )
        StatDivider()
        StatItem(
            icon = { Icon(Icons.Default.HelpOutline, null, Modifier.size(16.dp), tint = SpiceBlue) },
            value = "$questionCount",
            label = stringResource(R.string.module_detail_questions_stat),
        )
        StatDivider()
        StatItem(
            icon = { Icon(Icons.Default.AccessTime, null, Modifier.size(16.dp), tint = SpiceBlue) },
            value = "$estimatedMinutes",
            label = stringResource(R.string.module_detail_duration_stat),
        )
    }
}

@Composable
private fun StatDivider() {
    Box(
        modifier = Modifier
            .height(28.dp)
            .width(1.dp)
            .background(DividerColor),
    )
}

@Composable
private fun StatItem(
    icon: @Composable () -> Unit,
    value: String,
    label: String,
) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            icon()
            Spacer(Modifier.width(4.dp))
            Text(
                text = value,
                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                color = TitleColor,
            )
        }
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MetadataColor,
        )
    }
}

/**
 * One numbered entry in the module curriculum — a content card, or the closing
 * knowledge check.
 *
 * [subtitle] is whatever detail that entry can offer beneath its title, and is
 * omitted when there is none: the quiz knows its question count, while a card has
 * no per-card metadata on the wire to show.
 */
@Composable
private fun CurriculumRow(
    number: Int,
    title: String,
    subtitle: String? = null,
    isQuiz: Boolean = false,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(
            modifier = Modifier
                .size(36.dp)
                .clip(CircleShape)
                .background(if (isQuiz) SpiceBlueContainer else IndexBg),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = "%02d".format(number),
                color = SpiceBlue,
                style = MaterialTheme.typography.labelMedium.copy(
                    fontWeight = FontWeight.Bold,
                    fontSize = 13.sp,
                ),
            )
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Medium),
                color = TitleColor,
            )
            subtitle?.let {
                Text(
                    text = it,
                    style = MaterialTheme.typography.labelSmall,
                    color = MetadataColor,
                )
            }
        }
        Icon(
            imageVector = Icons.AutoMirrored.Filled.ArrowForward,
            contentDescription = null,
            tint = MetadataColor,
            modifier = Modifier.size(16.dp),
        )
    }
}

/**
 * Single-button CTA row for completed modules: "Read course". Opens the
 * lesson player in read-only mode (no quiz button on the last card).
 */
@Composable
private fun ReadAgainCta(onReadAgain: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(Color.White)
            .border(width = 0.5.dp, color = DividerColor)
            .navigationBarsPadding()
            .padding(horizontal = 20.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Button(
            onClick = onReadAgain,
            modifier = Modifier.weight(1f),
            shape = RoundedCornerShape(28.dp),
            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary),
        ) {
            Text(
                text = stringResource(R.string.module_detail_action_read_course),
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(Modifier.size(4.dp))
            Icon(
                imageVector = Icons.AutoMirrored.Filled.ArrowForward,
                contentDescription = null,
                modifier = Modifier.size(16.dp),
            )
        }
    }
}

@Composable
private fun CtaRow(
    onStartCourse: () -> Unit,
    onContinueToQuiz: () -> Unit,
    hasCards: Boolean,
    hasQuiz: Boolean,
    quizEnabled: Boolean,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(Color.White)
            .border(width = 0.5.dp, color = DividerColor)
            .navigationBarsPadding()
            .padding(horizontal = 20.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (hasCards) {
            Button(
                onClick = onStartCourse,
                modifier = Modifier.weight(1f),
                shape = RoundedCornerShape(28.dp),
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary),
            ) {
                Text(
                    text = stringResource(R.string.module_detail_action_start_course),
                    fontWeight = FontWeight.SemiBold,
                )
                Spacer(Modifier.size(4.dp))
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.ArrowForward,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp),
                )
            }
        }
        // Quiz CTA is only meaningful when the module actually has questions.
        // Modules with 0 questions finish at the cards-completion screen instead
        // of dead-ending in an empty quiz.
        if (hasQuiz) {
            OutlinedButton(
                onClick = onContinueToQuiz,
                modifier = Modifier.weight(1f),
                shape = RoundedCornerShape(28.dp),
                enabled = quizEnabled,
            ) {
                Text(stringResource(R.string.module_detail_action_do_quiz))
            }
        }
    }
}

private fun moduleSubtitle(module: LearnModule): String {
    val typeDisplay = when (module.moduleType) {
        "digital_proficiency" -> "Training"
        "content_update" -> "Knowledge Update"
        else -> module.clinicalDomain.replaceFirstChar { it.titlecase() }
    }
    return typeDisplay
}

private val TitleColor = Color(0xFF101828)
private val MetadataColor = Color(0xFF667085)
private val DividerColor = Color(0xFFE4E7EC)
private val IndexBg = Color(0xFFEFF4FF)
