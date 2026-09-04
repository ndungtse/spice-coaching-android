package com.medtroniclabs.microcoaching.ai.inference

import android.content.Context
import android.util.Log
import com.medtroniclabs.microcoaching.ai.embedding.EncoderModel
import com.medtroniclabs.microcoaching.ai.embedding.LiteRtQueryEmbedder
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.floatOrNull
import java.io.File

/**
 * Embeds one chat question into the same vector space as the synced card embeddings
 * (`card_embedding` table), for the dense half of hybrid retrieval.
 *
 * Implementations must return an **L2-normalized** vector matching the synced
 * dimension, produced with the encoder's QUERY prompt — the backend embeds cards
 * with the document prompt, and mixing the two collapses similarity. `null` means
 * "cannot embed right now" (model absent, text unsupported); callers then run
 * BM25-only for the turn.
 */
interface QueryEmbedder {
    /** False when the implementation cannot produce vectors at all on this device. */
    val isAvailable: Boolean

    suspend fun embed(text: String): FloatArray?
}

/**
 * Fixture-backed embedder: serves query vectors precomputed off-device from
 * `files/query_embeddings_fixture.json` (`{"queries":[{"text":…,"vec":[…]}]}`),
 * matched on trimmed text.
 *
 * A validation tool, not a production encoder: it lets the full hybrid pipeline —
 * vector sync, index join, fusion, gate, tracing — run end-to-end on a device
 * before the on-device encoder is integrated, using vectors from the same
 * embedding model the backend used. Questions outside the fixture embed to null,
 * which degrades that turn to BM25-only. The file is absent in production, so
 * [isAvailable] is false and the resolver moves on.
 */
internal class FixtureFileQueryEmbedder(context: Context) : QueryEmbedder {

    private val file = File(context.filesDir, FIXTURE_FILE_NAME)

    private val vectors: Map<String, FloatArray> by lazy {
        runCatching {
            val root = Json.parseToJsonElement(file.readText()) as JsonObject
            (root["queries"] as JsonArray).associate { el ->
                val o = el as JsonObject
                val text = (o["text"] as JsonPrimitive).contentOrNull.orEmpty().trim()
                val vec = (o["vec"] as JsonArray)
                    .map { (it as JsonPrimitive).floatOrNull ?: 0f }
                    .toFloatArray()
                text to vec
            }
        }.onFailure {
            Log.w(TAG, "fixture unreadable: ${it.message}")
        }.getOrDefault(emptyMap())
    }

    override val isAvailable: Boolean get() = file.exists()

    override suspend fun embed(text: String): FloatArray? = vectors[text.trim()]

    companion object {
        private const val TAG = "QueryEmbedder"
        const val FIXTURE_FILE_NAME = "query_embeddings_fixture.json"
    }
}

/**
 * Picks the query embedder for this device, or null when dense retrieval cannot run
 * (the flag is off, or neither an encoder nor a fixture is present).
 *
 * The downloaded EmbeddingGemma encoder wins when its files are on disk. The fixture
 * comes second so a device audit can still replay precomputed vectors — dropping the
 * fixture into `filesDir` overrides nothing, it only fills a gap.
 */
internal object QueryEmbedders {
    fun resolve(context: Context, enableDenseRetrieval: Boolean): QueryEmbedder? {
        if (!enableDenseRetrieval) return null
        val encoderDir = File(context.getExternalFilesDir(null), EncoderModel.DIR_NAME)
        val encoder = LiteRtQueryEmbedder(encoderDir)
        if (encoder.isAvailable) {
            Log.i(TAG_RESOLVE, "dense query encoder: LiteRT EmbeddingGemma at $encoderDir")
            return encoder
        }
        val fixture = FixtureFileQueryEmbedder(context)
        if (fixture.isAvailable) {
            Log.i(TAG_RESOLVE, "dense query encoder: precomputed fixture (no model on disk)")
            return fixture
        }
        Log.i(TAG_RESOLVE, "dense query encoder: none — missing ${EncoderModel.missingFiles(encoderDir)}")
        return null
    }

    private const val TAG_RESOLVE = "QueryEmbedders"
}
