package com.medtroniclabs.microcoaching.data.mapper

import com.medtroniclabs.microcoaching.data.db.entity.BehaviouralGapEntity
import com.medtroniclabs.microcoaching.data.db.entity.ChwGapProfileEntity
import com.medtroniclabs.microcoaching.data.db.entity.ChwModuleCompletionEntity
import com.medtroniclabs.microcoaching.data.db.entity.ChwModulePartialCompletionEntity
import com.medtroniclabs.microcoaching.data.db.entity.ConfigThresholdEntity
import com.medtroniclabs.microcoaching.data.db.entity.ModuleEntity
import com.medtroniclabs.microcoaching.data.db.entity.ModuleTriggerBindingEntity
import com.medtroniclabs.microcoaching.data.db.entity.TriggerDefinitionEntity
import com.medtroniclabs.microcoaching.network.BehaviouralGapSyncPayload
import com.medtroniclabs.microcoaching.network.ChwBehaviouralGapStateSyncPayload
import com.medtroniclabs.microcoaching.network.ChwModulePartialCompletionSyncPayload
import com.medtroniclabs.microcoaching.network.ChwModuleCompletionSyncPayload
import com.medtroniclabs.microcoaching.network.ModuleSyncPayload
import com.medtroniclabs.microcoaching.network.ModuleTriggerBindingSyncPayload
import com.medtroniclabs.microcoaching.network.SourceDocumentRef
import com.medtroniclabs.microcoaching.network.TriggerDefinitionSyncPayload
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import java.time.Instant

/**
 * Extension functions mapping v3 backend `*SyncPayload` types to their Room
 * counterparts. Used by [com.medtroniclabs.microcoaching.sync.SyncApi].
 *
 * Filename remains `ScenarioBundleMapper.kt` for git-history continuity; the
 * scenario layer itself was removed in 0.3.0.
 */

private val bundleJson = Json { ignoreUnknownKeys = true; isLenient = true }

private val jsonObjectListSerializer = ListSerializer(JsonObject.serializer())
private val jsonElementListSerializer = ListSerializer(JsonElement.serializer())

/**
 * Parse a backend ISO 8601 timestamp into epoch millis. Returns null when
 * input is null or unparseable.
 */
fun parseIsoMillis(iso: String?): Long? =
    iso?.let { runCatching { Instant.parse(it).toEpochMilli() }.getOrNull() }

// ── Module sync ───────────────────────────────────────────────────────────────

fun ModuleSyncPayload.toEntity(lastSynced: Long): ModuleEntity = ModuleEntity(
    moduleId = id,
    moduleFamilyId = moduleFamilyId,
    version = version,
    titleBn = titleBn,
    titleEn = titleEn,
    descriptionBn = descriptionBn,
    descriptionEn = descriptionEn,
    domain = domain,
    subDomain = subDomain,
    moduleType = moduleType,
    tenantId = tenantId,
    estimatedMinutes = estimatedMinutes,
    difficultyLevel = difficultyLevel,
    passThresholdOverride = passThresholdOverride,
    clinicallyReviewed = clinicallyReviewed,
    publishedAtIso = publishedAt,
    updatedAtIso = updatedAt,
    cardsJson = bundleJson.encodeToString(jsonObjectListSerializer, cards),
    quizJson = bundleJson.encodeToString(quizListSerializer, quiz),
    // Prefer the rich `source_documents`; if an older backend still sends only
    // `source_document_ids`, synthesise id-only refs so chips still render.
    sourceDocumentsJson = bundleJson.encodeToString(
        sourceDocumentRefListSerializer,
        sourceDocuments.ifEmpty { sourceDocumentIds.map { SourceDocumentRef(id = it) } },
    ),
    // Keep the deprecated id-only column populated (derived) for back-compat.
    sourceDocumentIdsJson = bundleJson.encodeToString(
        stringListSerializer,
        sourceDocuments.map { it.id }.ifEmpty { sourceDocumentIds },
    ),
    hasThumbnail = hasThumbnail,
    // thumbnailUrl / thumbnailExpiresAtEpochSec are intentionally left at their
    // defaults (null): a module REPLACE resets them, and thumbnail sync
    // (SyncApi.pullModuleThumbnails) repopulates them in the same inbound pass.
    lastSynced = lastSynced,
)

/**
 * Quiz is stored as raw JSON (one element per question). Re-serialising
 * preserves the exact field shape the renderer expects.
 */
private val quizListSerializer =
    ListSerializer(com.medtroniclabs.microcoaching.network.ModuleQuizQuestionPayload.serializer())

/** Serializer for the rich `source_documents` list stored in module_cache. */
private val sourceDocumentRefListSerializer =
    ListSerializer(com.medtroniclabs.microcoaching.network.SourceDocumentRef.serializer())

// ── Gaps sync ─────────────────────────────────────────────────────────────────

