package com.medtroniclabs.microcoaching.domain.gaps.ondevice

import com.medtroniclabs.microcoaching.data.db.entity.CoachingEventEntity

/**
 * Maps a telemetry [CoachingEventEntity] to a [GapOutcome], preferring the per-question
 * `is_correct` flag and falling back to the explicit `outcome` string.
 *
 * Shared by [OnDeviceGapStateEngine] and [OnDeviceQuizStateEngine] (previously an identical
 * private copy in each). Kept out of `OnDeviceGapModels.kt`, which is deliberately Room-free.
 */
internal fun outcomeOf(event: CoachingEventEntity): GapOutcome {
    event.isCorrect?.let { return if (it) GapOutcome.CORRECT else GapOutcome.INCORRECT }
    return when (event.outcome?.lowercase()) {
        "correct" -> GapOutcome.CORRECT
        "wrong", "incorrect" -> GapOutcome.INCORRECT
        else -> GapOutcome.UNKNOWN
    }
}
