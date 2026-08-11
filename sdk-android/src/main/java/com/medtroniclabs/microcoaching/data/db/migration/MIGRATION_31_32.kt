package com.medtroniclabs.microcoaching.data.db.migration

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * v31 → v32: add the single-row `dashboard_cache` table backing offline PO
 * dashboard viewing (MED-I516).
 */
val MIGRATION_31_32 = object : Migration(31, 32) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `dashboard_cache` (" +
                "`id` INTEGER NOT NULL, " +
                "`chw_id` TEXT NOT NULL, " +
                "`from_date` TEXT NOT NULL, " +
                "`to_date` TEXT NOT NULL, " +
                "`payload_json` TEXT NOT NULL, " +
                "`fetched_at` INTEGER NOT NULL, " +
                "PRIMARY KEY(`id`))",
        )
    }
}
