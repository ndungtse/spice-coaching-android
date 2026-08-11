package com.medtroniclabs.microcoaching.progress

import com.medtroniclabs.microcoaching.data.db.entity.ChwModuleCompletionEntity

/**
 * Update [ChwModuleCompletionEntity] after a quiz attempt. Returns the new row that the
 * caller is expected to upsert.
 *
 * Moved out of `domain/triggers/TriggerEvaluator.kt` (it has nothing to do with trigger
 * evaluation — it's module-completion/progress logic) into the `progress` package that owns
 * that concept. Behaviour unchanged.
 */
fun buildModuleCompletion(
    previous: ChwModuleCompletionEntity?,
    chwId: String,
    moduleFamilyId: String,
    moduleId: String?,
    scoreFraction: Float,
    passed: Boolean,
    reinforcementDays: Int,
    nowMillis: Long = System.currentTimeMillis(),
): ChwModuleCompletionEntity {
    val attemptsSincePass = when {
        passed -> 0
        previous == null -> 1
        else -> previous.attemptsSinceLastPass + 1
    }
    val completedAt = if (passed) nowMillis else previous?.completedAt
    val latestCompletedModuleId = if (passed) moduleId else previous?.latestCompletedModuleId
    val dueAt = if (passed) nowMillis + reinforcementDays.toLong() * 24L * 60L * 60L * 1000L
    else previous?.reinforcementDueAt
    return ChwModuleCompletionEntity(
        chwId = chwId,
        moduleFamilyId = moduleFamilyId,
        latestCompletedModuleId = latestCompletedModuleId,
        latestAttemptModuleId = moduleId,
        completedAt = completedAt,
        latestAttemptAt = nowMillis,
        latestQuizScore = scoreFraction,
        latestAttemptPassed = passed,
        attemptsSinceLastPass = attemptsSincePass,
        reinforcementDueAt = dueAt,
    )
}
