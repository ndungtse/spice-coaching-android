package com.medtroniclabs.microcoaching.data.db.migration

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * v15 → v16: `chw_module_partial_completion` table — cross-device CHW progress
 * recovery via the server's authoritative `incomplete_quiz_ids` per
 * `(chw_id, module_family_id)`.
 *
 * Hydrated by `/sync/gaps` (see `GapsSyncBundle.chwModulePartialCompletions`)
 * and consumed by `ToReinforceResolver` to seed the refresher / banner /
 * morning-card filter on a fresh device where the local `coaching_event`
 * history is empty.
 */
val MIGRATION_15_16 = object : Migration(15, 16) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS chw_module_partial_completion (
                chw_id TEXT NOT NULL,
                module_family_id TEXT NOT NULL,
                module_id TEXT,
                incomplete_quiz_ids_json TEXT NOT NULL,
                tenant_id TEXT,
                updated_at INTEGER NOT NULL,
                PRIMARY KEY(chw_id, module_family_id)
            )
            """.trimIndent(),
        )
    }
}
