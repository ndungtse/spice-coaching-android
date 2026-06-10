package com.medtroniclabs.microcoaching.data.db.dao

import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.medtroniclabs.microcoaching.data.db.entity.CoachingEventEntity
import kotlinx.coroutines.flow.Flow

/** Projection used by [CoachingEventDao.getRetryCounts]. */
data class RetryCountRow(
    @ColumnInfo(name = "event_id") val eventId: String,
    @ColumnInfo(name = "retry_count") val retryCount: Int,
)

@Dao
interface CoachingEventDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(event: CoachingEventEntity)

    /** All events not yet synced (sync_status = 'pending'). */
    @Query("SELECT * FROM coaching_event WHERE sync_status = 'pending' ORDER BY timestamp_local ASC")
    suspend fun getPending(): List<CoachingEventEntity>

    /**
     * Distinct question IDs whose **latest** `module_quiz_attempted` attempt was
     * correct for the given CHW + module family. Used by the refresher
     * reinforcement loop to EXCLUDE already-mastered questions — anything not in
     * this set (either never answered or latest answer wrong) is still
     * "to reinforce".
     *
     * Rows without `quiz_question_id` (the per-quiz summary event with score)
     * are filtered out so only per-question attempts count toward mastery.
     *
     * Keyed on `module_family_id` so the history survives module version bumps —
     * if backend re-publishes the same module under a new `module_id`, the CHW's
     * mastery history is preserved.
     */
    @Query(
        """
        SELECT quiz_question_id
        FROM coaching_event qe1
        WHERE qe1.chw_id = :chwId
          AND qe1.module_family_id = :moduleFamilyId
          AND qe1.event_type = 'module_quiz_attempted'
          AND qe1.quiz_question_id IS NOT NULL
          AND qe1.is_correct = 1
          AND qe1.timestamp_local = (
            SELECT MAX(qe2.timestamp_local) FROM coaching_event qe2
            WHERE qe2.chw_id = qe1.chw_id
              AND qe2.module_family_id = qe1.module_family_id
              AND qe2.quiz_question_id = qe1.quiz_question_id
              AND qe2.event_type = 'module_quiz_attempted'
          )
        """,
    )
    suspend fun getLatestCorrectQuestionIds(chwId: String, moduleFamilyId: String): List<String>

    /**
     * Mirror of [getLatestCorrectQuestionIds] for questions whose **latest**
     * `module_quiz_attempted` attempt was *wrong*. Used by `ToReinforceResolver`
     * as the local-wrong layer of the merge formula
     * `(serverIncomplete ∪ localWrong) - localCorrect`.
     */
    @Query(
        """
        SELECT quiz_question_id
        FROM coaching_event qe1
        WHERE qe1.chw_id = :chwId
          AND qe1.module_family_id = :moduleFamilyId
          AND qe1.event_type = 'module_quiz_attempted'
          AND qe1.quiz_question_id IS NOT NULL
          AND qe1.is_correct = 0
          AND qe1.timestamp_local = (
            SELECT MAX(qe2.timestamp_local) FROM coaching_event qe2
            WHERE qe2.chw_id = qe1.chw_id
              AND qe2.module_family_id = qe1.module_family_id
              AND qe2.quiz_question_id = qe1.quiz_question_id
              AND qe2.event_type = 'module_quiz_attempted'
          )
        """,
    )
    suspend fun getLatestWrongQuestionIds(chwId: String, moduleFamilyId: String): List<String>

    /**
     * Emits whenever any row in `coaching_event` changes. Used by [LearnViewModel.observeModules]
     * as a re-trigger so refresher tile counts (which depend on event history) refresh
     * immediately after the CHW completes a refresher quiz.
     */
    @Query("SELECT COUNT(*) FROM coaching_event")
    fun getEventCountFlow(): Flow<Int>

    /** Mark a batch of events as synced by their UUID event_id. */
    @Query("UPDATE coaching_event SET sync_status = 'synced', synced_at = :syncedAt WHERE event_id IN (:eventIds)")
    suspend fun markSynced(eventIds: List<String>, syncedAt: Long = System.currentTimeMillis())

    /** Mark a batch of events as permanently failed after max retry exhaustion. */
    @Query("UPDATE coaching_event SET sync_status = 'failed' WHERE event_id IN (:eventIds)")
    suspend fun markFailed(eventIds: List<String>)

    /** Increment retry count for events that failed a single sync attempt. */
    @Query("UPDATE coaching_event SET retry_count = retry_count + 1 WHERE event_id IN (:eventIds)")
    suspend fun incrementRetryCount(eventIds: List<String>)

    /**
     * Post-increment retry counts for the given event ids. Used by the outbound
     * sync to decide whether each rejected event has reached the retry cap and
     * should be moved to `failed` instead of staying pending forever.
     */
    @Query("SELECT event_id, retry_count FROM coaching_event WHERE event_id IN (:eventIds)")
    suspend fun getRetryCounts(eventIds: List<String>): List<RetryCountRow>

    /** All events for a session, ordered chronologically. */
    @Query("SELECT * FROM coaching_event WHERE session_id = :sessionId ORDER BY timestamp_local ASC")
    suspend fun getBySession(sessionId: String): List<CoachingEventEntity>

    /**
     * On visit close ([com.medtroniclabs.microcoaching.MicroCoachingSDK.onVisitCompleted]),
     * stamp [encounterId] as `patient_visit_id` onto every still-pending row in
     * this session that lacks one. Returns the number of rows updated.
     *
     * Scoped to pending rows so already-synced events are never mutated — the
     * backend has them and any in-flight update would race the sync worker.
     */
    @Query(
        "UPDATE coaching_event SET patient_visit_id = :encounterId " +
            "WHERE session_id = :sessionId " +
            "AND chw_id = :chwId " +
            "AND patient_visit_id IS NULL " +
            "AND sync_status = 'pending'",
    )
    suspend fun backfillPatientVisitId(
        sessionId: String,
        chwId: String,
        encounterId: String,
    ): Int

    /** All events, most recent first. */
    @Query("SELECT * FROM coaching_event ORDER BY timestamp_local DESC")
    suspend fun getAll(): List<CoachingEventEntity>

    /** Delete all events that have been successfully synced (30-day retention cleanup). */
    @Query("DELETE FROM coaching_event WHERE sync_status = 'synced'")
    suspend fun deleteSynced()

    @Query("DELETE FROM coaching_event")
    suspend fun deleteAll()
}
