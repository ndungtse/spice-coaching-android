package com.medtroniclabs.microcoaching.data.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Local cache of v3 module ↔ trigger bindings.
 *
 * One row associates a [ModuleEntity] (by `moduleFamilyId`) with a
 * [TriggerDefinitionEntity]. Multiple modules may bind to the same trigger;
 * the device picks the highest `priorityWeight` among bindings whose trigger
 * predicate matches.
 *
 * Source: `GET /sync/triggers` (bundled alongside trigger definitions).
 */
@Entity(
    tableName = "module_trigger_binding",
    indices = [
        Index(value = ["module_family_id"]),
        Index(value = ["trigger_definition_id"]),
    ],
)
data class ModuleTriggerBindingEntity(

    @PrimaryKey
    @ColumnInfo(name = "binding_id")
    val bindingId: String,

    @ColumnInfo(name = "module_family_id")
    val moduleFamilyId: String,

    @ColumnInfo(name = "trigger_definition_id")
    val triggerDefinitionId: String,

    /** "primary" | "secondary". */
    @ColumnInfo(name = "relationship")
    val relationship: String,

    /** Higher wins among bindings that match the same event. */
    @ColumnInfo(name = "priority_weight")
    val priorityWeight: Int,

    @ColumnInfo(name = "notes")
    val notes: String? = null,

    @ColumnInfo(name = "last_synced")
    val lastSynced: Long = System.currentTimeMillis(),
)
