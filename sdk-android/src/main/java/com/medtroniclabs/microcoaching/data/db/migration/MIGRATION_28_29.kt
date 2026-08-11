package com.medtroniclabs.microcoaching.data.db.migration

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * v28 → v29: chat FAQ suggestions cache.
 *
 * Adds the `chat_faq` table — the durable mirror of `GET /sync/chat-faqs`. The
 * chat suggestion chips read this table (falling back to the static defaults
 * when empty). `question_json` holds a serialized `LocalizedText` (`{bn, en?}`);
 * the English side is backfilled by on-device translation. Starts empty and
 * populates on the next `/sync/chat-faqs` pull.
 */
val MIGRATION_28_29 = object : Migration(28, 29) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS chat_faq (
                faq_id TEXT NOT NULL,
                question_json TEXT NOT NULL,
                rank INTEGER NOT NULL DEFAULT 0,
                occurrence_count INTEGER NOT NULL DEFAULT 0,
                last_seen_at TEXT,
                last_synced INTEGER,
                PRIMARY KEY (faq_id)
            )
            """.trimIndent(),
        )
    }
}
