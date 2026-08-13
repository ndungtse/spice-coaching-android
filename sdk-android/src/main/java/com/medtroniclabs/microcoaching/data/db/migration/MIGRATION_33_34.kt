package com.medtroniclabs.microcoaching.data.db.migration

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * v33 → v34: add `badge` for the CHW's achievement badges, and give
 * `published_source_document` the two columns that let one table serve both
 * halves of the source-document catalogue.
 *
 * `assigned_at` is what separates them: non-null marks a document assigned to this
 * CHW, which is what the Knowledge grid lists, while module-linked rows stay null
 * and exist so chat citations can still resolve a URL. `source_type` routes audio
 * and video to the Training sub-tab instead.
 *
 * Both are nullable, so rows written before this migration survive until the next
 * sync — which, since the table is replaced wholesale, is the very next cycle.
 */
val MIGRATION_33_34 = object : Migration(33, 34) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE `published_source_document` ADD COLUMN `source_type` TEXT")
        db.execSQL("ALTER TABLE `published_source_document` ADD COLUMN `assigned_at` TEXT")

        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `badge` (" +
                "`badge_id` TEXT NOT NULL, " +
                "`chw_id` TEXT NOT NULL, " +
                "`name` TEXT, " +
                "`domain` TEXT, " +
                "`image_storage_path` TEXT, " +
                "`image_url` TEXT, " +
                "`image_expires_at_epoch_sec` INTEGER, " +
                "`sequence` INTEGER NOT NULL DEFAULT 0, " +
                "`module_ids` TEXT, " +
                "`earned_at` TEXT, " +
                "`rank` INTEGER NOT NULL DEFAULT 0, " +
                "`last_synced` INTEGER, " +
                "PRIMARY KEY(`badge_id`))",
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_badge_chw_id` ON `badge` (`chw_id`)")
    }
}
