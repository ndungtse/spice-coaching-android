package com.medtroniclabs.microcoaching.domain.validation

import com.medtroniclabs.microcoaching.ai.retrieval.GroundingChunk
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins why an English answer scores zero against a Bengali-only card, and what fixes it.
 *
 * Groundedness is token overlap between the response and its references. Across a language
 * boundary that overlap is zero for arithmetic reasons, not quality ones: a perfectly faithful
 * English rephrasing of a Bengali card shares no tokens with it. The floor then discards every
 * answer, so the model can never contribute and the served text is always the card verbatim.
 *
 * The measurement only becomes meaningful once the references carry text in the language the
 * answer is written in.
 */
class GroundednessLanguageTest {

    private val validator = OutputValidator()

    /** The malaria card as it actually ships: Bengali title and body, no English side. */
    private fun banglaOnlyCard() = GroundingChunk(
        source = GroundingChunk.Source.CARD,
        moduleFamilyId = "dcde24a9",
        positionalId = 0,
        titleEn = null,
        bodyEn = null,
        titleBn = "ম্যালেরিয়া কী এবং এর জীবাণু",
        bodyBn = "ম্যালেরিয়া একটি সংক্রামক রোগ যা প্লাজমোডিয়াম প্রজাতির জীবাণু দ্বারা হয় এবং " +
            "জ্বরের মাধ্যমে প্রকাশ পায়। এই রোগ অ্যানোফিলিস স্ত্রী মশার কামড়ের মাধ্যমে ছড়ায়।",
        score = 65.28f,
    )

    /** The same card once its Bengali side has been translated for the model to read. */
    private fun translatedCard() = banglaOnlyCard().copy(
        titleEn = "What malaria is and its germ",
        bodyEn = "Malaria is an infectious disease caused by germs of the plasmodium species " +
            "and is revealed through fever. This disease spreads through the bite of the " +
            "anopheles female mosquito.",
    )

    /** A faithful, on-topic English answer drawn from that card. */
    private val faithfulEnglishAnswer =
        "Malaria is caused by plasmodium germs and spreads through the bite of an " +
            "anopheles female mosquito. It shows up as fever."

    /**
     * The defect. Nothing about this answer is wrong, yet it is unusable: the score is exactly
     * zero, so no floor above zero can ever admit it.
     */
    @Test
    fun `english answer scores exactly zero against a bangla-only card`() {
        val score = validator.groundednessScore(faithfulEnglishAnswer, listOf(banglaOnlyCard()))
        assertEquals(0f, score, 0.0001f)
    }

    /** Translating the references is what turns the score into a real measurement. */
    @Test
    fun `same answer clears the floor once the card carries english text`() {
        val score = validator.groundednessScore(faithfulEnglishAnswer, listOf(translatedCard()))
        assertTrue("expected > 0.25, got $score", score > 0.25f)
    }

    /**
     * The gate must stay able to reject. An answer from pre-training that ignores the card —
     * the "five servings of fruit and vegetables" class of response — must still fail even
     * after the references are readable, or translating them would just disable the check.
     */
    @Test
    fun `pretraining answer still fails against a translated card`() {
        val offCard = "Aim for at least five servings of fruit and vegetables per day, " +
            "plus lean protein and whole grains, and stay well hydrated throughout."
        val score = validator.groundednessScore(offCard, listOf(translatedCard()))
        assertTrue("expected <= 0.25, got $score", score <= 0.25f)
    }
}
