package com.medtroniclabs.microcoaching.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.medtroniclabs.microcoaching.data.db.entity.ChatMessageEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface ChatMessageDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(message: ChatMessageEntity): Long

    @Query("SELECT * FROM chat_messages WHERE session_id = :sessionId ORDER BY timestamp_ms ASC")
    suspend fun getBySession(sessionId: String): List<ChatMessageEntity>

    @Query("SELECT * FROM chat_messages WHERE session_id = :sessionId ORDER BY timestamp_ms ASC")
    fun observeSession(sessionId: String): Flow<List<ChatMessageEntity>>

    @Query("SELECT session_id FROM chat_messages GROUP BY session_id ORDER BY MIN(timestamp_ms) DESC")
    suspend fun getAllSessionIds(): List<String>

    @Query("SELECT * FROM chat_messages ORDER BY timestamp_ms DESC")
    suspend fun getAll(): List<ChatMessageEntity>

    @Query("DELETE FROM chat_messages WHERE session_id = :sessionId")
    suspend fun deleteSession(sessionId: String)

    @Query("DELETE FROM chat_messages")
    suspend fun deleteAll()

    /**
     * Most recent [limit] messages for the given CHW across all sessions and
     * conversations. Returned in DESCending order so the LIMIT clause caps
     * recency at the SQL layer; the repo reverses to ASC for chronological UI
     * rendering.
     *
     * Powered by the composite `(chw_id, timestamp_ms)` index on
     * [ChatMessageEntity] so even with thousands of rows the lookup stays O(log N).
     */
    @Query(
        "SELECT * FROM chat_messages WHERE chw_id = :chwId " +
            "ORDER BY timestamp_ms DESC LIMIT :limit",
    )
    suspend fun getRecentByChw(chwId: String, limit: Int): List<ChatMessageEntity>

    /** Hard-delete every chat message for this CHW. Backs the Clear-history action. */
    @Query("DELETE FROM chat_messages WHERE chw_id = :chwId")
    suspend fun deleteByChw(chwId: String)
}
