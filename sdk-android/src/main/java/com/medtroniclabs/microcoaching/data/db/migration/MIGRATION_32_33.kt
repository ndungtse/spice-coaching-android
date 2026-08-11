package com.medtroniclabs.microcoaching.data.db.migration

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * v32 → v33: give `assigned_video` somewhere to keep the media's own presigned
 * URL, and add `requested_module` for the server-side training-request history.
 *
 * The URL columns exist because the catalogue returns the playable URL inline and
 * nothing else can re-derive it. Both are nullable so existing rows survive until
 * the next sync fills them in.
 */
val MIGRATION_32_33 = object : Migration(32, 33) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE `assigned_video` ADD COLUMN `presigned_url` TEXT")
        db.execSQL("ALTER TABLE `assigned_video` ADD COLUMN `presigned_expires_at_epoch_sec` INTEGER")

        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `requested_module` (" +
                "`request_id` TEXT NOT NULL, " +
                "`chw_id` TEXT NOT NULL, " +
                "`module_id` TEXT, " +
                "`requested_module_name` TEXT, " +
                "`reason` TEXT, " +
                "`submitted_at` TEXT, " +
                "`last_synced` INTEGER, " +
                "PRIMARY KEY(`request_id`))",
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_requested_module_chw_id` ON `requested_module` (`chw_id`)")
    }
}
