package com.medtroniclabs.microcoaching.domain.validation

import com.medtroniclabs.microcoaching.ai.retrieval.GroundingChunk
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OutputValidatorTest {

    private val validator = OutputValidator()

    @Test
    fun `allowFreeText=true skips drug block-list when there are no candidates`() {
        val result = validator.validateChatResponse(
            response = "Take 5 mg of amlodipine once a day. Please confirm with your supervisor for the specific case.",
            candidates = emptyList(),
            allowFreeText = true,
        )
        assertTrue("expected pass in open-scope mode, got: ${result.failureReason}", result.isValid)
    }

    @Test
    fun `allowFreeText=false preserves legacy drug block-list`() {
        val result = validator.validateChatResponse(
            response = "Take 5 mg of amlodipine once a day.",
            candidates = emptyList(),
            allowFreeText = false,
        )
        assertFalse(result.isValid)
        assertEquals("drug_not_in_source:amlodipine", result.failureReason)
    }

    @Test
    fun `allowFreeText=true still blocks diagnostic phrases`() {
        val result = validator.validateChatResponse(
            response = "You have diabetes. Please confirm with your supervisor for the specific case.",
            candidates = emptyList(),
            allowFreeText = true,
        )
        assertFalse(result.isValid)
        assertTrue(
            "expected diagnostic block, got: ${result.failureReason}",
            result.failureReason?.startsWith("diagnostic:") == true,
        )
    }

    @Test
    fun `allowFreeText=true still enforces length cap`() {
        val long = (1..300).joinToString(" ") { "word" }
        val result = validator.validateChatResponse(
            response = long,
            candidates = emptyList(),
            allowFreeText = true,
            maxWords = 250,
        )
        assertFalse(result.isValid)
        assertTrue(
            "expected too_long, got: ${result.failureReason}",
            result.failureReason?.startsWith("too_long:") == true,
        )
    }

    @Test
    fun `isOutOfScopeSentinel detects the sentinel`() {
        assertTrue(validator.isOutOfScopeSentinel("[[REFUSE_OUT_OF_SCOPE]]"))
        assertTrue(validator.isOutOfScopeSentinel("Some prefix [[REFUSE_OUT_OF_SCOPE]] trailing"))
        assertFalse(validator.isOutOfScopeSentinel("normal answer"))
        assertFalse(validator.isOutOfScopeSentinel("[[REFUSE_NO_GROUND]]"))
    }

    @Test
    fun `grounded path still rejects drugs not in the candidate set`() {
        val chunk = GroundingChunk(
            source = GroundingChunk.Source.CARD,
            moduleFamilyId = "hypertension-001",
            positionalId = 0,
            titleEn = "Hypertension counselling",
            bodyEn = "Encourage lifestyle changes such as low-salt diet and daily walks.",
            titleBn = null,
            bodyBn = null,
            score = 4.2f,
        )
        val result = validator.validateChatResponse(
            response = "Start metformin 500 mg twice daily.",
            candidates = listOf(chunk),
            allowFreeText = false,
        )
        assertFalse(result.isValid)
        assertEquals("drug_not_in_source:metformin", result.failureReason)
    }
}
