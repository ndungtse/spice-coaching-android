package com.medtroniclabs.microcoaching.data.db.migration

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * v17 → v18. Bundles two related module-sync additions that landed together.
 *
 * **Module thumbnail caching** — three columns on `module_cache`:
 *  - `has_thumbnail` — set from the `/sync/modules` payload; flags which module
 *    versions to request presigned thumbnail URLs for.
 *  - `thumbnail_url` — the resolved presigned GET URL, populated separately by
 *    thumbnail sync (`POST /sync/modules/presigned-thumbnails`). Nullable;
 *    re-fetched once `thumbnail_expires_at_epoch_sec` passes.
 *  - `thumbnail_expires_at_epoch_sec` — absolute expiry (epoch seconds) of the
 *    cached URL. Null means "needs fetch".
 *
 * **Rich source-document references** — the module sync payload moved from
 * `source_document_ids` (bare UUIDs) to `source_documents` (objects carrying
 * `title` / `original_filename`). Adds a `source_documents_json` column to both
 * `module_cache` and `chat_messages` to hold the rich list. The legacy
 * `source_document_ids_json` columns stay in place (deprecated) so id-only
 * consumers and pre-existing rows keep working.
 */
val MIGRATION_17_18 = object : Migration(17, 18) {
    override fun migrate(db: SupportSQLiteDatabase) {
        // ── Module thumbnail caching ─────────────────────────────────────────
        db.execSQL("ALTER TABLE module_cache ADD COLUMN has_thumbnail INTEGER NOT NULL DEFAULT 0")
        db.execSQL("ALTER TABLE module_cache ADD COLUMN thumbnail_url TEXT")
        db.execSQL("ALTER TABLE module_cache ADD COLUMN thumbnail_expires_at_epoch_sec INTEGER")

        // ── Rich source-document references ──────────────────────────────────
        db.execSQL("ALTER TABLE module_cache ADD COLUMN source_documents_json TEXT NOT NULL DEFAULT '[]'")
        db.execSQL("ALTER TABLE chat_messages ADD COLUMN source_documents_json TEXT NOT NULL DEFAULT '[]'")
    }
}
