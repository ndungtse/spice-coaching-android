package com.medtroniclabs.microcoaching.data.db.entity

import org.junit.Assert.assertEquals
import org.junit.Assert.assertArrayEquals
import org.junit.Test
import kotlin.math.sqrt

/**
 * The card-embedding BLOB codec: vectors are stored L2-normalized as little-endian
 * float32, so the dense index can treat cosine as a dot product and a row read back
 * is exactly what search needs.
 */
class CardEmbeddingVectorCodecTest {

    @Test
    fun `floats round-trip through the blob`() {
        val vec = floatArrayOf(0.25f, -1.5f, 3.75f, 0f)
        assertArrayEquals(vec, vec.toLeBytes().toFloatVector(), 0f)
    }

    @Test
    fun `normalized copy has unit length and keeps direction`() {
        val vec = floatArrayOf(3f, 4f)
        val normalized = vec.l2Normalized()
        assertEquals(0.6f, normalized[0], 1e-6f)
        assertEquals(0.8f, normalized[1], 1e-6f)
        val len = sqrt(normalized.fold(0f) { acc, x -> acc + x * x })
        assertEquals(1f, len, 1e-6f)
    }

    @Test
    fun `zero vector normalizes to itself rather than dividing by zero`() {
        val vec = floatArrayOf(0f, 0f, 0f)
        assertArrayEquals(vec, vec.l2Normalized(), 0f)
    }

    @Test
    fun `blob length is four bytes per dimension`() {
        assertEquals(768 * 4, FloatArray(768).toLeBytes().size)
    }
}
