package com.medtroniclabs.microcoaching.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.medtroniclabs.microcoaching.data.db.entity.LlmTraceEntity

@Dao
interface LlmTraceDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(trace: LlmTraceEntity)

    @Query("SELECT * FROM llm_trace WHERE sync_status = 'pending' ORDER BY timestamp_local ASC")
    suspend fun getPending(): List<LlmTraceEntity>

    @Query("UPDATE llm_trace SET sync_status = 'synced', synced_at = :syncedAt WHERE id IN (:ids)")
    suspend fun markSynced(ids: List<String>, syncedAt: Long = System.currentTimeMillis())

    @Query("UPDATE llm_trace SET sync_status = 'failed' WHERE id IN (:ids)")
    suspend fun markFailed(ids: List<String>)

    @Query("UPDATE llm_trace SET retry_count = retry_count + 1 WHERE id IN (:ids)")
    suspend fun incrementRetryCount(ids: List<String>)

    /**
     * Escalate to `sync_status = 'failed'` any row in [ids] whose `retry_count`
     * has reached [maxRetries]. Atomic counterpart to [CoachingEventDao.markFailed]
     * paired with the existing retry-cap logic in [SyncApi.pushPendingEvents] —
     * prevents permanently malformed traces from cycling forever.
     *
     * Returns the number of rows that just transitioned to failed, so the caller
     * can log how many were given up on.
     */
    @Query(
        "UPDATE llm_trace SET sync_status = 'failed' WHERE id IN (:ids) AND retry_count >= :maxRetries",
    )
    suspend fun markFailedIfExhausted(ids: List<String>, maxRetries: Int): Int

    @Query("SELECT * FROM llm_trace WHERE coaching_event_id = :eventId")
    suspend fun getByCoachingEvent(eventId: String): List<LlmTraceEntity>

    @Query("DELETE FROM llm_trace WHERE sync_status = 'synced'")
    suspend fun deleteSynced()

    @Query("DELETE FROM llm_trace")
    suspend fun deleteAll()
}
