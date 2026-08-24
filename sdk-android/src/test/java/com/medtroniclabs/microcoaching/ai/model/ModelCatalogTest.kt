package com.medtroniclabs.microcoaching.ai.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Invariants of the model allowlist. The catalog is plain constants, so the failures worth
 * catching are the ones a constant can cause: a default nothing can load, an id that no
 * longer exists, or a format routed to the wrong validator.
 */
class ModelCatalogTest {

    @Test
    fun `the default variant exists and a bundled engine can load it`() {
        val default = ModelCatalog.default()

        assertEquals(ModelCatalog.DEFAULT_ID, default.id)
        assertTrue(
            "DEFAULT_ID '${default.id}' runtime=${default.runtime} has no bundled engine",
            ModelCatalog.isRunnable(default),
        )
    }

    /**
     * Rolling back is a change of one constant, so every id named as a fallback in
     * [ModelCatalog]'s KDoc has to still resolve and still load.
     */
    @Test
    fun `the documented rollback variants are present and runnable`() {
        listOf("gemma3-1b-it-int4-litertlm", "gemma3-270m-it-q8-litertlm").forEach { id ->
            val variant = requireNotNull(ModelCatalog.byId(id)) { "rollback id '$id' is gone" }
            assertTrue("rollback id '$id' is not runnable", ModelCatalog.isRunnable(variant))
        }
    }

    /** One engine ships, so a variant on any other runtime cannot load. */
    @Test
    fun `only LiteRT-LM variants are runnable`() {
        ModelCatalog.ALLOWLIST.forEach { variant ->
            assertEquals(
                "${variant.id} (${variant.runtime})",
                variant.runtime == ModelRuntime.LITERT_LM,
                ModelCatalog.isRunnable(variant),
            )
        }
    }

    /**
     * Every variant must be a container the SDK can actually check. A file with no
     * structural validator would be adopted as Ready on the strength of its name alone.
     */
    @Test
    fun `every variant is a container with a validator`() {
        ModelCatalog.ALLOWLIST.forEach { variant ->
            assertTrue(
                "${variant.fileName} has no container check",
                ModelCatalog.isLiteRtLmBundle(variant),
            )
        }
    }

    /** Nothing may offer a `.task`: no bundled engine can load one. */
    @Test
    fun `no variant is a task bundle`() {
        ModelCatalog.ALLOWLIST.forEach { variant ->
            assertFalse(variant.fileName, variant.fileName.endsWith(".task"))
            assertFalse(variant.id, variant.runtime == ModelRuntime.MEDIAPIPE)
        }
    }

    @Test
    fun `ids and filenames are unique`() {
        val ids = ModelCatalog.ALLOWLIST.map { it.id }
        val fileNames = ModelCatalog.ALLOWLIST.map { it.fileName }

        assertEquals("duplicate ids in ALLOWLIST", ids.distinct().size, ids.size)
        // Resolution matches by exact filename, so a duplicate would make two variants
        // indistinguishable on disk.
        assertEquals("duplicate filenames in ALLOWLIST", fileNames.distinct().size, fileNames.size)
    }

    @Test
    fun `an unknown id falls back to the default`() {
        assertEquals(ModelCatalog.default(), ModelCatalog.resolve("no-such-model"))
    }
}
