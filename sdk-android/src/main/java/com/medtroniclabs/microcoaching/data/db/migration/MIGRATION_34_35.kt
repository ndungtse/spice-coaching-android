package com.medtroniclabs.microcoaching.data.db.migration

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * v34 → v35: keep the object path beside every presigned URL, carry a document's
 * description, and give `badge` somewhere to record an on-device win.
 *
 * The storage paths matter because a presigned URL is the only thing that expires:
 * holding the path it was signed from is what lets one be renewed on demand instead
 * of waiting for the next full sync.
 *
 * `locally_earned_at` is deliberately not `earned_at` — that column is replaced
 * wholesale on every badge sync, so a locally-written value there would vanish on
 * the next tick.
 *
 * All nullable, so existing rows survive until the next sync fills them.
 */
val MIGRATION_34_35 = object : Migration(34, 35) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE `published_source_document` ADD COLUMN `description` TEXT")
        db.execSQL("ALTER TABLE `published_source_document` ADD COLUMN `storage_path` TEXT")
        db.execSQL("ALTER TABLE `published_source_document` ADD COLUMN `thumbnail_storage_path` TEXT")

        db.execSQL("ALTER TABLE `assigned_video` ADD COLUMN `storage_path` TEXT")

        db.execSQL("ALTER TABLE `badge` ADD COLUMN `locally_earned_at` TEXT")
    }
}
