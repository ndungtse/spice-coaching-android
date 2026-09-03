package com.medtroniclabs.microcoaching.sync

import android.util.Log
import com.medtroniclabs.microcoaching.data.db.entity.CardEmbeddingEntity
import com.medtroniclabs.microcoaching.data.db.entity.l2Normalized
import com.medtroniclabs.microcoaching.data.db.entity.toLeBytes
import com.medtroniclabs.microcoaching.network.CardEmbeddingDto
import com.medtroniclabs.microcoaching.network.SyncDefaults

// Card-embeddings sync (/sync/card-embeddings) — extension functions on SyncApi.
private const val TAG = "SyncApi"

/**
 * Fetch card embedding vectors updated after [sinceWatermark] and upsert them into
 * `card_embedding`. Rows are keyed by the backend's per-card UUID (the `id` the
 * modules sync now carries per card), so a delta pull only touches changed cards.
 *
 * Vectors are L2-normalized before storage — the dense index treats cosine as a
 * dot product — and rows whose vector length disagrees with the bundle's
 * `embedding_dimension` are dropped rather than poisoning search.
 */
suspend fun SyncApi.pullCardEmbeddings(sinceWatermark: String?): CardEmbeddingsResult =
    safeInbound(
        label = "Card embeddings",
        call = {
            apiService.pullCardEmbeddings(
                since = sinceWatermark?.takeIf { it.isNotBlank() } ?: SyncDefaults.EPOCH_ISO,
            )
        },
        onSuccess = { bundle ->
            val now = System.currentTimeMillis()
            val rows = bundle.cards.mapNotNull { it.toEntity(bundle.embeddingDimension, bundle.modelId, now) }
            val dropped = bundle.cards.size - rows.size
            if (rows.isNotEmpty()) {
                db.cardEmbeddingDao().upsertAll(rows)
            }
            Log.i(
                TAG,
                "Card embeddings sync OK: upserted=${rows.size} dropped=$dropped " +
                    "dim=${bundle.embeddingDimension} total=${db.cardEmbeddingDao().count()}",
            )
            CardEmbeddingsResult(
                upserted = rows.size,
                dropped = dropped,
                newWatermark = bundle.serverTimeUtc,
            )
        },
        onFailure = { error, kind -> CardEmbeddingsResult(error = error, errorKind = kind) },
    )

/**
 * Map one sync row to its storage form, or null when it cannot be used: an empty
 * vector, a length that disagrees with the bundle's dimension, or no family id
 * (the fallback join key and the retirement-cleanup key).
 */
internal fun CardEmbeddingDto.toEntity(
    expectedDim: Int,
    modelId: String?,
    nowMs: Long,
): CardEmbeddingEntity? {
    if (embedding.isEmpty() || embedding.size != expectedDim) return null
    val familyId = cardFamilyId?.takeIf { it.isNotBlank() } ?: return null
    return CardEmbeddingEntity(
        cardId = cardId,
        moduleFamilyId = familyId,
        dim = embedding.size,
        modelId = modelId,
        vec = embedding.toFloatArray().l2Normalized().toLeBytes(),
        syncedAtMs = nowMs,
    )
}
