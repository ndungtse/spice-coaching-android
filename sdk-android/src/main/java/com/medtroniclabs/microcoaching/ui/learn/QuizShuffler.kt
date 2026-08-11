package com.medtroniclabs.microcoaching.ui.learn

import kotlin.random.Random

/**
 * Per-attempt randomisation for quiz questions and their answer options.
 *
 * Learners re-taking a quiz should answer from understanding, not from the
 * memorised sequence of questions or the remembered position of the correct
 * option. These helpers reorder both, once, at attempt-assembly time (see
 * `startQuiz` / `primeRefresherQuiz`), so the order is stable for the whole
 * attempt and fresh on every reattempt.
 *
 * Everything downstream (scoring, card colouring, the answer-review screen) is
 * positional — it compares the selected index against [QuizQuestion.correctIndex]
 * and reads the `answers` map by index. So shuffling options must permute
 * [QuizQuestion.answers] AND remap [QuizQuestion.correctIndex] together, which is
 * exactly what [withShuffledOptions] does. Local scoring therefore stays correct
 * in the shuffled ("display") space.
 *
 * Telemetry is the one place that must NOT move into display space: the backend
 * receives `selected_option` as an index into the authored option order. Each
 * shuffled question records its display→original mapping in
 * [QuizQuestion.optionOriginalIndices]; [canonicalOptionIndex] uses it to translate
 * the CHW's tap back to the backend's coordinates before recording the event.
 */

/** Shuffle question order AND each question's options for a fresh attempt. */
internal fun List<QuizQuestion>.shuffledForAttempt(random: Random = Random.Default): List<QuizQuestion> =
    shuffled(random).map { it.withShuffledOptions(random) }

/**
 * Return a copy of this question with its options permuted, [correctIndex]
 * remapped to the correct option's new position, and [optionOriginalIndices]
 * recording the display→**authored** mapping. A question with 0–1 options is
 * returned unchanged (nothing to shuffle).
 *
 * Safe to call on an already-shuffled question: the new [optionOriginalIndices]
 * is composed through any existing mapping, so it always points at the authored
 * (backend) option order — never at the previous shuffle's order. This keeps
 * [canonicalOptionIndex] correct across a reshuffle (e.g. refresher "Try again").
 */
internal fun QuizQuestion.withShuffledOptions(random: Random = Random.Default): QuizQuestion {
    if (answers.size <= 1) return this
    // order[displayPos] = current index of the option now shown at displayPos.
    val order = answers.indices.shuffled(random)
    // Map current positions back to authored positions (identity when unshuffled).
    val currentToAuthored = optionOriginalIndices.ifEmpty { answers.indices.toList() }
    return copy(
        answers = order.map { answers[it] },
        correctIndex = order.indexOf(correctIndex),
        optionOriginalIndices = order.map { currentToAuthored[it] },
    )
}

/**
 * Translate a displayed option index back to its canonical/original index for
 * telemetry. Returns the input unchanged when the question was not shuffled
 * (empty [QuizQuestion.optionOriginalIndices] ⇒ identity mapping).
 */
internal fun QuizQuestion.canonicalOptionIndex(displayIndex: Int): Int =
    optionOriginalIndices.getOrNull(displayIndex) ?: displayIndex
