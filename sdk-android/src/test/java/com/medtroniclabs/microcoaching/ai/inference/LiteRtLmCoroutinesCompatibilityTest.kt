package com.medtroniclabs.microcoaching.ai.inference

import kotlinx.coroutines.channels.SendChannel
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Why [LiteRtLmService.generateResponseStream] hand-rolls a callback→Flow bridge instead of
 * calling the library's own `Conversation.sendMessageAsync(prompt): Flow<Message>`.
 *
 * That bridge resolves `SendChannel.close$default` — the synthetic for `close(cause = null)`
 * — as a static **on the interface**, while the coroutines version this project resolves
 * keeps it in `SendChannel$DefaultImpls`. The call therefore finds nothing and throws
 * `NoSuchMethodError` from `onDone`, on a native callback thread past any `catch` in this
 * SDK, which takes the process with it.
 *
 * These assertions describe the classpath rather than our code, so they cannot reproduce
 * that — only a device can. What they do is fail the day coroutines moves the synthetic onto
 * the interface, which is exactly when the library's one-liner becomes usable and the
 * hand-rolled bridge can go.
 */
class LiteRtLmCoroutinesCompatibilityTest {

    private val sendChannel = SendChannel::class.java

    @Test
    fun `close default synthetic lives in DefaultImpls, not on the SendChannel interface`() {
        val onInterface = sendChannel.declaredMethods.any { it.name == CLOSE_DEFAULT }
        val onDefaultImpls = Class.forName("${sendChannel.name}\$DefaultImpls")
            .declaredMethods.any { it.name == CLOSE_DEFAULT }

        assertTrue(
            "SendChannel\$DefaultImpls.$CLOSE_DEFAULT is gone — the coroutines layout changed, " +
                "so re-check whether LiteRtLmService still needs its own callback bridge",
            onDefaultImpls,
        )
        assertTrue(
            "SendChannel.$CLOSE_DEFAULT now exists on the interface: LiteRT-LM's own " +
                "sendMessageAsync(): Flow<Message> should work, and the hand-rolled bridge in " +
                "LiteRtLmService can be replaced by it",
            !onInterface,
        )
    }

    /** The `close(cause)` this SDK's own code calls must still be there to call. */
    @Test
    fun `the SendChannel close this SDK compiles against is present`() {
        assertTrue(
            sendChannel.declaredMethods.any { method ->
                method.name == "close" && method.parameterTypes.size == 1
            },
        )
    }

    private companion object {
        const val CLOSE_DEFAULT = "close\$default"
    }
}
