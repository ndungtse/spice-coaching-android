package com.medtroniclabs.microcoaching.data.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity

/**
 * Local cache of v3 config thresholds.
 *
 * Source: `GET /sync/config` (currently 404 server-side; the SDK call
 * tolerates the empty bundle and falls back to [MicroCoachingConfig] defaults).
 *
 * Each row is a (key, value) pair scoped either globally (when
 * [moduleFamilyId] is null) or to a single module family. Lookup order at
 * runtime is: module-scoped row → global row → [MicroCoachingConfig] default.
 *
 * Composite primary key on (module_family_id, key). For global rows,
 * [moduleFamilyId] is stored as the empty string so Room's NOT-NULL primary
 * key constraint is satisfied.
 */
@Entity(
    tableName = "config_threshold",
    primaryKeys = ["module_family_id", "key"],
)
data class ConfigThresholdEntity(

    /** Module family scope, or empty string for global rows. */
    @ColumnInfo(name = "module_family_id")
    val moduleFamilyId: String,

    @ColumnInfo(name = "key")
    val key: String,

    @ColumnInfo(name = "value")
    val value: String,

    @ColumnInfo(name = "last_synced")
    val lastSynced: Long = System.currentTimeMillis(),
) {
    val isGlobal: Boolean get() = moduleFamilyId.isEmpty()

    companion object {
        /** Sentinel stored in [moduleFamilyId] for global rows. */
        const val GLOBAL_SCOPE: String = ""
    }
}
