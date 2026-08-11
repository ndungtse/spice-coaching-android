package com.medtroniclabs.microcoaching.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.medtroniclabs.microcoaching.data.db.entity.TriggerDefinitionEntity

@Dao
interface TriggerDefinitionDao {

    @Query("SELECT * FROM trigger_definition WHERE status = 'active'")
    suspend fun getAllActive(): List<TriggerDefinitionEntity>

    @Query("SELECT * FROM trigger_definition WHERE trigger_kind = :kind AND status = 'active'")
    suspend fun getByKind(kind: String): List<TriggerDefinitionEntity>

    @Query("SELECT * FROM trigger_definition WHERE trigger_id = :id")
    suspend fun getById(id: String): TriggerDefinitionEntity?

    @Query("SELECT * FROM trigger_definition WHERE trigger_code = :code")
    suspend fun getByCode(code: String): TriggerDefinitionEntity?

    @Query("SELECT COUNT(*) FROM trigger_definition WHERE status = 'active'")
    suspend fun countActive(): Int

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(triggers: List<TriggerDefinitionEntity>)

    @Query("DELETE FROM trigger_definition WHERE trigger_id IN (:ids)")
    suspend fun deleteByIds(ids: List<String>)

    @Query("DELETE FROM trigger_definition")
    suspend fun deleteAll()
}
