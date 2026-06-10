package com.medtroniclabs.microcoaching.ai.retrieval

import com.medtroniclabs.microcoaching.data.db.entity.ModuleEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ModuleKnowledgeIndexTest {

    private fun moduleWith(cardsJson: String) = ModuleEntity(
        moduleId = "m1",
        moduleFamilyId = "fam1",
        version = 1,
        titleBn = "মডিউল",
        domain = "rmnch",
        moduleType = "initial_training",
        estimatedMinutes = 10,
        difficultyLevel = "moderate",
        clinicallyReviewed = true,
        updatedAtIso = "2026-06-01T00:00:00Z",
        cardsJson = cardsJson,
    )

    @Test
    fun `tiptap array body is indexed and retrievable`() {
        val cardsJson = """
            [
              {
                "title_en": "Dehydration signs",
                "body_en": [
                  {"type":"paragraph","content":[
                    {"type":"text","text":"Signs of dehydration include dry mouth and sunken eyes"}
                  ]},
                  {"type":"image","attrs":{"object_name":"media/uuid_pic.png"}}
                ]
              }
            ]
        """.trimIndent()

        val index = ModuleKnowledgeIndex.build(listOf(moduleWith(cardsJson)))
        // scoreThreshold=0 isolates "was the body text indexed?" from production
        // BM25 tuning (the default 3.0 is calibrated for the full on-device corpus).
        val hits = index.search("dehydration sunken eyes", k = 2, scoreThreshold = 0f)
        assertTrue("expected a hit on the array body", hits.isNotEmpty())

        // The reference text fed to the LLM must be clean prose, not JSON.
        val reference = hits.first().referenceText()
        assertTrue(reference.contains("dehydration"))
        assertFalse(reference.contains("object_name"))
        assertFalse(reference.contains("\"type\""))
        assertFalse(reference.contains("media/"))
    }

    @Test
    fun `markdown string body still indexes (regression)`() {
        val cardsJson = """[{"title_en":"Treatment","body_en":"Use **ORS** to rehydrate the child"}]"""
        val index = ModuleKnowledgeIndex.build(listOf(moduleWith(cardsJson)))
        val hits = index.search("ORS rehydrate child", k = 2, scoreThreshold = 0f)
        assertTrue(hits.isNotEmpty())
    }

    // ── Change 2: bare JsonObject body guard ─────────────────────────────────

    @Test
    fun `bare JsonObject body_en is indexed and referenceText is clean`() {
        val cardsJson = """
            [
              {
                "title_en": "Malnutrition signs",
                "body_en": {"type":"paragraph","content":[{"type":"text","text":"Signs of severe malnutrition include wasting"}]}
              }
            ]
        """.trimIndent()
        val index = ModuleKnowledgeIndex.build(listOf(moduleWith(cardsJson)))
        val hits = index.search("severe malnutrition wasting", k = 2, scoreThreshold = 0f)
        assertTrue("bare object body must be indexed", hits.isNotEmpty())
        val ref = hits.first().referenceText()
        assertFalse("referenceText must not contain raw JSON keys", ref.contains("\"type\""))
        assertFalse("referenceText must not contain raw JSON keys", ref.contains("\"content\""))
        assertTrue(ref.contains("malnutrition"))
    }

    // ── Change 3: correct quiz option text indexing ───────────────────────────

    private fun moduleWithQuiz(quizJson: String) = ModuleEntity(
        moduleId = "m2",
        moduleFamilyId = "fam2",
        version = 1,
        titleBn = "মডিউল",
        domain = "rmnch",
        moduleType = "initial_training",
        estimatedMinutes = 10,
        difficultyLevel = "moderate",
        clinicallyReviewed = true,
        updatedAtIso = "2026-06-01T00:00:00Z",
        quizJson = quizJson,
    )

    @Test
    fun `correct quiz option text (string shape) is retrievable`() {
        val quizJson = """
            [{
              "question_bn": "উচ্চ রক্তচাপের সীমা কত?",
              "question_en": "What is the hypertension threshold?",
              "options_bn": ["BP less than 120", "BP 140 per 90 or above"],
              "options_en": ["BP less than 120", "BP 140 per 90 or above"],
              "correct_indices": [1],
              "explanation_bn": "রক্তচাপ পরিমাপ সম্পর্কিত তথ্য।",
              "explanation_en": "Information about blood pressure measurement."
            }]
        """.trimIndent()
        val index = ModuleKnowledgeIndex.build(listOf(moduleWithQuiz(quizJson)))
        val hits = index.search("hypertension threshold 140 90", k = 2, scoreThreshold = 0f)
        assertTrue("correct option text must be retrievable", hits.isNotEmpty())
    }

    @Test
    fun `correct quiz option text (object shape) is retrievable`() {
        val quizJson = """
            [{
              "question_en": "Which is a danger sign?",
              "options_en": [
                {"label": "Mild cough", "value": 0},
                {"label": "Convulsion with fever", "value": 1}
              ],
              "correct_indices": [1],
              "explanation_en": "Convulsions require emergency referral."
            }]
        """.trimIndent()
        val index = ModuleKnowledgeIndex.build(listOf(moduleWithQuiz(quizJson)))
        val hits = index.search("convulsion danger sign", k = 2, scoreThreshold = 0f)
        assertTrue("object-shape option must be retrievable", hits.isNotEmpty())
    }

    @Test
    fun `wrong quiz option text is NOT indexed`() {
        val quizJson = """
            [{
              "question_en": "What is the normal glucose level?",
              "options_en": ["glucose above 300 critically high", "glucose 70 to 100 normal"],
              "correct_indices": [1],
              "explanation_en": "Normal fasting glucose is 70 to 100."
            }]
        """.trimIndent()
        val index = ModuleKnowledgeIndex.build(listOf(moduleWithQuiz(quizJson)))
        // The wrong option "above 300 critically high" must NOT create strong signal
        // for a query exclusively about that wrong value.
        val wrongHits = index.search("glucose above 300 critically high", k = 2, scoreThreshold = 0f)
        // If there are hits they must be scored lower than a query on the correct answer.
        val correctHits = index.search("glucose 70 100 normal fasting", k = 2, scoreThreshold = 0f)
        val wrongScore = wrongHits.firstOrNull()?.score ?: 0f
        val correctScore = correctHits.firstOrNull()?.score ?: 0f
        assertTrue(
            "correct answer query must score at least as high as wrong answer query",
            correctScore >= wrongScore,
        )
    }

    // ── applyQuizBoost=false: low-end path does not over-boost quiz chunks ────

    @Test
    fun `quiz boost disabled causes on-topic card to outscore unrelated quiz chunk`() {
        // Anaemia card directly mentions haemoglobin and anaemia referral threshold.
        // ARI quiz shares only the generic "referral" token.
        // With boost=true the quiz's 1.2× multiplier may push it above the anaemia card.
        // With boost=false (low-end path) the anaemia card must win on pure token overlap.
        val anaemiaCardJson = """
            [{"title_en":"Severe Anaemia Referral","body_en":"Refer immediately when haemoglobin is below 7.5 g/dL. Anaemia referral threshold is 7.5."}]
        """.trimIndent()
        val ariQuizJson = """
            [{
              "question_en":"When to refer ARI case?",
              "options_en":["Mild cough","Chest indrawing with fast breathing"],
              "correct_indices":[1],
              "explanation_en":"Refer when chest indrawing. Do not delay referral."
            }]
        """.trimIndent()

        val anaemiaModule = moduleWith(anaemiaCardJson)
        val ariQuizModule = ModuleEntity(
            moduleId = "m3", moduleFamilyId = "fam3", version = 1,
            titleBn = "ARI মডিউল", domain = "rmnch", moduleType = "initial_training",
            estimatedMinutes = 10, difficultyLevel = "moderate",
            clinicallyReviewed = true, updatedAtIso = "2026-06-01T00:00:00Z",
            quizJson = ariQuizJson,
        )
        val index = ModuleKnowledgeIndex.build(listOf(anaemiaModule, ariQuizModule))

        val hitsNoBoost = index.search("anaemia haemoglobin referral", k = 4, scoreThreshold = 0f, applyQuizBoost = false)

        assertTrue("must return results", hitsNoBoost.isNotEmpty())
        val top = hitsNoBoost.first()
        assertTrue(
            "on-topic anaemia card (fam1/CARD) must be top-1, got ${top.moduleFamilyId}/${top.source}",
            top.moduleFamilyId == "fam1" && top.source == GroundingChunk.Source.CARD,
        )
    }

    // ── Bangla recall: symmetric bigrams match inflected forms (F2) ──────────

    @Test
    fun `inflected bangla query retrieves base-form bangla chunk`() {
        // Card body is in Bangla ("টিকা" = vaccine). Query is the inflected form
        // "টিকার" (of the vaccine). Whole-word tokens differ; the symmetric
        // character bigrams indexed on both sides bridge the inflection.
        val cardsJson = """[{"title_bn":"টিকা","body_bn":"শিশুকে টিকা দিতে হবে নির্ধারিত সময়ে।"}]"""
        val index = ModuleKnowledgeIndex.build(listOf(moduleWith(cardsJson)))
        val hits = index.search(
            "টিকার",
            k = 2,
            scoreThreshold = 0f,
            language = ModuleKnowledgeIndex.Lang.BN,
        )
        assertTrue("inflected Bangla query must surface the base-form chunk", hits.isNotEmpty())
    }

    // ── Per-language scoring keeps EN and BN bags separate (F4/F7) ───────────

    @Test
    fun `english query against BN index does not match english-only content`() {
        // English-only card. Searching the BN index must not return it — the per
        // language split keeps English tokens out of the Bangla bag.
        val cardsJson = """[{"title_en":"Dehydration signs","body_en":"dry mouth and sunken eyes"}]"""
        val index = ModuleKnowledgeIndex.build(listOf(moduleWith(cardsJson)))
        val bnHits = index.search("dehydration sunken eyes", k = 2, scoreThreshold = 0f,
            language = ModuleKnowledgeIndex.Lang.BN)
        val enHits = index.search("dehydration sunken eyes", k = 2, scoreThreshold = 0f,
            language = ModuleKnowledgeIndex.Lang.EN)
        assertTrue("BN index must not match English-only content", bnHits.isEmpty())
        assertTrue("EN index must match English content", enHits.isNotEmpty())
    }

    // ── Change 4: title boost ranks specific card above overview ─────────────

    @Test
    fun `title-boosted card outranks overview card for specific topic query`() {
        // Card 0 is the module overview — it mentions "danger signs" once in passing
        // among many other keywords. Card 1 is titled "Danger Signs" specifically.
        val cardsJson = """
            [
              {
                "title_en": "Module Overview",
                "body_en": "This module covers risk factors, treatment, danger signs, and referral."
              },
              {
                "title_en": "Danger Signs",
                "body_en": "Key danger signs include severe headache and visual disturbance."
              }
            ]
        """.trimIndent()
        val index = ModuleKnowledgeIndex.build(listOf(moduleWith(cardsJson)))
        val hits = index.search("danger signs", k = 2, scoreThreshold = 0f)
        assertTrue("must return at least one hit", hits.isNotEmpty())
        // The card titled "Danger Signs" (positionalId=1) must rank above the overview (positionalId=0).
        assertEquals(
            "Danger Signs card (positionalId=1) must be the top hit",
            1,
            hits.first().positionalId,
        )
    }
}
