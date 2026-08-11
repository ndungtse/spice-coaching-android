package com.medtroniclabs.microcoaching.data.db.migration

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * v29 → v30: assigned-video catalogue.
 *
 * Adds the `assigned_video` table — the durable mirror of
 * `GET /sync/assigned-videos?user_id=…` that backs the Training sub-tab. Stores
 * per-video metadata + the inline thumbnail presigned URL (with absolute expiry)
 * and the CHW's watch progress (`last_position_ms`, `percent_watched`,
 * `completed`, `last_watched_at`) for resume + YouTube-style progress bars.
 * Starts empty and populates on the next `/sync/assigned-videos` pull.
 */
val MIGRATION_29_30 = object : Migration(29, 30) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS assigned_video (
                video_id TEXT NOT NULL,
                chw_id TEXT NOT NULL,
                title TEXT,
                description TEXT,
                duration_ms INTEGER NOT NULL DEFAULT 0,
                assigned_at TEXT,
                thumbnail_url TEXT,
                thumbnail_expires_at_epoch_sec INTEGER,
                thumbnail_storage_path TEXT,
                last_position_ms INTEGER NOT NULL DEFAULT 0,
                percent_watched REAL NOT NULL DEFAULT 0.0,
                completed INTEGER NOT NULL DEFAULT 0,
                last_watched_at TEXT,
                rank INTEGER NOT NULL DEFAULT 0,
                last_synced INTEGER,
                PRIMARY KEY (video_id)
            )
            """.trimIndent(),
        )
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS index_assigned_video_chw_id ON assigned_video (chw_id)",
        )
    }
}
