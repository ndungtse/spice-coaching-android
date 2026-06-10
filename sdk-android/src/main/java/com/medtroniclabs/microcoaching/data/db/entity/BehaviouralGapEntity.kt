package com.medtroniclabs.microcoaching.data.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Local cache of the v3.3 behavioural-gap taxonomy synced from the backend.
 *
 * Source: `GET /sync/v3/gaps` — see `serialise_gap()` in
 * `coaching-platform/services/platform/src/platform_service/services/sync_bundle_serialiser.py`.
 *
 * This is the *catalogue* of gaps the backend knows about (e.g.
 * `missed_hypertension_referral_threshold`). It is distinct from
 * [ChwGapProfileEntity], which records *this CHW's per-scenario performance*.
 *
 * Modules link to a row here via [ModuleEntity.primaryGapId] = [gapId]. The
 * catalogue lets the device:
 *   - Resolve module → human-readable gap description for the learn screen
 *   - Pre-warm the morning card selector with server-known gap codes
 *   - Detect deprecated gaps so locally-derived [ChwGapProfileEntity] rows can
 *     be reconciled
 */
@Entity(
    tableName = "behavioural_gap_cache",
    indices = [
        Index(value = ["gap_code"], unique = true),
        Index(value = ["domain"]),
    ],
)
data class BehaviouralGapEntity(

    /** Server UUID — primary key. Modules reference this via primary_gap_id. */
    @PrimaryKey
    @ColumnInfo(name = "gap_id")
    val gapId: String,

    /** Stable string identifier (e.g. `missed_hypertension_referral_threshold`). */
    @ColumnInfo(name = "gap_code")
    val gapCode: String,

    @ColumnInfo(name = "description")
    val description: String? = null,

    @ColumnInfo(name = "domain")
    val domain: String? = null,

    /** "low" | "moderate" | "high". */
    @ColumnInfo(name = "severity_default")
    val severityDefault: String? = null,

    /** "active" | "deprecated". Deprecated rows are pruned at upsert time. */
    @ColumnInfo(name = "status")
    val status: String = "active",

    /**
     * JSON-serialised `detection_rule_jsonb` envelope from `/sync/gaps` — the
     * `{schema_version, rule_type, params, match}` shape consumed by
     * [com.medtroniclabs.microcoaching.domain.gaps.GapRuleDispatcher].
     *
     * Null or `{}` for quiz-only gaps (action path skips them).
     */
    @ColumnInfo(name = "detection_rule")
    val detectionRule: String? = null,

    @ColumnInfo(name = "last_synced")
    val lastSynced: Long = System.currentTimeMillis(),
)
