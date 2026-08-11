package com.medtroniclabs.microcoaching.sync

import android.util.Log
import androidx.room.withTransaction
import com.medtroniclabs.microcoaching.BuildConfig
import com.medtroniclabs.microcoaching.data.db.MicroCoachingDatabase
import com.medtroniclabs.microcoaching.data.db.entity.AssignedModuleEntity
import com.medtroniclabs.microcoaching.data.db.entity.ChatFaqEntity
import com.medtroniclabs.microcoaching.data.db.entity.CoachingEventEntity
import com.medtroniclabs.microcoaching.data.db.entity.DigitalProficiencyEventEntity
import com.medtroniclabs.microcoaching.data.db.entity.LlmTraceEntity
import com.medtroniclabs.microcoaching.data.db.entity.decodeSourceDocumentRefs
import com.medtroniclabs.microcoaching.data.mapper.parseIsoMillis
import com.medtroniclabs.microcoaching.data.mapper.toConfigEntities
import com.medtroniclabs.microcoaching.data.mapper.toEntity
import com.medtroniclabs.microcoaching.data.mapper.toPayload
import com.medtroniclabs.microcoaching.domain.telemetry.sha256Short
import com.medtroniclabs.microcoaching.data.db.entity.MorningCardCacheEntity
import com.medtroniclabs.microcoaching.network.CoachingApiService
import com.medtroniclabs.microcoaching.data.db.entity.SourceDocumentThumbnailEntity
import com.medtroniclabs.microcoaching.data.localized.toJsonString
import com.medtroniclabs.microcoaching.network.SyncDefaults
import com.medtroniclabs.microcoaching.network.TelemetryBatch
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import java.io.IOException
import java.util.UUID

// Taxonomy sync (gaps / triggers / config / chat-faqs) — extension functions on SyncApi.
private const val TAG = "SyncApi"

/**
 * Fetch the behavioural-gap taxonomy. When [chwId] is a valid UUID, the
 * response also includes per-CHW gap state and module-completion rows,
 * which we upsert into the local mirror so CoachingCard UI reflects
 * server-known progress on first load.
 */
suspend fun SyncApi.pullGaps(sinceWatermark: String?, chwId: String? = null): GapsResult = safeInbound(
    label = "Gaps",
    failureStage = "inbound_gaps",
    call = {
        // Watermark survives Room wipes (lives in SharedPreferences), so a stale
        // watermark + freshly-wiped Room returns an empty delta and progress stays
        // at 0%. If either local mirror is empty, ignore the watermark and ask the
        // backend for the full snapshot so completions repopulate.
        val gapsEmpty = db.behaviouralGapDao().countActive() == 0
        val completionsEmpty = chwId != null &&
            db.chwModuleCompletionDao().getAllForChw(chwId).isEmpty()
        val effectiveSince = if (gapsEmpty || completionsEmpty) null else sinceWatermark
        if (sinceWatermark != null && effectiveSince == null) {
            val reason = when {
                gapsEmpty && completionsEmpty -> "gaps + completions empty"
                gapsEmpty -> "gaps cache empty"
                else -> "completions cache empty for chw=${chwId?.sha256Short()}"
            }
            Log.i(TAG, "Forcing full /sync/gaps bundle — $reason (ignoring stored watermark $sinceWatermark).")
        }
        apiService.pullGaps(since = effectiveSince)
    },
    onSuccess = { bundle ->
        val now = System.currentTimeMillis()
        val gapRows = bundle.behaviouralGaps.map { it.toEntity(now) }
        if (gapRows.isNotEmpty()) db.behaviouralGapDao().upsertAll(gapRows)

        // Resolve domain by gap_id so we can stamp ChwGapProfile with it.
        val domainByGapId = gapRows.associate { it.gapId to (it.domain ?: "unknown") }
        bundle.chwBehaviouralGapStates.forEach { state ->
            val domain = domainByGapId[state.behaviouralGapId] ?: "unknown"
            db.chwGapProfileDao().upsert(state.toEntity(domain))
        }
        // Quiz-level refresher baseline (backend default mode). Additive to the
        // gap states above; the on-device quiz engine reads this snapshot.
        if (bundle.chwQuizQuestionStates.isNotEmpty()) {
            db.chwQuizQuestionStateDao().upsertAll(bundle.chwQuizQuestionStates.map { it.toEntity() })
        }
        bundle.chwModuleCompletions.forEach { completion ->
            db.chwModuleCompletionDao().upsert(completion.toEntity())
        }
        bundle.chwModulePartialCompletions.forEach { partial ->
            db.chwModulePartialCompletionDao().upsert(partial.toEntity())
        }

        val withRules = gapRows.count { it.detectionRule != null }
        Log.i(
            TAG,
            "Gaps sync OK: gaps=${gapRows.size} (with_rules=$withRules) " +
                "states=${bundle.chwBehaviouralGapStates.size} " +
                "quizStates=${bundle.chwQuizQuestionStates.size} " +
                "completions=${bundle.chwModuleCompletions.size} " +
                "partials=${bundle.chwModulePartialCompletions.size} server_time=${bundle.serverTimeUtc}",
        )
        GapsResult(
            upsertedCount = gapRows.size,
            // Structurally 0: pullGaps only upserts the gap taxonomy + CHW
            // state; it never deletes/prunes gap rows, so there is nothing
            // to count here (unlike modules/triggers). See F10.
            prunedCount = 0,
            partialUpserted = bundle.chwModulePartialCompletions.size,
            newWatermark = bundle.serverTimeUtc,
        )
    },
    onFailure = { error, kind -> GapsResult(error = error, errorKind = kind) },
)

