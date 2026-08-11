package com.medtroniclabs.microcoaching.sync

import android.util.Log
import com.medtroniclabs.microcoaching.BuildConfig
import com.medtroniclabs.microcoaching.data.db.MicroCoachingDatabase
import com.medtroniclabs.microcoaching.data.db.entity.AssignedModuleEntity
import com.medtroniclabs.microcoaching.data.db.entity.ChatFaqEntity
import com.medtroniclabs.microcoaching.data.db.entity.CoachingEventEntity
import com.medtroniclabs.microcoaching.data.db.entity.DigitalProficiencyEventEntity
import com.medtroniclabs.microcoaching.data.db.entity.LlmTraceEntity
import com.medtroniclabs.microcoaching.data.mapper.parseIsoMillis
import com.medtroniclabs.microcoaching.data.mapper.toConfigEntities
import com.medtroniclabs.microcoaching.data.mapper.toEntity
import com.medtroniclabs.microcoaching.data.mapper.toPayload
import com.medtroniclabs.microcoaching.data.db.entity.MorningCardCacheEntity
import com.medtroniclabs.microcoaching.network.CoachingApiService
import com.medtroniclabs.microcoaching.data.db.entity.AssignedVideoEntity
import com.medtroniclabs.microcoaching.data.db.entity.PublishedSourceDocumentEntity
import com.medtroniclabs.microcoaching.data.localized.toJsonString
import com.medtroniclabs.microcoaching.network.SyncDefaults
import com.medtroniclabs.microcoaching.network.TelemetryBatch
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import java.io.IOException
import java.util.UUID

// Asset sync (morning cards / source documents) — extension functions on SyncApi.
private const val TAG = "SyncApi"

/**
 * Fetch the backend-prioritised morning-module list for the signed-in CHW and
 * atomically replace the backend rows of the morning-card cache. On network
 * failure the previous cache is left intact so the device can still surface a
 * ranked list.
 */
suspend fun SyncApi.pullMorningCards(): MorningCardsResult = safeInbound(
    label = "Morning cards",
    failureStage = null,
    call = { apiService.getMorningCards() },
    onSuccess = { body ->
        val now = System.currentTimeMillis()
        val entities = body.items.mapIndexed { idx, item ->
            MorningCardCacheEntity(
                moduleId = item.moduleId,
                moduleFamilyId = item.moduleFamilyId,
                source = item.source,
                behaviouralGapId = item.behaviouralGapId,
                quizId = item.quizId,
                rank = idx,
                fetchedAt = now,
            )
        }
        // Replace only the backend rows — never the on-device gap cards
        // (e.g. referral compliance), which coexist via `on_device = 1`.
        db.morningCardCacheDao().replaceBackend(entities)
        Log.i(
            TAG,
            "Morning cards sync OK: items=${entities.size} " +
                "quiz=${entities.count { it.source == "quiz" }} gap=${entities.count { it.source == "gap" }}",
        )
        MorningCardsResult(count = entities.size)
    },
    onFailure = { error, kind -> MorningCardsResult(error = error, errorKind = kind) },
)

/**
 * Fetch the source-document catalogue and fan it out to the two tables that
 * consume it: `published_source_document` (the Knowledge grid) from
 * `source_documents`, and `assigned_video` (the Training sub-tab) from the
 * audio/video rows of `assigned_documents`.
 *
 * The request always asks for the full catalogue rather than passing a stored
 * watermark. Presigned URLs are only attached to the rows a response returns, so
 * a narrower `since` would leave every unchanged document holding a URL that
 * eventually expires with no way to refresh it — and the Knowledge table is
 * replaced wholesale, which a partial response would truncate.
 *
 * Both writes are all-or-nothing per table: on any failure the previous contents
 * stay intact, and the assigned reconcile no-ops on an empty snapshot so a
 * transient blank response cannot wipe the Training list.
 */
