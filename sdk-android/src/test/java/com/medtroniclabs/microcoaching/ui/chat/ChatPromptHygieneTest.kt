package com.medtroniclabs.microcoaching.ui.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Prompt-hygiene helpers:
 *
 *  - [dropRefusalExchanges]: refusal turns replayed as history both waste the
 *    token window and prime a small model to refuse again.
 *  - [ChatViewModel.trimToCompleteSentence]: when the session token window
 *    closes mid-sentence (`sawEndOfTurn=false`), the dangling fragment must
 *    never reach the CHW.
 *  - [ChatViewModel.stripThinkSpans]: a reasoning model's internal deliberation must
 *    never be shown as clinical advice.
 *  - [ChatViewModel.stripSourcePreamble]: a CHW asked a clinical question, not what the
 *    prompt contains.
 */
class ChatPromptHygieneTest {

    private fun user(text: String) = ChatMessage(sessionId = "s", role = ChatRole.USER, text = text)

    private fun assistant(text: String, outcome: String? = null) = ChatMessage(
        sessionId = "s",
        role = ChatRole.ASSISTANT,
        text = text,
        meta = outcome?.let { ChatMessageMeta(outcome = it) },
    )

    // ── dropRefusalExchanges ──────────────────────────────────────────────────

    @Test
    fun `refusal pair is removed from model-facing history`() {
        val history = listOf(
            user("What should I advise to a PW with Low BP 90/60?"),
            assistant("I don't have this in my training material yet.", outcome = "refused_no_ground"),
            user("How can breast engorgement be managed?"),
            assistant("Support the mother with correct techniques.", outcome = "served_grounded"),
        )
        val filtered = dropRefusalExchanges(history)
        assertEquals(2, filtered.size)
        assertEquals("How can breast engorgement be managed?", filtered[0].text)
        assertEquals(ChatRole.ASSISTANT, filtered[1].role)
    }

    @Test
    fun `served and metaless turns pass through untouched`() {
        val history = listOf(
            user("q1"),
            assistant("a1", outcome = "served_grounded"),
            user("q2"),
            assistant("a2"), // restored history — no meta
        )
        assertEquals(history, dropRefusalExchanges(history))
    }

    @Test
    fun `consecutive refusals are all removed with their questions`() {
        val history = listOf(
            user("q1"),
            assistant("refusal", outcome = "refused_no_ground"),
            user("q1 again"),
            assistant("refusal", outcome = "refused_scope"),
        )
        assertTrue(dropRefusalExchanges(history).isEmpty())
    }

    // ── trimToCompleteSentence ────────────────────────────────────────────────

    @Test
    fun `truncated tail after the last sentence is dropped`() {
        val truncated = "Breast engorgement can be managed with warm compresses. " +
            "Pain can be alleviated by"
        assertEquals(
            "Breast engorgement can be managed with warm compresses.",
            ChatViewModel.trimToCompleteSentence(truncated),
        )
    }

    @Test
    fun `fragment with no complete sentence trims to empty for card fallback`() {
        // The verified danger-signs failure: 14 tokens of output, no terminator.
        val fragment = "There are several danger signs that need be recognized when"
        assertEquals("", ChatViewModel.trimToCompleteSentence(fragment))
    }

    @Test
    fun `bangla danda counts as a sentence terminator`() {
        val truncated = "নবজাতককে দ্রুত শুকিয়ে মুড়িয়ে রাখুন। এরপর মাথা ঢেকে"
        assertEquals(
            "নবজাতককে দ্রুত শুকিয়ে মুড়িয়ে রাখুন।",
            ChatViewModel.trimToCompleteSentence(truncated),
        )
    }


    // ── stripThinkSpans ───────────────────────────────────────────────────────

    @Test
    fun `a closed think span is removed and the answer kept`() {
        val raw = "<think>The card says lie down. Should I mention referral?</think>\n" +
            "Ask her to lie down and measure her BP again after four hours."

        assertEquals(
            "Ask her to lie down and measure her BP again after four hours.",
            ChatViewModel.stripThinkSpans(raw),
        )
    }

    @Test
    fun `multiple think spans are all removed`() {
        val raw = "<think>first</think>Lie down.<think>second</think> Recheck in four hours."

        assertEquals("Lie down. Recheck in four hours.", ChatViewModel.stripThinkSpans(raw))
    }

    /**
     * Generation stopped inside the reasoning block, so there is no answer — only a
     * half-formed thought. Dropping the remainder routes the turn to the empty-response
     * path rather than showing the CHW the model reasoning with itself.
     */
    @Test
    fun `an unclosed think span takes everything after it`() {
        val raw = "<think>The references mention 140 over 90, so maybe I should"

        assertEquals("", ChatViewModel.stripThinkSpans(raw))
    }

    @Test
    fun `text with no think span is untouched`() {
        val answer = "Refer her urgently if she has heavy bleeding, fever, or convulsions."

        assertEquals(answer, ChatViewModel.stripThinkSpans(answer))
    }

    // ── stripSourcePreamble ───────────────────────────────────────────────────

    @Test
    fun `a leading talks-about-the-prompt clause is dropped and the answer capitalised`() {
        val raw = "The context mentions that a newborn weighs 2.2 kg and needs kangaroo care."

        assertEquals(
            "A newborn weighs 2.2 kg and needs kangaroo care.",
            ChatViewModel.stripSourcePreamble(raw),
        )
    }

    @Test
    fun `an according-to preface is dropped`() {
        listOf(
            "Based on the information provided, refer her urgently to the hospital.",
            "According to the card, refer her urgently to the hospital.",
            "As per the references above: refer her urgently to the hospital.",
        ).forEach { raw ->
            assertEquals(
                "Refer her urgently to the hospital.",
                ChatViewModel.stripSourcePreamble(raw),
            )
        }
    }

    @Test
    fun `an answer that does not open with a preamble is untouched`() {
        val answer = "Ask her to lie down and measure her blood pressure again after four hours."

        assertEquals(answer, ChatViewModel.stripSourcePreamble(answer))
    }

    /** A statement about what the card omits is an answer in its own right, not a preface. */
    @Test
    fun `a sentence reporting what the card does not cover survives`() {
        val answer = "The information above does not mention a dose for this."

        assertEquals(answer, ChatViewModel.stripSourcePreamble(answer))
    }

    /** Stripping must never leave a fragment where there was a sentence. */
    @Test
    fun `a preamble with nothing substantial after it is left alone`() {
        val raw = "Based on the information provided, yes."

        assertEquals(raw, ChatViewModel.stripSourcePreamble(raw))
    }

    @Test
    fun `mid-answer mentions are not touched`() {
        val answer = "Refer her urgently. According to the card, this applies before 37 weeks."

        assertEquals(answer, ChatViewModel.stripSourcePreamble(answer))
    }
}
