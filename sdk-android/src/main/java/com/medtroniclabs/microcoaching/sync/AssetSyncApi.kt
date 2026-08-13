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
import com.medtroniclabs.microcoaching.network.SourceDocumentSyncDownloadItem
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
 * Collapse the catalogue's two halves into the one table that stores them, so a
 * document appearing in both keeps the assigned row.
 *
 * Assignment is the more specific fact and the only one the grid filters on, so
 * losing it would hide a document the CHW was assigned. The reverse never
 * matters: both rows describe the same document and carry the same URLs.
 *
 * Top-level + pure so the precedence rule is unit-testable without a Room or
 * network harness.
 */
internal fun mergeSourceDocumentRows(
    moduleLinked: List<PublishedSourceDocumentEntity>,
    assigned: List<PublishedSourceDocumentEntity>,
): List<PublishedSourceDocumentEntity> =
    (moduleLinked + assigned).associateBy { it.sourceDocumentId }.values.toList()

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
 * Recover watch progress the server holds for this CHW's assigned videos.
 *
 * Progress is written by telemetry, so the device is normally ahead of the server
 * and this pull changes nothing. It earns its place in the one case the device
 * cannot recover alone: a reinstall, a cleared data directory, or a different
 * handset, where the local position is gone but the server still has it.
 *
 * Writes go through the DAO's monotonic update rather than an entity upsert, so a
 * server value that lags unsent local playback can never wind a video backwards.
 * Unlike the catalogue pulls this one honours a real watermark — the response
 * carries no presigned URLs, so a row skipped as unchanged loses nothing.
 */
suspend fun SyncApi.pullVideoProgress(sinceWatermark: String?): VideoProgressResult {
    val userId = chwId
    if (userId.isBlank()) {
        Log.d(TAG, "Video progress: no CHW signed in — skipping.")
        return VideoProgressResult(skipped = true)
    }
    return safeInbound(
        label = "Video progress",
        failureStage = "inbound_video_progress",
        call = {
            apiService.pullVideoProgress(
                since = sinceWatermark?.takeIf { it.isNotBlank() } ?: SyncDefaults.EPOCH_ISO,
            )
        },
        onSuccess = { bundle ->
            bundle.videos.forEach { item ->
                db.assignedVideoDao().updateProgress(
                    videoId = item.sourceDocumentId,
                    chwId = userId,
                    positionMs = item.lastPositionMs,
                    percent = item.percentWatched,
                    completed = item.completed,
                    watchedAt = item.lastWatchedAt,
                )
            }
            Log.i(TAG, "Video progress sync OK: rows=${bundle.videos.size}")
            VideoProgressResult(count = bundle.videos.size, newWatermark = bundle.serverTimeUtc)
        },
        onFailure = { error, kind -> VideoProgressResult(error = error, errorKind = kind) },
    )
}

/**
 * Fetch the source-document catalogue and fan it out to the two tables that
 * consume it.
 *
 * Both halves of the response go into `published_source_document`, because both
 * are needed: the assigned rows are what the Knowledge grid lists, and the
 * module-linked rows are what a chat citation chip resolves a URL against. The
 * audio/video subset of the assigned rows additionally reconciles into
 * `assigned_video`, which is the only table that carries watch progress.
 *
 * The request always asks for the full catalogue rather than passing a stored
 * watermark. Presigned URLs are only attached to the rows a response returns, so
 * a narrower `since` would leave every unchanged document holding a URL that
 * eventually expires with no way to refresh it — and the document table is
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

        // ── Document catalogue ────────────────────────────────────────────────
        // Both halves land in one table: the assigned rows are what the Knowledge
        // grid lists, and the module-linked rows are what a chat citation chip
        // resolves a URL against. Assigned rows are mapped last so that a document
        // present in both keeps its `assigned_at` — being assigned is the more
        // specific fact, and it is what puts the row in the grid.
        //
        // De-duping by id also protects the primary key, which would otherwise
        // drop later duplicates non-deterministically.
        fun documentRow(idx: Int, item: SourceDocumentSyncDownloadItem) = PublishedSourceDocumentEntity(
            sourceDocumentId = item.sourceDocumentId,
            sourceType = item.sourceType,
            title = item.title,
            description = item.description,
            originalFilename = item.originalFilename,
            storagePath = item.storagePath,
            thumbnailStoragePath = item.thumbnailStoragePath,
            assignedAt = item.assignedAt,
            presignedUrl = item.presignedUrl,
            presignedExpiresAt = item.presignedExpiresSeconds?.let { absoluteExpiry(nowSec, it) },
            thumbnailUrl = item.thumbnailPresignedUrl,
            thumbnailExpiresAt = item.thumbnailPresignedExpiresSeconds?.let { absoluteExpiry(nowSec, it) },
            rank = idx,
            lastSynced = now,
        )

        val moduleLinkedRows = body.sourceDocuments.mapIndexed(::documentRow)
        val assignedRows = body.assignedDocuments.mapIndexed(::documentRow)
        val documentRows = mergeSourceDocumentRows(moduleLinkedRows, assignedRows)
        db.publishedSourceDocumentDao().replaceAll(documentRows)

        // ── Training sub-tab ──────────────────────────────────────────────────
        // Video-only slice of the same assigned rows (audio stays in the Knowledge
        // grid). They are stored twice on purpose: `assigned_video` carries watch
        // progress and the resume anchor, which the document table has no notion of.
        val userId = chwId
        val videoResult = if (userId.isBlank()) {
            Log.d(TAG, "Assigned videos: no CHW signed in — skipping reconcile.")
            AssignedVideosResult(skipped = true)
        } else {
            val videoRows = body.assignedDocuments
                .filter { it.isVideo }
                .mapIndexed { idx, item ->
                    AssignedVideoEntity(
                        videoId = item.sourceDocumentId,
                        chwId = userId,
                        title = item.title,
                        description = item.description,
                        durationMs = item.durationMs ?: 0,
                        assignedAt = item.assignedAt,
                        storagePath = item.storagePath,
                        presignedUrl = item.presignedUrl,
                        presignedExpiresAt = item.presignedExpiresSeconds?.let { absoluteExpiry(nowSec, it) },
                        thumbnailUrl = item.thumbnailPresignedUrl,
                        thumbnailExpiresAt = item.thumbnailPresignedExpiresSeconds
                            ?.let { absoluteExpiry(nowSec, it) },
                        // The catalogue carries no watch progress. Leaving those at
                        // their defaults is what lets the DAO's monotonic merge keep
                        // whatever the device already knows; progress is recovered
                        // separately by [pullVideoProgress].
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
            "Source-documents sync OK: documents=${documentRows.size} " +
                "(moduleLinked=${moduleLinkedRows.size} assigned=${assignedRows.size}) " +
                "videos=${videoResult.count}",
        )
        SourceDocumentsResult(
            published = PublishedSourceDocumentsResult(count = documentRows.size),
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

