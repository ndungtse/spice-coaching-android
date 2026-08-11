package com.medtroniclabs.microcoaching.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.medtroniclabs.microcoaching.data.db.entity.ChatFaqEntity
import kotlinx.coroutines.flow.Flow

/**
 * Read/write access for the synced chat-FAQ suggestions. The list is
 * server-authoritative (upserted per sync); the only in-place mutation is
 * [updateQuestionJson], used to backfill a translated English question.
 */
@Dao
interface ChatFaqDao {

    /** Reactive suggestion source, highest-priority first. */
    @Query("SELECT * FROM chat_faq ORDER BY rank ASC")
    fun getAllOrderedByRank(): Flow<List<ChatFaqEntity>>

    /**
     * One-shot snapshot for the translation scan. The FAQ set is small, so the
     * "needs English" filter is done in Kotlin (parse `question_json`) rather
     * than a fragile JSON `LIKE` predicate.
     */
    @Query("SELECT * FROM chat_faq ORDER BY rank ASC")
    suspend fun getAllOnce(): List<ChatFaqEntity>

    @Query("SELECT COUNT(*) FROM chat_faq")
    suspend fun count(): Int

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(rows: List<ChatFaqEntity>)

    /** Backfill the translated question blob — touches only `question_json`. */
    @Query("UPDATE chat_faq SET question_json = :questionJson WHERE faq_id = :faqId")
    suspend fun updateQuestionJson(faqId: String, questionJson: String)
}
