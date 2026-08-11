package com.medtroniclabs.microcoaching.data.db.migration

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * v16 → v17. Bundles two independently-developed changes that landed at the
 * same Room version:
 *
 * **Gap-rule envelope persistence** (`feat/hooks-and-gap-detection-sdk`):
 *  - `behavioural_gap_cache.detection_rule` — JSON-serialised
 *    `detection_rule_jsonb` envelope from `/sync/gaps`. Before this column
 *    the SDK dropped the envelope at the SyncApi mapping boundary; rule
 *    evaluators now read it at SPICE event time and dispatch per
 *    `rule_type`.
 *
 * **Source-document attribution for chat citations** (`feat/chat-optimization`):
 *  - `module_cache.source_document_ids_json` — JSON-encoded `List<String>`
 *    of source-document UUIDs from the modules sync payload. Empty array
 *    for legacy rows.
 *  - `chat_messages.source_document_ids_json` — JSON-encoded `List<String>`
 *    carried on each assistant message so citation chips survive in history
 *    without re-querying the module the answer came from.
 *  - `chat_messages.grounding_module_family_id` — the dominant BM25-matched
 *    module family for the message, used at render time to resolve the chip
 *    label (`{module title} — SA N`) in the active SDK locale.
 *
 * Earlier drafts of the gap branch also created a `facility_cache` table
 * for a `FacilityDao` lookup path. That layer was removed when we settled
 * on Path A — SPICE writes the picked facility's tier directly into the
 * SDK map as `picked_facility_type`, so the SDK no longer needs to look up
 * tiers via a local facility cache. Any pre-release device that picked up
 * the early `facility_cache` table can leave it in place: it's an unused
 * SQLite table; `fallbackToDestructiveMigration` will clean it up at the
 * next schema bump.
 */
val MIGRATION_16_17 = object : Migration(16, 17) {
    override fun migrate(db: SupportSQLiteDatabase) {
        // ── Gap-rule envelope persistence ────────────────────────────────────
        db.execSQL("ALTER TABLE behavioural_gap_cache ADD COLUMN detection_rule TEXT")

        // ── Source-document attribution for chat citations ───────────────────
        db.execSQL(
            "ALTER TABLE module_cache ADD COLUMN source_document_ids_json TEXT NOT NULL DEFAULT '[]'",
        )
        db.execSQL(
            "ALTER TABLE chat_messages ADD COLUMN source_document_ids_json TEXT NOT NULL DEFAULT '[]'",
        )
        db.execSQL(
            "ALTER TABLE chat_messages ADD COLUMN grounding_module_family_id TEXT",
        )
    }
}
