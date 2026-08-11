package com.medtroniclabs.microcoaching.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import com.medtroniclabs.microcoaching.data.db.entity.RequestedModuleEntity
import kotlinx.coroutines.flow.Flow

/**
 * Read/replace access for the server's view of a CHW's training requests.
 *
 * The server snapshot is authoritative for its own rows, so [replaceForUser]
 * swaps them wholesale — but only this table. The local `module_requested` event
 * log is a separate, additive source and is never touched here.
 */
@Dao
interface RequestedModuleDao {

    /** The CHW's server-known requests, newest first. */
    @Query("SELECT * FROM requested_module WHERE chw_id = :chwId ORDER BY submitted_at DESC")
    fun observeForUser(chwId: String): Flow<List<RequestedModuleEntity>>

    /** One-shot read for the submit-form duplicate guard. */
    @Query("SELECT * FROM requested_module WHERE chw_id = :chwId")
    suspend fun getForUser(chwId: String): List<RequestedModuleEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(rows: List<RequestedModuleEntity>)

    @Query("DELETE FROM requested_module WHERE chw_id = :chwId")
    suspend fun deleteForUser(chwId: String)

    /**
     * Replace this CHW's server rows with [rows]. Unlike the other catalogue
     * tables an empty list is honoured: a CHW whose requests were all cleared
     * server-side should end up with none, and the local event log still holds
     * anything they submitted from this device.
     */
    @Transaction
    suspend fun replaceForUser(chwId: String, rows: List<RequestedModuleEntity>) {
        deleteForUser(chwId)
        if (rows.isNotEmpty()) upsertAll(rows)
    }
}
