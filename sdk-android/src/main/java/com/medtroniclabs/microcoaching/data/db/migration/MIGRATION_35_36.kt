package com.medtroniclabs.microcoaching.data.db.migration

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * v35 → v36: `card_embedding` — per-card vectors from `GET /sync/card-embeddings`,
 * keyed by the card's backend UUID and stored as L2-normalized little-endian
 * float32 BLOBs for the dense retrieval index.
 */
val MIGRATION_35_36 = object : Migration(35, 36) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `card_embedding` (
                `card_id` TEXT NOT NULL PRIMARY KEY,
                `module_family_id` TEXT NOT NULL,
                `dim` INTEGER NOT NULL,
                `model_id` TEXT,
                `vec` BLOB NOT NULL,
                `synced_at_ms` INTEGER NOT NULL
            )
            """.trimIndent(),
        )
    }
}
