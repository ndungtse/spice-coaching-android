package com.medtroniclabs.microcoaching.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.medtroniclabs.microcoaching.data.db.entity.ModuleTriggerBindingEntity

@Dao
interface ModuleTriggerBindingDao {

    @Query("SELECT * FROM module_trigger_binding")
    suspend fun getAll(): List<ModuleTriggerBindingEntity>

    @Query("SELECT * FROM module_trigger_binding WHERE trigger_definition_id = :triggerId ORDER BY priority_weight DESC")
    suspend fun getByTrigger(triggerId: String): List<ModuleTriggerBindingEntity>

    @Query("SELECT * FROM module_trigger_binding WHERE module_family_id = :moduleFamilyId")
    suspend fun getByModule(moduleFamilyId: String): List<ModuleTriggerBindingEntity>

    @Query("SELECT COUNT(*) FROM module_trigger_binding")
    suspend fun count(): Int

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(bindings: List<ModuleTriggerBindingEntity>)

    @Query("DELETE FROM module_trigger_binding WHERE binding_id IN (:ids)")
    suspend fun deleteByIds(ids: List<String>)

    /**
     * Remove bindings for terminally-retired module families (the SDK side of F1).
     * The backend does not cascade binding cleanup on retirement, so the SDK does
     * it locally. Not safety-critical — [com.medtroniclabs.microcoaching.domain.triggers.TriggerEvaluator]
     * already fails safe on a missing module — but it keeps the table clean.
     * Returns the number of rows deleted.
     */
    @Query("DELETE FROM module_trigger_binding WHERE module_family_id IN (:familyIds)")
    suspend fun deleteByModuleFamilyIds(familyIds: List<String>): Int

    @Query("DELETE FROM module_trigger_binding")
    suspend fun deleteAll()
}
