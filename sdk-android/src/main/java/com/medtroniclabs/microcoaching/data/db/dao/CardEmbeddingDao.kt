package com.medtroniclabs.microcoaching.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.medtroniclabs.microcoaching.data.db.entity.CardEmbeddingEntity

@Dao
interface CardEmbeddingDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(rows: List<CardEmbeddingEntity>)

    @Query("SELECT * FROM card_embedding")
    suspend fun getAll(): List<CardEmbeddingEntity>

    @Query("SELECT COUNT(*) FROM card_embedding")
    suspend fun count(): Int

    /** Vectors from a different encoder cannot match on-device query vectors — drop them. */
    @Query("DELETE FROM card_embedding WHERE model_id IS NOT :modelId")
    suspend fun clearOtherModels(modelId: String)

    @Query("DELETE FROM card_embedding")
    suspend fun clear()
}
