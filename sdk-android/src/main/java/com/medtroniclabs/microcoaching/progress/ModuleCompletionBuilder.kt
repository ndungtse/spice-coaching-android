package com.medtroniclabs.microcoaching.progress

import com.medtroniclabs.microcoaching.data.db.entity.ChwModuleCompletionEntity

/**
 * Update [ChwModuleCompletionEntity] after a module attempt. Returns the new row that the
 * caller is expected to upsert.
 *
 * Owns the two rules that outlive any single attempt: `completedAt` is sticky, so a later
 * failure never un-completes a module, and the reinforcement clock restarts only on a pass.
 *
 * @param scoreFraction 0–1 quiz score, or null for a module that has no quiz — a synthetic
 *   100% would otherwise flow into score reporting as if it had been answered.
 */
fun buildModuleCompletion(
    previous: ChwModuleCompletionEntity?,
    chwId: String,
    moduleFamilyId: String,
    moduleId: String?,
    scoreFraction: Float?,
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
