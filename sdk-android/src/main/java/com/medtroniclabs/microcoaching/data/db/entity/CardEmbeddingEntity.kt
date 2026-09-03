package com.medtroniclabs.microcoaching.data.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.sqrt

/**
 * One card's embedding vector from `GET /sync/card-embeddings`, joined to the
 * card cache on [cardId] (the per-card `id` inside `module_cache.cards_json`).
 *
 * [vec] is the vector as little-endian float32 (`dim × 4` bytes), stored
 * **L2-normalized** so the dense index computes cosine as a plain dot product.
 * [modelId] records which encoder produced it, so a model upgrade can detect and
 * discard vectors the on-device query encoder no longer matches.
 */
@Entity(tableName = "card_embedding")
data class CardEmbeddingEntity(
    @PrimaryKey @ColumnInfo(name = "card_id") val cardId: String,
    @ColumnInfo(name = "module_family_id") val moduleFamilyId: String,
    @ColumnInfo(name = "dim") val dim: Int,
    @ColumnInfo(name = "model_id") val modelId: String?,
    @ColumnInfo(name = "vec", typeAffinity = ColumnInfo.BLOB) val vec: ByteArray,
    @ColumnInfo(name = "synced_at_ms") val syncedAtMs: Long,
) {
    fun toFloatVector(): FloatArray = vec.toFloatVector()

    // ByteArray fields break data-class equality; identity is the primary key.
    override fun equals(other: Any?): Boolean = other is CardEmbeddingEntity && other.cardId == cardId
    override fun hashCode(): Int = cardId.hashCode()
}

/** Little-endian float32 encoding — 4 bytes per dimension. */
fun FloatArray.toLeBytes(): ByteArray {
    val buf = ByteBuffer.allocate(size * 4).order(ByteOrder.LITTLE_ENDIAN)
    for (f in this) buf.putFloat(f)
    return buf.array()
}

fun ByteArray.toFloatVector(): FloatArray {
    val buf = ByteBuffer.wrap(this).order(ByteOrder.LITTLE_ENDIAN)
    return FloatArray(size / 4) { buf.getFloat(it * 4) }
}

/** Unit-length copy; the zero vector comes back unchanged rather than dividing by zero. */
fun FloatArray.l2Normalized(): FloatArray {
    var sum = 0f
    for (f in this) sum += f * f
    if (sum == 0f) return copyOf()
    val inv = 1f / sqrt(sum)
    return FloatArray(size) { this[it] * inv }
}