/**
 * Fetch trigger definitions + module-trigger bindings updated since
 * [sinceWatermark]. Backend requires non-null `since`; first sync uses
 * [SyncDefaults.EPOCH_ISO].
 */
suspend fun SyncApi.pullTriggers(sinceWatermark: String?): TriggersResult = safeInbound(
    label = "Triggers",
    failureStage = "inbound_triggers",
    call = {
        val localCount = db.triggerDefinitionDao().countActive()
        val effectiveSince = when {
            localCount == 0 -> SyncDefaults.EPOCH_ISO
            sinceWatermark.isNullOrBlank() -> SyncDefaults.EPOCH_ISO
            else -> sinceWatermark
        }
        if (effectiveSince == SyncDefaults.EPOCH_ISO && sinceWatermark != null) {
            Log.i(TAG, "Triggers cache empty — requesting full bundle (ignoring stored watermark $sinceWatermark).")
        }
        apiService.pullTriggers(since = effectiveSince)
    },
    onSuccess = { bundle ->
        val now = System.currentTimeMillis()
        val activeTriggers = bundle.triggers.filter { it.status != "deprecated" }.map { it.toEntity(now) }
        val deprecatedTriggerIds = bundle.triggers.filter { it.status == "deprecated" }.map { it.id }
        // Backend binds to a specific module_id; resolve it to the module family
        // (modules sync before triggers). Drop bindings whose module isn't cached.
        var unresolvedBindings = 0
        val bindings = bundle.bindings.mapNotNull { payload ->
            val family = db.moduleDao().getById(payload.moduleId)?.moduleFamilyId
            if (family == null) {
                unresolvedBindings++
                null
            } else {
                payload.toEntity(moduleFamilyId = family, lastSynced = now)
            }
        }

        if (activeTriggers.isNotEmpty()) db.triggerDefinitionDao().upsertAll(activeTriggers)
        if (deprecatedTriggerIds.isNotEmpty()) db.triggerDefinitionDao().deleteByIds(deprecatedTriggerIds)
        if (bindings.isNotEmpty()) db.moduleTriggerBindingDao().upsertAll(bindings)
        if (deprecatedTriggerIds.isNotEmpty()) {
            val orphanedBindingIds = deprecatedTriggerIds.flatMap { tid ->
                db.moduleTriggerBindingDao().getByTrigger(tid).map { it.bindingId }
            }
            if (orphanedBindingIds.isNotEmpty()) {
                db.moduleTriggerBindingDao().deleteByIds(orphanedBindingIds)
            }
        }

        Log.i(
            TAG,
            "Triggers sync OK: triggers=${activeTriggers.size} (pruned ${deprecatedTriggerIds.size}) " +
                "bindings=${bindings.size} (dropped $unresolvedBindings unresolved module_id) " +
                "server_time=${bundle.serverTimeUtc}",
        )
        TriggersResult(
            triggerCount = activeTriggers.size,
            bindingCount = bindings.size,
            prunedCount = deprecatedTriggerIds.size,
            newWatermark = bundle.serverTimeUtc,
        )
    },
    onFailure = { error, kind -> TriggersResult(error = error, errorKind = kind) },
)