fun BehaviouralGapSyncPayload.toEntity(lastSynced: Long): BehaviouralGapEntity =
    BehaviouralGapEntity(
        gapId = id,
        gapCode = gapCode,
        description = description,
        domain = domain,
        severityDefault = severityDefault,
        status = "active",
        detectionRule = detectionRule.takeIf { it.isNotEmpty() }?.toString(),
        lastSynced = lastSynced,
    )

fun ChwBehaviouralGapStateSyncPayload.toEntity(
    clinicalDomain: String,
): ChwGapProfileEntity = ChwGapProfileEntity(
    chwId = chwId,
    behaviouralGapId = behaviouralGapId,
    clinicalDomain = clinicalDomain,
    wrongCount = occurrenceCount,
    consecutiveCorrect = 0,
    totalAttempts = occurrenceCount + failedAttemptsCount,
    quizScorePct = null,
    cardsShown = 0,
    cardsUsed = 0,
    cardsSkipped = 0,
    counsellingUseRate = null,
    gapActive = status == "active",
    gapType = null,
    lastSeen = parseIsoMillis(lastObservedAt),
    resolvedAt = if (status == "resolved") parseIsoMillis(updatedAt) else null,
    lastSynced = parseIsoMillis(updatedAt),
    lastReinforcedAt = parseIsoMillis(lastReinforcedAt),
    failedAttemptsCount = failedAttemptsCount,
    lastFailedAttemptAt = parseIsoMillis(lastFailedAttemptAt),
    escalatedToSupervisor = escalatedToSupervisor,
)

fun ChwModuleCompletionSyncPayload.toEntity(): ChwModuleCompletionEntity {
    val nowMillis = System.currentTimeMillis()
    return ChwModuleCompletionEntity(
        chwId = chwId,
        moduleFamilyId = moduleFamilyId,
        latestCompletedModuleId = latestCompletedModuleId,
        latestAttemptModuleId = latestAttemptModuleId,
        completedAt = parseIsoMillis(completedAt),
        latestAttemptAt = parseIsoMillis(latestAttemptAt) ?: nowMillis,
        latestQuizScore = latestQuizScore,
        latestAttemptPassed = latestAttemptPassed,
        attemptsSinceLastPass = attemptsSinceLastPass,
        reinforcementDueAt = parseIsoMillis(reinforcementDueAt),
    )
}

private val stringListSerializer = ListSerializer(String.serializer())

fun ChwModulePartialCompletionSyncPayload.toEntity(): ChwModulePartialCompletionEntity =
    ChwModulePartialCompletionEntity(
        chwId = chwId,
        moduleFamilyId = moduleFamilyId,
        moduleId = moduleId,
        incompleteQuizIdsJson = bundleJson.encodeToString(stringListSerializer, incompleteQuizIds),
        tenantId = tenantId,
        updatedAt = System.currentTimeMillis(),
    )

/** Decode the persisted `incomplete_quiz_ids_json` back into a `List<String>`. */
fun ChwModulePartialCompletionEntity.decodeIncompleteQuizIds(): List<String> =
    runCatching {
        bundleJson.decodeFromString(stringListSerializer, incompleteQuizIdsJson)
    }.getOrDefault(emptyList())

// ── Triggers sync ─────────────────────────────────────────────────────────────

fun TriggerDefinitionSyncPayload.toEntity(lastSynced: Long): TriggerDefinitionEntity =
    TriggerDefinitionEntity(
        triggerId = id,
        triggerKind = triggerKind,
        triggerCode = triggerCode,
        description = description,
        predicateJson = bundleJson.encodeToString(JsonObject.serializer(), predicate),
        status = status,
        lastSynced = lastSynced,
    )

fun ModuleTriggerBindingSyncPayload.toEntity(lastSynced: Long): ModuleTriggerBindingEntity =
    ModuleTriggerBindingEntity(
        bindingId = id,
        moduleFamilyId = moduleFamilyId,
        triggerDefinitionId = triggerDefinitionId,
        relationship = relationship,
        priorityWeight = priorityWeight,
        notes = notes,
        lastSynced = lastSynced,
    )

// ── Config sync ───────────────────────────────────────────────────────────────

/**
 * Backend ships `{thresholds: {key: value, ...}}`. Each entry becomes a global
 * (empty-string scope) row in `config_threshold`. Per-module overrides are
 * NOT exposed on this endpoint — for those, the SDK reads `ModuleEntity.passThresholdOverride`.
 */
fun Map<String, JsonElement>.toConfigEntities(lastSynced: Long): List<ConfigThresholdEntity> =
    entries.map { (key, value) ->
        ConfigThresholdEntity(
            moduleFamilyId = ConfigThresholdEntity.GLOBAL_SCOPE,
            key = key,
            value = value.toString().trim('"'),
            lastSynced = lastSynced,
        )
    }
