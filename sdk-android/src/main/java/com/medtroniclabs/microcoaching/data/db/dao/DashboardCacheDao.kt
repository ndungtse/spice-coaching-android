package com.medtroniclabs.microcoaching.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.medtroniclabs.microcoaching.data.db.entity.DashboardCacheEntity

@Dao
interface DashboardCacheDao {

    /** The single cached snapshot, or null when the cache is empty. */
    @Query("SELECT * FROM dashboard_cache WHERE id = 0")
    suspend fun get(): DashboardCacheEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: DashboardCacheEntity)

    @Query("DELETE FROM dashboard_cache")
    suspend fun clear()
}
