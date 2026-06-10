package com.medtroniclabs.microcoaching.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.medtroniclabs.microcoaching.data.db.entity.ChwGapProfileEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface ChwGapProfileDao {

    @Query("SELECT * FROM chw_gap_profile_local WHERE chw_id = :chwId AND behavioural_gap_id = :behaviouralGapId")
    suspend fun getProfile(chwId: String, behaviouralGapId: String): ChwGapProfileEntity?

    @Query("SELECT * FROM chw_gap_profile_local WHERE chw_id = :chwId AND gap_active = 1 ORDER BY wrong_count DESC")
    fun getActiveGaps(chwId: String): Flow<List<ChwGapProfileEntity>>

    @Query("SELECT * FROM chw_gap_profile_local WHERE chw_id = :chwId ORDER BY wrong_count DESC")
    suspend fun getAllForChw(chwId: String): List<ChwGapProfileEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(gap: ChwGapProfileEntity)

    @Query("DELETE FROM chw_gap_profile_local WHERE chw_id = :chwId")
    suspend fun deleteForChw(chwId: String)
}
