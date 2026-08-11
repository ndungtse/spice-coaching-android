package com.medtroniclabs.microcoaching.ui.learn

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class QuizShufflerTest {

    private fun question(
        id: String,
        answers: List<String>,
        correctIndex: Int,
    ) = QuizQuestion(id = id, questionText = "q-$id", answers = answers, correctIndex = correctIndex)

    @Test
    fun `withShuffledOptions keeps the correct answer's text as the correct answer`() {
        val q = question("q1", listOf("A", "B", "C", "D"), correctIndex = 2) // correct = "C"
        // Try many seeds so we cover multiple permutations, including no-op orders.
        for (seed in 0..200) {
            val shuffled = q.withShuffledOptions(Random(seed))
            assertEquals(
                "correct answer text must survive the shuffle (seed=$seed)",
                "C",
                shuffled.answers[shuffled.correctIndex],
            )
            // Same options, just reordered.
            assertEquals(q.answers.sorted(), shuffled.answers.sorted())
        }
    }

    @Test
    fun `canonicalOptionIndex maps every displayed index back to the authored index`() {
        val q = question("q1", listOf("A", "B", "C", "D"), correctIndex = 2)
        val shuffled = q.withShuffledOptions(Random(7))
        // The authored index of each displayed option is its position in the original list.
        shuffled.answers.forEachIndexed { displayIndex, text ->
            val authored = q.answers.indexOf(text)
            assertEquals(authored, shuffled.canonicalOptionIndex(displayIndex))
        }
        // In particular, the displayed correct index maps back to the authored correct index.
        assertEquals(2, shuffled.canonicalOptionIndex(shuffled.correctIndex))
    }

    @Test
    fun `reshuffling an already-shuffled question keeps the mapping relative to authored order`() {
        val q = question("q1", listOf("A", "B", "C", "D"), correctIndex = 0) // correct = "A"
        val once = q.withShuffledOptions(Random(3))
        val twice = once.withShuffledOptions(Random(99))
        // canonicalOptionIndex on the twice-shuffled question must still resolve to authored indices.
        twice.answers.forEachIndexed { displayIndex, text ->
            val authored = q.answers.indexOf(text)
            assertEquals(authored, twice.canonicalOptionIndex(displayIndex))
        }
        assertEquals("A", twice.answers[twice.correctIndex])
        assertEquals(0, twice.canonicalOptionIndex(twice.correctIndex))
    }

    @Test
    fun `single-option and empty questions are returned unchanged`() {
        val single = question("q1", listOf("only"), correctIndex = 0)
        assertSame(single, single.withShuffledOptions(Random(1)))
        val empty = question("q2", emptyList(), correctIndex = 0)
        assertSame(empty, empty.withShuffledOptions(Random(1)))
        // Unshuffled question ⇒ identity canonical mapping.
        assertEquals(0, single.canonicalOptionIndex(0))
    }

    @Test
    fun `shuffledForAttempt preserves the multiset of questions and remaps each question`() {
        val questions = (1..6).map { question("q$it", listOf("A$it", "B$it", "C$it"), correctIndex = 1) }
        val shuffled = questions.shuffledForAttempt(Random(42))
        assertEquals(questions.size, shuffled.size)
        assertEquals(questions.map { it.id }.toSet(), shuffled.map { it.id }.toSet())
        // Each question still points at its own correct answer text after option shuffle.
        shuffled.forEach { s ->
            val original = questions.first { it.id == s.id }
            assertEquals(
                original.answers[original.correctIndex],
                s.answers[s.correctIndex],
            )
        }
    }

    @Test
    fun `shuffledForAttempt actually changes order for a large set`() {
        val questions = (1..12).map { question("q$it", listOf("A", "B", "C", "D"), correctIndex = 0) }
        val shuffled = questions.shuffledForAttempt(Random(1))
        // With 12 questions the odds of an identical order under a fixed seed are negligible.
        assertNotEquals(questions.map { it.id }, shuffled.map { it.id })
        assertTrue(shuffled.all { it.optionOriginalIndices.size == 4 })
    }
}
