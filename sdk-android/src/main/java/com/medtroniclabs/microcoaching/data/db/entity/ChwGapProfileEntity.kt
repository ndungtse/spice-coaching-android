package com.medtroniclabs.microcoaching.data.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity

/**
 * Local mirror of the CHW's behavioural gap state per gap definition.
 *
 * Keyed on `(chw_id, behavioural_gap_id)` — mirrors the backend
 * `chw_behavioural_gap_state` table. Written locally on every quiz answer and
 * overwritten on `/sync/gaps` inbound sync (authoritative source).
 *
 * Gap classification rules (DataDesign v1.1 §4.4.1):
 *   knowledge gap      : wrongCount ≥ 2 AND counsellingUseRate < 0.20
 *   skill_application  : quizScorePct ≥ 70 AND counsellingUseRate < 0.20
 *   resolved           : consecutiveCorrect ≥ 2 AND cardsUsed ≥ 1
 */
@Entity(
    tableName = "chw_gap_profile_local",
    primaryKeys = ["chw_id", "behavioural_gap_id"],
)
data class ChwGapProfileEntity(

    @ColumnInfo(name = "chw_id")
    val chwId: String,

    /** The `behavioural_gap_id` UUID from the backend gap catalogue. */
    @ColumnInfo(name = "behavioural_gap_id")
    val behaviouralGapId: String,

    @ColumnInfo(name = "clinical_domain")
    val clinicalDomain: String,

    // ── Quiz performance ──────────────────────────────────────────────────────

    @ColumnInfo(name = "wrong_count")
    val wrongCount: Int = 0,

    @ColumnInfo(name = "consecutive_correct")
    val consecutiveCorrect: Int = 0,

    @ColumnInfo(name = "total_attempts")
    val totalAttempts: Int = 0,

    /** Correct / totalAttempts * 100. Null until first attempt. */
    @ColumnInfo(name = "quiz_score_pct")
    val quizScorePct: Float? = null,

    // ── Counselling engagement ────────────────────────────────────────────────

    @ColumnInfo(name = "cards_shown")
    val cardsShown: Int = 0,

    /** card_accepted + counselling_used events. */
    @ColumnInfo(name = "cards_used")
    val cardsUsed: Int = 0,

    @ColumnInfo(name = "cards_skipped")
    val cardsSkipped: Int = 0,

    /**
     * Ratio of card_accepted + counselling_used events to cards_shown.
     * Null until at least one card has been shown. Used in gap classification:
     * counsellingUseRate < 0.20 → skill_application gap candidate.
     */
    @ColumnInfo(name = "counselling_use_rate")
    val counsellingUseRate: Float? = null,

    // ── Gap state ─────────────────────────────────────────────────────────────

    @ColumnInfo(name = "gap_active")
    val gapActive: Boolean = true,

    /** knowledge | skill_application | null (no gap yet). */
    @ColumnInfo(name = "gap_type")
    val gapType: String? = null,

    // ── Timestamps ────────────────────────────────────────────────────────────

    @ColumnInfo(name = "last_seen")
    val lastSeen: Long? = null,

    @ColumnInfo(name = "resolved_at")
    val resolvedAt: Long? = null,

    @ColumnInfo(name = "last_synced")
    val lastSynced: Long? = null,

    // ── v3 escalation / reinforcement ────────────────────────────────────────

    /** Epoch millis the related module was last delivered for reinforcement. */
    @ColumnInfo(name = "last_reinforced_at")
    val lastReinforcedAt: Long? = null,

    /** Count of consecutive failed quiz attempts on the linked module. */
    @ColumnInfo(name = "failed_attempts_count")
    val failedAttemptsCount: Int = 0,

    @ColumnInfo(name = "last_failed_attempt_at")
    val lastFailedAttemptAt: Long? = null,

    /** True once [failedAttemptsCount] exceeds the escalation threshold inside the window. */
    @ColumnInfo(name = "escalated_to_supervisor")
    val escalatedToSupervisor: Boolean = false,
)
