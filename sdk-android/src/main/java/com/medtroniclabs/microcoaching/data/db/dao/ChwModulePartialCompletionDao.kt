package com.medtroniclabs.microcoaching.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.medtroniclabs.microcoaching.data.db.entity.ChwModulePartialCompletionEntity

@Dao
interface ChwModulePartialCompletionDao {

    @Query(
        "SELECT * FROM chw_module_partial_completion " +
            "WHERE chw_id = :chwId AND module_family_id = :moduleFamilyId",
    )
    suspend fun get(chwId: String, moduleFamilyId: String): ChwModulePartialCompletionEntity?

    @Query("SELECT * FROM chw_module_partial_completion WHERE chw_id = :chwId")
    suspend fun getAllForChw(chwId: String): List<ChwModulePartialCompletionEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(row: ChwModulePartialCompletionEntity)

    @Query("DELETE FROM chw_module_partial_completion WHERE chw_id = :chwId")
    suspend fun deleteForChw(chwId: String)
}
