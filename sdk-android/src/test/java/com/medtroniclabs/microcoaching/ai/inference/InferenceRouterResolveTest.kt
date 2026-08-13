package com.medtroniclabs.microcoaching.ai.inference

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * Model-file resolution priority. A host that scans the external files dir and
 * adopts the first model it finds can pass a `.litertlm` as `modelPath`; no
 * bundled engine loads that, so preferring it unconditionally would strand chat
 * on the setup screen even with a loadable `.task` sitting beside it.
 */
class InferenceRouterResolveTest {

    @get:Rule
    val temp = TemporaryFolder()

    private val expected = "gemma3-270m-it-q8.task"

    /** Mirrors the real predicate: only MediaPipe `.task` has a bundled engine. */
    private val canLoad: (File) -> Boolean = { it.name.endsWith(".task") }

    private fun resolve(configuredModelPath: String, externalDir: File?) =
        InferenceRouter.resolveModelFile(configuredModelPath, externalDir, expected, canLoad)

    @Test
    fun `prefers a loadable configured modelPath`() {
        val dir = temp.newFolder("files")
        val task = File(dir, expected).apply { writeText("model") }

        assertEquals(task, resolve(task.absolutePath, dir))
    }

    @Test
    fun `falls back to the variant file when modelPath is an unloadable litertlm`() {
        val dir = temp.newFolder("files")
        val litertlm = File(dir, "gemma3-270m-it-q8.litertlm").apply { writeText("unloadable") }
        val task = File(dir, expected).apply { writeText("model") }

        // Host handed us the unloadable file, but a loadable one is right there.
        assertEquals(task, resolve(litertlm.absolutePath, dir))
    }

    @Test
    fun `falls back to the variant file when modelPath does not exist`() {
        val dir = temp.newFolder("files")
        val task = File(dir, expected).apply { writeText("model") }

        assertEquals(task, resolve(File(dir, "gone.task").absolutePath, dir))
    }

    @Test
    fun `resolves from the external dir when no modelPath is configured`() {
        val dir = temp.newFolder("files")
        val task = File(dir, expected).apply { writeText("model") }

        assertEquals(task, resolve("", dir))
    }

    @Test
    fun `ignores a different variant's file in the same directory`() {
        val dir = temp.newFolder("files")
        File(dir, "gemma3-1b-it-int4.task").writeText("other variant")

        // Matched by exact name so coexisting variants stay deterministic.
        assertNull(resolve("", dir))
    }

    @Test
    fun `returns null when only an unloadable file exists`() {
        val dir = temp.newFolder("files")
        val litertlm = File(dir, "gemma3-270m-it-q8.litertlm").apply { writeText("unloadable") }

        // Nothing loadable anywhere — the caller must show the download CTA, not
        // hand an unloadable file to the engine.
        assertNull(resolve(litertlm.absolutePath, dir))
    }

    @Test
    fun `returns null when the external dir is unavailable`() {
        assertNull(resolve("", null))
    }

    /**
     * A host scanning for the first `.task` gets an unordered answer from `listFiles()`, so
     * it can pass a leftover from an earlier default model while `ModelManager` reports the
     * selected variant as ready. Resolution must not let the two disagree.
     */
    @Test
    fun `ignores a configured modelPath naming a different variant`() {
        val dir = temp.newFolder("files")
        val stale = File(dir, "gemma3-1b-it-int4.task").apply { writeText("older default model") }
        val task = File(dir, expected).apply { writeText("model") }

        assertEquals(task, resolve(stale.absolutePath, dir))
    }

    /**
     * Same rule with nothing to fall back on: a foreign `.task` is no substitute for the
     * selected variant, so resolution fails and the caller shows the download CTA.
     */
    @Test
    fun `returns null when only a different variant's task is configured`() {
        val dir = temp.newFolder("files")
        val stale = File(dir, "gemma3-1b-it-int4.task").apply { writeText("older default model") }

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
