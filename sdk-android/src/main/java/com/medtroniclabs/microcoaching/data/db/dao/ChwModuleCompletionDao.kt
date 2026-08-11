package com.medtroniclabs.microcoaching.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.medtroniclabs.microcoaching.data.db.entity.ChwModuleCompletionEntity

@Dao
interface ChwModuleCompletionDao {

    @Query("SELECT * FROM chw_module_completion WHERE chw_id = :chwId AND module_family_id = :moduleFamilyId")
    suspend fun get(chwId: String, moduleFamilyId: String): ChwModuleCompletionEntity?

    @Query("SELECT * FROM chw_module_completion WHERE chw_id = :chwId")
    suspend fun getAllForChw(chwId: String): List<ChwModuleCompletionEntity>

    @Query(
        """
        SELECT * FROM chw_module_completion
        WHERE chw_id = :chwId
          AND reinforcement_due_at IS NOT NULL
          AND reinforcement_due_at <= :nowMillis
        """,
    )
    suspend fun getDueForReinforcement(chwId: String, nowMillis: Long): List<ChwModuleCompletionEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(row: ChwModuleCompletionEntity)

    @Query("DELETE FROM chw_module_completion WHERE chw_id = :chwId")
    suspend fun deleteForChw(chwId: String)
}
