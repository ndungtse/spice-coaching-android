package com.medtroniclabs.microcoaching.data.db.migration

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * v14 → v15: chat history persistence by CHW.
 *
 * Adds:
 *   - `chat_messages.chw_id` (TEXT NOT NULL DEFAULT '') — grouping key for the
 *     recency query that powers chat-history restoration on sheet reopen.
 *   - `chat_messages.conversation_id` (TEXT NULL) — placeholder for the future
 *     Conversation feature; remains NULL until threading ships.
 *   - composite index `(chw_id, timestamp_ms)` matching the recency query so it
 *     doesn't fall back to a table scan.
 *
 * Legacy rows pre-migration get `chw_id = ''` and `conversation_id = NULL`.
 * Those rows survive in the DB but won't surface in CHW-filtered queries —
 * which is the intended behaviour: they predate the persistence feature and
 * lack a real owner. They're cleaned up if the user later taps "Clear chat".
 *
 * Pre-release, the SDK's Room builder also has `fallbackToDestructiveMigration`
 * as a safety net, so any future schema bump without an explicit migration
 * won't crash. This file is the first explicit migration shipped.
 */
val MIGRATION_14_15 = object : Migration(14, 15) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE chat_messages ADD COLUMN chw_id TEXT NOT NULL DEFAULT ''")
        db.execSQL("ALTER TABLE chat_messages ADD COLUMN conversation_id TEXT")
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS index_chat_messages_chw_id_timestamp_ms " +
                "ON chat_messages(chw_id, timestamp_ms)",
        )
    }
}
