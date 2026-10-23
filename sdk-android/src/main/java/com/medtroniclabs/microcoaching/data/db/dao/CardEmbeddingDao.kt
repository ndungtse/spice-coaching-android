package com.medtroniclabs.microcoaching.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.medtroniclabs.microcoaching.data.db.entity.CardEmbeddingEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface CardEmbeddingDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(rows: List<CardEmbeddingEntity>)

    @Query("SELECT * FROM card_embedding")
    suspend fun getAll(): List<CardEmbeddingEntity>

    @Query("SELECT COUNT(*) FROM card_embedding")
    suspend fun count(): Int

    /** Emits on every write to the table, so the dense index can rebuild when vectors land. */
    @Query("SELECT COUNT(*) FROM card_embedding")
    fun observeCount(): Flow<Int>

    /**
     * The encoder the stored vectors came from, or null when no row names one.
     * Rows are written one bundle at a time and the whole table is cleared whenever
     * the bundle's model changes, so any row's id speaks for all of them.
     */
    @Query("SELECT model_id FROM card_embedding WHERE model_id IS NOT NULL LIMIT 1")
    suspend fun anyModelId(): String?

    @Query("DELETE FROM card_embedding")
    suspend fun clear()
}
