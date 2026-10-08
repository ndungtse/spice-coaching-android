package com.medtroniclabs.microcoaching.ai.inference

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

/**
 * The embedder must follow the files: a download finishing mid-session is used on the next
 * turn, and files going away release the interpreter instead of keeping 110 MB mapped.
 */
class QueryEmbedderHolderTest {

    private class FakeEmbedder(var available: Boolean = true) : QueryEmbedder {
        var releases = 0
        override val isAvailable: Boolean get() = available
        override suspend fun embed(text: String): FloatArray? = floatArrayOf(1f)
        override suspend fun release() { releases++ }
    }

    @Test
    fun `a download that lands mid-session is picked up, then held across turns`() = runBlocking {
        var resolves = 0
        var onDisk: QueryEmbedder? = null
        val holder = QueryEmbedderHolder { resolves++; onDisk }
        assertNull(holder.get())
        val arrived = FakeEmbedder()
        onDisk = arrived
        assertSame(arrived, holder.get())
        // Resolving again would build a fresh interpreter, and reload the weights, every turn.
        assertSame(arrived, holder.get())
        assertEquals(2, resolves)
    }

    @Test
    fun `an embedder whose files went away is released`() = runBlocking {
        val first = FakeEmbedder()
        var onDisk: QueryEmbedder? = first
        val holder = QueryEmbedderHolder { onDisk }
        holder.get()
        first.available = false
        onDisk = null
        assertNull(holder.get())
        assertEquals(1, first.releases)
    }

    @Test
    fun `release frees the held embedder once, and the next turn resolves afresh`() = runBlocking {
        var resolves = 0
        val embedder = FakeEmbedder()
        val holder = QueryEmbedderHolder { resolves++; embedder }
        holder.get()
        holder.release()
        holder.release()
        assertEquals(1, embedder.releases)
        holder.get()
        assertEquals(2, resolves)
    }
}