/**
 * Fetch the config-threshold snapshot. Backend ships a single flat
 * `thresholds` dict — every entry is upserted as a global-scoped
 * [ConfigThresholdEntity] row.
 */
suspend fun SyncApi.pullConfig(): ConfigResult = safeInbound(
    label = "Config",
    failureStage = "inbound_config",
    call = { apiService.pullConfig() },
    onSuccess = { bundle ->
        val now = System.currentTimeMillis()
        val rows = bundle.thresholds.toConfigEntities(now)
        if (rows.isNotEmpty()) db.configThresholdDao().upsertAll(rows)
        Log.i(
            TAG,
            "Config sync OK: upserted=${rows.size} server_time=${bundle.serverTimeUtc}",
        )
        ConfigResult(
            upsertedCount = rows.size,
            newWatermark = bundle.serverTimeUtc,
        )
    },
    onFailure = { error, kind -> ConfigResult(error = error, errorKind = kind) },
)

/**
 * Fetch the ranked chat-FAQ suggestions and upsert them into [chat_faq].
 * Incremental via [sinceWatermark] (EPOCH on first sync / empty cache), same
 * as [pullModules]. The question is stored as a serialized
 * [com.medtroniclabs.microcoaching.data.localized.LocalizedText] blob (`{bn, en?}`);
 * English is backfilled later by on-device translation (see
 * [com.medtroniclabs.microcoaching.ui.chat.ChatFaqRepository]) — not here,
 * since [SyncApi] has no translator. Rows without a Bangla question are
 * skipped (nothing to display or translate).
 */
suspend fun SyncApi.pullChatFaqs(sinceWatermark: String?, tenantId: String? = null): ChatFaqsResult = safeInbound(
    label = "Chat FAQs",
    failureStage = "inbound_chat_faqs",
    call = {
        val cacheEmpty = db.chatFaqDao().count() == 0
        val effectiveSince = when {
            cacheEmpty -> SyncDefaults.EPOCH_ISO
            sinceWatermark.isNullOrBlank() -> SyncDefaults.EPOCH_ISO
            else -> sinceWatermark
        }
        apiService.pullChatFaqs(since = effectiveSince)
    },
    onSuccess = { bundle ->
        val now = System.currentTimeMillis()
        val rows = bundle.faqs.mapNotNull { faq ->
            // Bangla is guaranteed by the backend; skip anything without it.
            if (faq.question.bn.isNullOrBlank()) return@mapNotNull null
            ChatFaqEntity(
                faqId = faq.id,
                questionJson = faq.question.toJsonString(),
                rank = faq.rank,
                occurrenceCount = faq.occurrenceCount,
                lastSeenAt = faq.lastSeenAt,
                lastSynced = now,
            )
        }
        if (rows.isNotEmpty()) db.chatFaqDao().upsertAll(rows)
        Log.i(
            TAG,
            "Chat FAQs sync OK: upserted=${rows.size} (of ${bundle.faqs.size}) " +
                "server_time=${bundle.serverTimeUtc}",
        )
        ChatFaqsResult(upsertedCount = rows.size, newWatermark = bundle.serverTimeUtc)
    },
    onFailure = { error, kind -> ChatFaqsResult(error = error, errorKind = kind) },
)
