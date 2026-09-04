package com.medtroniclabs.microcoaching.sync

import com.medtroniclabs.microcoaching.data.db.entity.toFloatVector
import com.medtroniclabs.microcoaching.network.CardEmbeddingDto
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CardEmbeddingMapperTest {

    @Test
    fun `dto maps to a normalized blob entity`() {
        val dto = CardEmbeddingDto(
            cardId = "card-1",
            moduleId = "m-1",
            cardFamilyId = "fam-1",
            embedding = listOf(3f, 4f),
        )
        val entity = dto.toEntity(expectedDim = 2, modelId = "test-model", nowMs = 42L)!!
        assertEquals("card-1", entity.cardId)
        assertEquals("fam-1", entity.moduleFamilyId)
        assertEquals(2, entity.dim)
        assertEquals("test-model", entity.modelId)
        assertEquals(42L, entity.syncedAtMs)
        val vec = entity.vec.toFloatVector()
        assertEquals(0.6f, vec[0], 1e-6f)
        assertEquals(0.8f, vec[1], 1e-6f)
    }

    @Test
    fun `wrong dimension is rejected`() {
        val dto = CardEmbeddingDto(cardId = "card-1", cardFamilyId = "fam-1", embedding = listOf(1f, 2f, 3f))
        assertNull(dto.toEntity(expectedDim = 2, modelId = null, nowMs = 0L))
    }

    @Test
    fun `empty embedding is rejected`() {
        val dto = CardEmbeddingDto(cardId = "card-1", cardFamilyId = "fam-1", embedding = emptyList())
        assertNull(dto.toEntity(expectedDim = 0, modelId = null, nowMs = 0L))
    }

    @Test
    fun `missing family id is rejected`() {
        val dto = CardEmbeddingDto(cardId = "card-1", cardFamilyId = null, embedding = listOf(1f, 0f))
        assertNull(dto.toEntity(expectedDim = 2, modelId = null, nowMs = 0L))
    }

    // ── encoder-change detection ─────────────────────────────────────────────
    // Vectors from two different encoders occupy unrelated spaces, so a cosine
    // between them is meaningless rather than merely inaccurate. Mixing them is
    // worse than having no dense index at all, which is why the whole table goes.

    @Test
    fun `an unchanged model id keeps the stored vectors`() {
        assertFalse(shouldDiscardStaleVectors(storedModelId = "gemma-v1", bundleModelId = "gemma-v1"))
    }

    @Test
    fun `a changed model id discards the stored vectors`() {
        assertTrue(shouldDiscardStaleVectors(storedModelId = "gemma-v1", bundleModelId = "gemma-v2"))
    }

    @Test
    fun `vectors of unknown provenance are discarded once a model id arrives`() {
        assertTrue(shouldDiscardStaleVectors(storedModelId = null, bundleModelId = "gemma-v1"))
    }

    @Test
    fun `a bundle naming no model cannot condemn what is stored`() {
        assertFalse(shouldDiscardStaleVectors(storedModelId = "gemma-v1", bundleModelId = null))
        assertFalse(shouldDiscardStaleVectors(storedModelId = null, bundleModelId = null))
        assertFalse(shouldDiscardStaleVectors(storedModelId = "gemma-v1", bundleModelId = "  "))
    }

    @Test
    fun `nothing stored means nothing to discard`() {
        assertFalse(shouldDiscardStaleVectors(storedModelId = null, bundleModelId = "gemma-v1", storedRows = 0))
    }
}