suspend fun SyncApi.pullSourceDocuments(): SourceDocumentsResult {
    return try {
        val nowSec = System.currentTimeMillis() / 1000L
        val now = System.currentTimeMillis()

        val response = apiService.getSourceDocuments(since = SyncDefaults.EPOCH_ISO)
        if (!response.isSuccessful) {
            val errorMsg = "HTTP ${response.code()}"
            recordInboundFailure("inbound_source_documents", errorMsg)
            Log.w(TAG, "Source-documents sync server error: $errorMsg")
            return SourceDocumentsResult.failed(errorMsg, httpKindFor(response.code()))
        }
        val body = response.body()
            ?: return SourceDocumentsResult.failed("empty body", SyncErrorKind.UNEXPECTED)

        // ── Knowledge grid ────────────────────────────────────────────────────
        // De-dupe by id (the catalogue can repeat a source id across re-ingested
        // files) keeping first-seen order; the table PK would otherwise drop
        // later duplicates non-deterministically.
        val publishedRows = body.sourceDocuments.mapIndexed { idx, item ->
            PublishedSourceDocumentEntity(
                sourceDocumentId = item.sourceDocumentId,
                title = item.title,
                originalFilename = item.originalFilename,
                presignedUrl = item.presignedUrl,
                presignedExpiresAt = item.presignedExpiresSeconds?.let { absoluteExpiry(nowSec, it) },
                thumbnailUrl = item.thumbnailPresignedUrl,
                thumbnailExpiresAt = item.thumbnailPresignedExpiresSeconds?.let { absoluteExpiry(nowSec, it) },
                rank = idx,
                lastSynced = now,
            )
        }.distinctBy { it.sourceDocumentId }
        db.publishedSourceDocumentDao().replaceAll(publishedRows)

        // ── Training sub-tab ──────────────────────────────────────────────────
        // Assigned documents cover every file type; only the playable ones back
        // the video list. The remainder are parsed but not yet surfaced.
        val userId = chwId
        val videoResult = if (userId.isBlank()) {
            Log.d(TAG, "Assigned videos: no CHW signed in — skipping reconcile.")
            AssignedVideosResult(skipped = true)
        } else {
            val videoRows = body.assignedDocuments
                .filter { it.isPlayableMedia }
                .mapIndexed { idx, item ->
                    AssignedVideoEntity(
                        videoId = item.sourceDocumentId,
                        chwId = userId,
                        title = item.title,
                        assignedAt = item.assignedAt,
                        presignedUrl = item.presignedUrl,
                        presignedExpiresAt = item.presignedExpiresSeconds?.let { absoluteExpiry(nowSec, it) },
                        thumbnailUrl = item.thumbnailPresignedUrl,
                        thumbnailExpiresAt = item.thumbnailPresignedExpiresSeconds
                            ?.let { absoluteExpiry(nowSec, it) },
                        // The catalogue carries no watch progress or duration.
                        // Leaving these at their defaults is what lets the DAO's
                        // monotonic merge keep whatever the device already knows.
                        rank = idx,
                        lastSynced = now,
                    )
                }
                .distinctBy { it.videoId }
            db.assignedVideoDao().reconcileForUser(userId, videoRows)
            AssignedVideosResult(count = videoRows.size)
        }

        Log.i(
            TAG,
            "Source-documents sync OK: published=${publishedRows.size} " +
                "assigned=${body.assignedDocuments.size} videos=${videoResult.count}",
        )
        SourceDocumentsResult(
            published = PublishedSourceDocumentsResult(count = publishedRows.size),
            assignedVideos = videoResult,
        )
    } catch (e: IOException) {
        recordInboundFailure("inbound_source_documents", e.javaClass.simpleName, offline = true)
        Log.w(TAG, "Source-documents sync network error: ${e.message}")
        SourceDocumentsResult.failed(e.message ?: "network error", SyncErrorKind.NETWORK)
    } catch (e: Exception) {
        recordInboundFailure("inbound_source_documents", e.javaClass.simpleName)
        Log.w(TAG, "Source-documents sync unexpected error: ${e.message}", e)
        SourceDocumentsResult.failed(e.message ?: "unexpected error", SyncErrorKind.UNEXPECTED)
    }
}

