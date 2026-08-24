package com.medtroniclabs.microcoaching.domain.gaps.ondevice

/**
 * Pure fold of one quiz-attempt outcome onto a [QuizState], mirroring the backend
 * `QuizQuestionStateService`:
 *  - incorrect → `failedAttemptsCount + 1` (reset to 1 when the previous failure is
 *    outside the escalation window), `status = ACTIVE`, escalate at the threshold;
 *  - correct → clears the counter outright and resolves;
 *  - unknown → record the attempt timestamp only (no per-question signal).
 *
 * The correct-answer rule differs from `GapStateReducer`, which decrements: the backend
 * likewise resets for quiz state and decrements for gap state. Getting this wrong leaves a
 * question the backend considers resolved still being re-emitted on-device.
 *
 * Stateless and deterministic — `nowMillis` (the event's timestamp) is passed in,
 * never read from the clock, so it runs in the plain JUnit source set.
 */
object QuizStateReducer {

    private const val DAY_MS = 24L * 60L * 60L * 1000L

    fun reduce(
        state: QuizState,
        outcome: GapOutcome,
        config: GapStateConfig,
        nowMillis: Long,
    ): QuizState {
        val firstAttemptAt = state.firstAttemptAt ?: nowMillis
        return when (outcome) {
            GapOutcome.INCORRECT -> {
                val outsideWindow = state.lastFailedAttemptAt != null &&
                    nowMillis - state.lastFailedAttemptAt > config.escalationWindowDays.toLong() * DAY_MS
                val failed = if (outsideWindow) 1 else state.failedAttemptsCount + 1
                state.copy(
                    failedAttemptsCount = failed,
                    lastFailedAttemptAt = nowMillis,
                    lastAttemptAt = nowMillis,
                    firstAttemptAt = firstAttemptAt,
                    status = GapStatus.ACTIVE,
                    escalatedToSupervisor = failed >= config.escalationFailureCount,
                )
            }
            // Guarded on a non-zero count exactly as the backend is: a correct answer to a
            // question that was never failed records the attempt and nothing more.
            GapOutcome.CORRECT -> if (state.failedAttemptsCount == 0) {
                state.copy(lastAttemptAt = nowMillis, firstAttemptAt = firstAttemptAt)
            } else {
                state.copy(
                    failedAttemptsCount = 0,
                    lastFailedAttemptAt = null,
                    lastAttemptAt = nowMillis,
                    firstAttemptAt = firstAttemptAt,
                    status = GapStatus.RESOLVED,
                    escalatedToSupervisor = false,
                )
            }
            GapOutcome.UNKNOWN -> state.copy(lastAttemptAt = nowMillis, firstAttemptAt = firstAttemptAt)
        }
    }
}
