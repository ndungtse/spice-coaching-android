package com.medtroniclabs.microcoaching.data.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Local cache of v3 trigger definitions synced from the backend.
 *
 * Source: `GET /sync/triggers` — see `serialise_trigger()` in the backend
 * `sync_bundle_serialiser.py`.
 *
 * A trigger is a structured rule that maps an event signal to a module via
 * [ModuleTriggerBindingEntity]. Trigger kinds:
 *   - `gap`            — fires when a CHW's [ChwGapProfileEntity] shows a
 *                        pattern (occurrence count over a window)
 *   - `workflow_event` — fires on a specific SPICE callback (form_submitted,
 *                        rule_fired, assessment_submitted, …)
 *   - `content_push`   — server-side only; ignored on device
 *
 * Predicate shape varies by kind and is held as raw JSON so the device can
 * evolve without schema bumps.
 */
@Entity(
    tableName = "trigger_definition",
    indices = [
        Index(value = ["trigger_kind"]),
        Index(value = ["trigger_code"], unique = true),
    ],
)
data class TriggerDefinitionEntity(

    @PrimaryKey
    @ColumnInfo(name = "trigger_id")
    val triggerId: String,

    /** "gap" | "workflow_event" | "content_push". */
    @ColumnInfo(name = "trigger_kind")
    val triggerKind: String,

    /** Stable string identifier (e.g. `gap:missed_hypertension_referral`). */
    @ColumnInfo(name = "trigger_code")
    val triggerCode: String,

    @ColumnInfo(name = "description")
    val description: String? = null,

    /** Raw JSON predicate; structure depends on [triggerKind]. */
    @ColumnInfo(name = "predicate_json")
    val predicateJson: String,

    /** "active" | "deprecated". Deprecated rows are pruned at upsert time. */
    @ColumnInfo(name = "status")
    val status: String = "active",

    @ColumnInfo(name = "last_synced")
    val lastSynced: Long = System.currentTimeMillis(),
)
