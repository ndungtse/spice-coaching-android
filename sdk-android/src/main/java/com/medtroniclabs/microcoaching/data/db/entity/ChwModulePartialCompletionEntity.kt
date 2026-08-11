package com.medtroniclabs.microcoaching.data.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity

/**
 * Per-CHW per-module partial-completion state.
 *
 * Mirrors the server-side `chw_module_partial_completions` table. The backend
 * derives [incompleteQuizIdsJson] (a JSON-encoded `List<String>`) by aggregating
 * per-question `module_quiz_attempted` events: any question the CHW has either
 * never attempted or whose latest answer was wrong.
 *
 * Read site: feeds the cross-device "to-reinforce" set merged with the local
 * `coaching_event` history in `ToReinforceResolver`. Survives Room wipes /
 * device switches because the server is authoritative.
 *
 * A row exists only after the CHW has attempted the module's quiz at least
 * once (backend semantics — see plan doc handling β).
 */
@Entity(
    tableName = "chw_module_partial_completion",
    primaryKeys = ["chw_id", "module_family_id"],
)
data class ChwModulePartialCompletionEntity(

    @ColumnInfo(name = "chw_id")
    val chwId: String,

    @ColumnInfo(name = "module_family_id")
    val moduleFamilyId: String,

    /** Specific module version backing the latest partial state. */
    @ColumnInfo(name = "module_id")
    val moduleId: String? = null,

    /** JSON-encoded `List<String>` of question IDs still to reinforce. */
    @ColumnInfo(name = "incomplete_quiz_ids_json")
    val incompleteQuizIdsJson: String,

    @ColumnInfo(name = "tenant_id")
    val tenantId: String? = null,

    /** Epoch millis when this row was last upserted locally from sync. */
    @ColumnInfo(name = "updated_at")
    val updatedAt: Long,
)
