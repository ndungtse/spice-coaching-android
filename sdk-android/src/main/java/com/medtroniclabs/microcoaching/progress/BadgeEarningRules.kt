package com.medtroniclabs.microcoaching.progress

import com.medtroniclabs.microcoaching.data.db.entity.BadgeEntity
import com.medtroniclabs.microcoaching.util.LenientJson
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer

/**
 * Decide which badges this CHW has earned from what they have completed, so the
 * tick appears the moment the last module is done rather than after the next sync.
 *
 * The server reaches the same conclusion eventually; this only closes the gap.
 */

/** The module ids a badge is awarded for, or empty when the column is absent or malformed. */
internal fun BadgeEntity.moduleIdList(): List<String> {
    val raw = moduleIds?.takeIf { it.isNotBlank() } ?: return emptyList()
    return runCatching {
        LenientJson.decodeFromString(ListSerializer(String.serializer()), raw)
    }.getOrDefault(emptyList())
}

/** Whether one of a badge's module ids counts as done, and if not, whether we could tell. */
internal enum class ModuleRequirement { SATISFIED, OUTSTANDING, UNKNOWN }

/**
 * Whether the CHW has completed the module a badge refers to.
 *
 * A badge names a module **version** (`badge_module.module_id` is an FK to
 * `module.id`), while completions are recorded per **family**. Two ways to bridge
 * that, and both are needed:
 *
 *  - the version was completed outright, so it appears among [completedModuleIds];
 *  - or [familyOf] translates it to a family that has a completion. This covers the
 *    common case where the badge names an older version than the one the CHW
 *    actually did — module content gets republished, and completion is deliberately
 *    tracked per family so it survives that.
 *
 * [ModuleRequirement.UNKNOWN] is the honest third answer: the version is neither
 * completed nor resolvable to a family, so this device simply cannot say. That
 * happens when a module was never assigned and its version has aged out of
 * `module_cache`, which only ever holds the newest version of each family.
 */
internal fun moduleRequirementFor(
    moduleId: String,
    completedModuleIds: Set<String>,
    completedFamilyIds: Set<String>,
    familyOf: (moduleId: String) -> String?,
): ModuleRequirement {
    if (moduleId in completedModuleIds) return ModuleRequirement.SATISFIED
    val family = familyOf(moduleId) ?: return ModuleRequirement.UNKNOWN
    return if (family in completedFamilyIds) ModuleRequirement.SATISFIED else ModuleRequirement.OUTSTANDING
}

/**
 * Badges that should now count as earned locally.
 *
 * A badge qualifies when nothing it requires is outstanding and at least one
 * requirement is positively satisfied. Requirements this device can't judge
 * ([ModuleRequirement.UNKNOWN]) are passed over rather than treated as
 * outstanding — blocking on one would leave the CHW permanently short of a tick
 * they have in fact earned — but a badge whose requirements are *all* unknown is
 * not awarded, since there is no evidence in either direction.
 *
 * Already-earned badges are excluded, so the result is only what is newly earned.
 *
 * Pure, so the id bridging is testable without Room.
 */
internal fun badgesNewlyEarned(
    badges: List<BadgeEntity>,
    completedModuleIds: Set<String>,
    completedFamilyIds: Set<String>,
    familyOf: (moduleId: String) -> String?,
): List<BadgeEntity> = badges.filter { badge ->
    if (badge.earnedAt != null || badge.locallyEarnedAt != null) return@filter false
    val verdicts = badge.moduleIdList()
        .map { moduleRequirementFor(it, completedModuleIds, completedFamilyIds, familyOf) }
    verdicts.any { it == ModuleRequirement.SATISFIED } &&
        verdicts.none { it == ModuleRequirement.OUTSTANDING }
}
