package com.medtroniclabs.microcoaching.data.db.migration

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * v30 → v31: capture the module content-domain taxonomy.
 *
 * Adds `module_cache.content_domain` (`TEXT`, nullable) so the SK/PO
 * content-domain tag ("clinical" | "digital" | "operational") on Learning Library
 * & Practice Zone cards is backed by the authored `module.content_domain` value
 * (Med-I617). Pre-migration rows default to null (rendered as "clinical", the
 * documented default) and are refreshed on the next module sync.
 */
val MIGRATION_30_31 = object : Migration(30, 31) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE module_cache ADD COLUMN content_domain TEXT")
    }
}
