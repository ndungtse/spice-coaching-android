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
            val dao = db.cardEmbeddingDao()
            val encoderChanged = shouldDiscardStaleVectors(
                storedModelId = dao.anyModelId(),
                bundleModelId = bundle.modelId,
                storedRows = dao.count(),
            )
            if (encoderChanged) {
                Log.w(TAG, "Card embeddings: encoder changed to ${bundle.modelId} — clearing stale vectors")
                dao.clear()
            }
            val rows = bundle.cards.mapNotNull { it.toEntity(bundle.embeddingDimension, bundle.modelId, now) }
            val dropped = bundle.cards.size - rows.size
            if (rows.isNotEmpty()) {
                dao.upsertAll(rows)
            }
            Log.i(
                TAG,
                "Card embeddings sync OK: upserted=${rows.size} dropped=$dropped " +
                    "dim=${bundle.embeddingDimension} total=${dao.count()}",
            )
            CardEmbeddingsResult(
                upserted = rows.size,
                dropped = dropped,
                // A delta pull only carries the cards that changed, so after a clear the
                // table holds this bundle alone. Refusing the new watermark sends the next
                // sync back to the epoch, which refills the rest in the new encoder's space.
                newWatermark = bundle.serverTimeUtc.takeUnless { encoderChanged },
                resetWatermark = encoderChanged,
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

/**
 * Whether the stored vectors must be thrown away because they came from a different
 * encoder than the incoming bundle.
 *
 * Two encoders produce unrelated vector spaces, so a cosine across them is not a
 * degraded similarity but a meaningless number — mixing them is worse than having no
 * dense index, which simply degrades chat to BM25-only. Stored rows that name no
 * model are of unknown provenance and go the same way once a bundle does name one.
 * A bundle that names nothing (today's backend) proves nothing and condemns nothing.
 */
internal fun shouldDiscardStaleVectors(
    storedModelId: String?,
    bundleModelId: String?,
    storedRows: Int = 1,
): Boolean {
    val incoming = bundleModelId?.takeIf { it.isNotBlank() } ?: return false
    if (storedRows == 0) return false
    return storedModelId != incoming
}
