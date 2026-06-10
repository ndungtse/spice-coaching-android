package com.medtroniclabs.microcoaching.data.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity

/**
 * Per-CHW per-module completion state.
 *
 * Mirrors the server-side `chw_module_completion` table (DataModel v3 §7.2).
 * Updated locally when the CHW finishes a module's quiz; reconciled with the
 * server via telemetry events (no inbound sync of this table — telemetry is
 * the source of truth backend-side).
 *
 * Tracks both pass state (immutable across reinforcement cycles) and the
 * most-recent attempt (mutable). A pass clears the related
 * [ChwGapProfileEntity]; repeated failures within an escalation window
 * promote the CHW to supervisor review.
 */
@Entity(
    tableName = "chw_module_completion",
    primaryKeys = ["chw_id", "module_family_id"],
)
data class ChwModuleCompletionEntity(

    @ColumnInfo(name = "chw_id")
    val chwId: String,

    @ColumnInfo(name = "module_family_id")
    val moduleFamilyId: String,

    /** Specific module version of the latest pass (null if never passed). */
    @ColumnInfo(name = "latest_completed_module_id")
    val latestCompletedModuleId: String? = null,

    /** Specific module version of the most recent attempt (pass or fail). */
    @ColumnInfo(name = "latest_attempt_module_id")
    val latestAttemptModuleId: String? = null,

    /** Epoch millis of first pass (or most recent if reinforcement re-passed). */
    @ColumnInfo(name = "completed_at")
    val completedAt: Long? = null,

    @ColumnInfo(name = "latest_attempt_at")
    val latestAttemptAt: Long,

    /** 0.0–1.0 quiz score from the latest attempt. */
    @ColumnInfo(name = "latest_quiz_score")
    val latestQuizScore: Float? = null,

    @ColumnInfo(name = "latest_attempt_passed")
    val latestAttemptPassed: Boolean = false,

    /** Resets to 0 on each pass; increments on each subsequent fail. */
    @ColumnInfo(name = "attempts_since_last_pass")
    val attemptsSinceLastPass: Int = 0,

    /** Epoch millis when the CHW is due to repeat this module for reinforcement. */
    @ColumnInfo(name = "reinforcement_due_at")
    val reinforcementDueAt: Long? = null,
)
