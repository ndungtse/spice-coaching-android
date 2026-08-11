package com.medtroniclabs.microcoaching.data.db.dao

import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import com.medtroniclabs.microcoaching.data.db.entity.ModuleEntity
import kotlinx.coroutines.flow.Flow

/**
 * Slim projection for the training-request module picker — one row per module
 * family (latest cached version), without the multi-MB cards/quiz JSON blobs.
 * Carries both ids: [moduleId] (version PK — what the training-request API
 * takes) and [moduleFamilyId] (stable across versions — what UI routes carry).
 */
data class ModulePickerRow(
    @ColumnInfo(name = "module_id") val moduleId: String,
    @ColumnInfo(name = "module_family_id") val moduleFamilyId: String,
    @ColumnInfo(name = "title_json") val titleJson: String,
    @ColumnInfo(name = "domain") val domain: String,
    @ColumnInfo(name = "thumbnail_url") val thumbnailUrl: String?,
)

@Dao
interface ModuleDao {

    // The full-entity queries below are @Transaction for CURSOR-WINDOW
    // CONSISTENCY, not for multi-statement atomicity: module rows carry large
    // cards/quiz JSON blobs, so only ~30 rows fit per 2 MB CursorWindow and big
    // catalogues span several windows. Outside a transaction each window refill
    // re-runs the query against the LIVE table — a concurrent sync delete
    // between fills shrinks the result set and reading the now-missing row
    // throws "Couldn't read row N from CursorWindow" (seen as a fresh-install
    // crash when the full-catalogue prune raced the first reads). Inside a
    // transaction every refill sees one stable snapshot.

    /**
     * Backend only ships published modules through `/sync/modules`, so every
     * row in the cache is active. Sorted for stable UI ordering.
     */
    @Transaction
    @Query("SELECT * FROM module_cache ORDER BY domain, module_id")
    fun getAllActive(): Flow<List<ModuleEntity>>

    /** One-shot read for joins — used by [MicroCoachingSDK.resolveFromCache]. */
    @Transaction
    @Query("SELECT * FROM module_cache ORDER BY domain, module_id")
    suspend fun getAllOrderedOnce(): List<ModuleEntity>

    /** Look up by module version id (the primary key). */
    @Query("SELECT * FROM module_cache WHERE module_id = :moduleId")
    suspend fun getById(moduleId: String): ModuleEntity?

    /**
     * Look up the latest version of a module family. When multiple versions of
     * the same family are cached (after an update), this returns the highest
     * version number.
     */
    @Query("SELECT * FROM module_cache WHERE module_family_id = :familyId ORDER BY version DESC LIMIT 1")
    suspend fun getByFamilyId(familyId: String): ModuleEntity?

    @Transaction
    @Query("SELECT * FROM module_cache WHERE domain = :domain ORDER BY module_id")
    fun getByDomain(domain: String): Flow<List<ModuleEntity>>

    @Query("SELECT COUNT(*) FROM module_cache")
    suspend fun countActive(): Int

    /**
     * Just the `source_documents_json` column — for consumers that only need
     * source-document references (e.g. thumbnail refresh). Loading the full
     * entity pulled every module's cards/quiz JSON blobs into memory to read
     * one small column.
     */
    @Query("SELECT source_documents_json FROM module_cache")
    suspend fun allSourceDocumentsJson(): List<String>

    /**
     * One slim row per module family (latest cached version) — feeds the
     * training-request module picker and title fallbacks. Deliberately NOT the
     * full entity: [getAllOrderedOnce] drags every module's cards/quiz JSON
     * blobs through the CursorWindow (see the note at the top of this DAO).
     */
    @Query(
        """
        SELECT m.module_id AS module_id, m.module_family_id AS module_family_id, m.title_json AS title_json,
               m.domain AS domain, m.thumbnail_url AS thumbnail_url
        FROM module_cache m
        JOIN (
            SELECT module_family_id, MAX(version) AS v
            FROM module_cache GROUP BY module_family_id
        ) latest
          ON m.module_family_id = latest.module_family_id AND m.version = latest.v
        """,
    )
    suspend fun pickerRowsLatestPerFamily(): List<ModulePickerRow>

    /**
     * Live variant of [pickerRowsLatestPerFamily] — the training-request form
     * observes this so an inbound sync landing while the form is open (retired
     * families pruned, new publishes added) updates the picker in place.
     */
    @Query(
        """
        SELECT m.module_id AS module_id, m.module_family_id AS module_family_id, m.title_json AS title_json,
               m.domain AS domain, m.thumbnail_url AS thumbnail_url
        FROM module_cache m
        JOIN (
            SELECT module_family_id, MAX(version) AS v
            FROM module_cache GROUP BY module_family_id
        ) latest
          ON m.module_family_id = latest.module_family_id AND m.version = latest.v
        """,
    )
    fun observePickerRowsLatestPerFamily(): Flow<List<ModulePickerRow>>

    /**
     * Distinct module-family ids currently cached. Used by the full-catalogue
     * reconcile in [com.medtroniclabs.microcoaching.sync.SyncApi.pullModules] to
     * find families the server no longer publishes (terminally retired).
     */
    @Query("SELECT DISTINCT module_family_id FROM module_cache")
    suspend fun distinctFamilyIds(): List<String>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(modules: List<ModuleEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(module: ModuleEntity)

    /** Remove specific module versions. */
    @Query("DELETE FROM module_cache WHERE module_id IN (:moduleIds)")
    suspend fun deleteByIds(moduleIds: List<String>)

    /**
     * Remove **every** cached version of the given families. Used by the
     * full-catalogue reconcile to drop terminally-retired families (no published
     * version remains server-side). Keyed on `module_family_id`, NOT `module_id`,
     * because retirement is a family-level concept and a family may hold multiple
     * cached versions. Returns the number of rows deleted.
     */
    @Query("DELETE FROM module_cache WHERE module_family_id IN (:familyIds)")
    suspend fun deleteByFamilyIds(familyIds: List<String>): Int

    /**
     * Drop every cached version of a family except the latest. Used by sync
     * after a new published version arrives. Returns the number of rows deleted.
     */
    @Query(
        """
        DELETE FROM module_cache
        WHERE module_family_id = :familyId AND version < (
            SELECT MAX(version) FROM module_cache WHERE module_family_id = :familyId
        )
        """,
    )
    suspend fun pruneOldVersions(familyId: String): Int

    @Query("DELETE FROM module_cache")
    suspend fun deleteAll()
}
