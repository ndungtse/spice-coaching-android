package com.medtroniclabs.microcoaching.ai.inference

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * Model-file resolution priority. A host that scans the external files dir and adopts the
 * first model it finds can pass something the bundled engine cannot load — a `.task` left by
 * an earlier SDK is the case seen in the field — and preferring it unconditionally would
 * strand chat on the setup screen with a loadable model sitting beside it.
 */
class InferenceRouterResolveTest {

    @get:Rule
    val temp = TemporaryFolder()

    private val expected = "qwen3_0_6b_mixed_int4.litertlm"

    /** The real predicate — both bundled engines, so the test cannot drift from it. */
    private val canLoad: (File) -> Boolean = InferenceRouter::canLoad

    private fun resolve(
        configuredModelPath: String,
        externalDir: File?,
        expectedFileName: String = expected,
    ) = InferenceRouter.resolveModelFile(configuredModelPath, externalDir, expectedFileName, canLoad)

    @Test
    fun `prefers a loadable configured modelPath`() {
        val dir = temp.newFolder("files")
        val bundle = File(dir, expected).apply { writeText("model") }

        assertEquals(bundle, resolve(bundle.absolutePath, dir))
    }

    @Test
    fun `falls back to the variant file when modelPath has no engine`() {
        val dir = temp.newFolder("files")
        // A leftover from an SDK that still shipped a `.task` engine.
        val stale = File(dir, "gemma3-270m-it-q8.task").apply { writeText("unloadable") }
        val bundle = File(dir, expected).apply { writeText("model") }

        assertEquals(bundle, resolve(stale.absolutePath, dir))
    }

    @Test
    fun `falls back to the variant file when modelPath does not exist`() {
        val dir = temp.newFolder("files")
        val bundle = File(dir, expected).apply { writeText("model") }

        assertEquals(bundle, resolve(File(dir, "gone.litertlm").absolutePath, dir))
    }

    @Test
    fun `resolves from the external dir when no modelPath is configured`() {
        val dir = temp.newFolder("files")
        val bundle = File(dir, expected).apply { writeText("model") }

        assertEquals(bundle, resolve("", dir))
    }

    @Test
    fun `ignores a different variant's file in the same directory`() {
        val dir = temp.newFolder("files")
        File(dir, "gemma3-1b-it-int4.litertlm").writeText("other variant")

        // Matched by exact name so coexisting variants stay deterministic.
        assertNull(resolve("", dir))
    }

    @Test
    fun `returns null when only an unloadable file exists`() {
        val dir = temp.newFolder("files")
        val stale = File(dir, "gemma3-270m-it-q8.task").apply { writeText("unloadable") }

        // Nothing loadable anywhere — the caller must show the download CTA, not
        // hand an unloadable file to the engine.
        assertNull(resolve(stale.absolutePath, dir))
    }

    @Test
    fun `returns null when the external dir is unavailable`() {
        assertNull(resolve("", null))
    }

    /**
     * A host scanning for the first model file gets an unordered answer from `listFiles()`, so
     * it can pass a leftover from an earlier default model while `ModelManager` reports the
     * selected variant as ready. Resolution must not let the two disagree.
     */
    @Test
    fun `ignores a configured modelPath naming a different variant`() {
        val dir = temp.newFolder("files")
        val stale = File(dir, "gemma3-1b-it-int4.litertlm").apply { writeText("older default model") }
        val bundle = File(dir, expected).apply { writeText("model") }

        assertEquals(bundle, resolve(stale.absolutePath, dir))
    }

    /**
     * Same rule with nothing to fall back on: another variant's bundle is no substitute for
     * the selected one, so resolution fails and the caller shows the download CTA.
     */
    @Test
    fun `returns null when only a different variant's bundle is configured`() {
        val dir = temp.newFolder("files")
        val stale = File(dir, "gemma3-1b-it-int4.litertlm").apply { writeText("older default model") }

        assertNull(resolve(stale.absolutePath, dir))
    }

    /** A path outside the model dir is still fine, as long as it names the right file. */
    @Test
    fun `accepts a configured modelPath outside the external dir`() {
        val dir = temp.newFolder("files")
        val sideloaded = File(temp.newFolder("sideload"), expected).apply { writeText("model") }

        assertEquals(sideloaded, resolve(sideloaded.absolutePath, dir))
    }
}
