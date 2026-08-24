package com.medtroniclabs.microcoaching.ui.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the grounded prompt to the wording the model was evaluated on.
 *
 * That evaluation is the whole value of this prompt: a sub-1B model's behaviour shifts
 * markedly between prompt designs, so an edit here quietly invalidates the recorded scores.
 * Asserting the exact string makes such an edit fail a test rather than a deployment.
 */
class ContextAnswerPromptTest {

    @Test
    fun `prompt matches the evaluated wording exactly`() {
        val prompt = buildContextAnswerPrompt(
            context = "If the pregnant mother has high blood pressure, ask her to lie down.",
            question = "I checked a pregnant woman's BP and it is above 140/90 mmHg. What should I do?",
        )

        assertEquals(
            """
If the pregnant mother has high blood pressure, ask her to lie down.

Question: I checked a pregnant woman's BP and it is above 140/90 mmHg. What should I do?

Answer the health worker directly, using only the information above.
Do not add anything it does not say.
            """.trimIndent(),
            prompt,
        )
    }

    /**
     * The card text carries no label. A small model quotes whatever label it is handed, and
     * "the context mentions…" is not something to say to a health worker.
     */
    /** Length is bounded by ChatTuning, not by a sentence count the model may need to break. */
    @Test
    fun `prompt does not prescribe an answer length`() {
        val prompt = buildContextAnswerPrompt(context = "Card body.", question = "Question?")

        listOf("two or three", "sentences", "briefly", "concise", "short").forEach {
            assertFalse("prompt should not prescribe length: \"$it\"", prompt.contains(it))
        }
    }

    @Test
    fun `prompt gives the card text no label to quote back`() {
        val prompt = buildContextAnswerPrompt(context = "Card body.", question = "Question?")

        assertFalse(prompt.contains("Context:"))
        assertFalse(prompt.contains("the context"))
        assertTrue(prompt.startsWith("Card body."))
    }

    /**
     * The engine wraps this in the model's own chat template, so markers written here would
     * be templated twice and arrive as literal characters. The older scaffolding (system
     * role, `[1] (card)` labels, refusal sentinel) belongs to a prompt this one replaced.
     */
    @Test
    fun `prompt carries no chat-template markers or legacy scaffolding`() {
        val prompt = buildContextAnswerPrompt(context = "Card body.", question = "Question?")

        listOf(
            "<start_of_turn>", "<end_of_turn>", "<|im_start|>", "<|im_end|>",
            "Key facts", "Reference content", "[1]", "[[REFUSE_NO_GROUND]]",
        ).forEach { fragment ->
            assertFalse("prompt should not contain \"$fragment\"", prompt.contains(fragment))
        }
    }

    @Test
    fun `context block joins the served card texts with a blank line`() {
        val context = buildGroundingContext(
            listOf("Ask her to lie down.", "She must drink eight to ten glasses."),
        )

        assertEquals("Ask her to lie down.\n\nShe must drink eight to ten glasses.", context)
    }

    /**
     * The served text arrives whole. Clipping it here is what made the model reword a card
     * whose second half the CHW would never see.
     */
    @Test
    fun `context block passes a long card through uncut`() {
        val long = "A pregnant mother must rest. " + "She must drink water every single day. ".repeat(40)

        assertEquals(long, buildGroundingContext(listOf(long)))
    }

    @Test
    fun `blank cards are dropped and an empty list yields an empty block`() {
        assertEquals("", buildGroundingContext(emptyList()))
        assertEquals("Only this.", buildGroundingContext(listOf("", "Only this.", "   ")))
    }
}
