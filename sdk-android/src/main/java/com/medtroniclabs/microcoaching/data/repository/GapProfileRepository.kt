package com.medtroniclabs.microcoaching.data.repository

import com.medtroniclabs.microcoaching.data.db.dao.ChwGapProfileDao
import com.medtroniclabs.microcoaching.data.db.entity.ChwGapProfileEntity
import kotlinx.coroutines.flow.Flow

/**
 * Manages the CHW's local knowledge-gap profile.
 *
 * Gap state is updated after every quiz answer using the classification rules from
 * DataDesign v1.1 §4.4.1. Server-side sync is deferred to a future sprint.
 */
interface GapProfileRepository {
    fun getActiveGaps(chwId: String): Flow<List<ChwGapProfileEntity>>
    suspend fun getAllForChw(chwId: String): List<ChwGapProfileEntity>
    /**
     * @param behaviouralGapId The gap UUID from the morning-cards response or sync.
     *   Pass null to skip local gap tracking (e.g. module opened without gap context).
     */
    suspend fun recordQuizAnswer(
        chwId: String,
        behaviouralGapId: String?,
        clinicalDomain: String,
        isCorrect: Boolean,
    )
}

class GapProfileRepositoryImpl(private val dao: ChwGapProfileDao) : GapProfileRepository {

    override fun getActiveGaps(chwId: String): Flow<List<ChwGapProfileEntity>> =
        dao.getActiveGaps(chwId)

    override suspend fun getAllForChw(chwId: String): List<ChwGapProfileEntity> =
        dao.getAllForChw(chwId)

    override suspend fun recordQuizAnswer(
        chwId: String,
        behaviouralGapId: String?,
        clinicalDomain: String,
        isCorrect: Boolean,
    ) {
        // No gap context → nothing to track locally.
        if (behaviouralGapId == null) return
        val existing = dao.getProfile(chwId, behaviouralGapId) ?: ChwGapProfileEntity(
            chwId = chwId,
            behaviouralGapId = behaviouralGapId,
            clinicalDomain = clinicalDomain,
        )

        val newWrongCount = if (isCorrect) existing.wrongCount else existing.wrongCount + 1
        val newConsecutiveCorrect = if (isCorrect) existing.consecutiveCorrect + 1 else 0
        val newTotalAttempts = existing.totalAttempts + 1
        val newCorrectSoFar = existing.totalAttempts - existing.wrongCount + (if (isCorrect) 1 else 0)
        val newScorePct = (newCorrectSoFar.toFloat() / newTotalAttempts) * 100f

        // Gap classification rules (DataDesign v1.1 §4.4.1 defaults)
        val counsellingUseRate: Float =
            if (existing.cardsShown > 0) existing.cardsUsed.toFloat() / existing.cardsShown else 0f

        val newGapType = when {
            newWrongCount >= KNOWLEDGE_GAP_WRONG_THRESHOLD && counsellingUseRate < COUNSELLING_USE_THRESHOLD ->
                "knowledge"
            newScorePct >= QUIZ_PASS_THRESHOLD && counsellingUseRate < COUNSELLING_USE_THRESHOLD ->
                "skill_application"
            else -> existing.gapType
        }

        val resolvedNow = newConsecutiveCorrect >= CONSECUTIVE_CORRECT_REQUIRED
                && existing.cardsUsed >= MIN_COUNSELLING_USES
                && existing.gapActive

        val updated = existing.copy(
            wrongCount = newWrongCount,
            consecutiveCorrect = newConsecutiveCorrect,
            totalAttempts = newTotalAttempts,
            quizScorePct = newScorePct,
            gapType = newGapType,
            gapActive = if (resolvedNow) false else existing.gapActive,
            resolvedAt = if (resolvedNow) System.currentTimeMillis() else existing.resolvedAt,
            lastSeen = System.currentTimeMillis(),
        )

        dao.upsert(updated)
    }

    companion object {
        // Thresholds from DataDesign v1.1 §4.4.1
        private const val KNOWLEDGE_GAP_WRONG_THRESHOLD = 2
        private const val COUNSELLING_USE_THRESHOLD = 0.2f
        private const val QUIZ_PASS_THRESHOLD = 70f
        private const val CONSECUTIVE_CORRECT_REQUIRED = 2
        private const val MIN_COUNSELLING_USES = 1
    }
}
