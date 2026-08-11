package com.medtroniclabs.microcoaching.ai.retrieval

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OffTopicGuardTest {

    private val gazetteer = setOf(
        "hypertension", "blood pressure", "bp",
        "diarrhoea", "loose stool",
        "anaemia", "haemoglobin",
        "ডায়রিয়া", "রক্তচাপ", "বুক",
    )

    private fun chunk(title: String? = null, body: String? = null) = GroundingChunk(
        source = GroundingChunk.Source.CARD,
        moduleFamilyId = "fam1",
        positionalId = 0,
        titleEn = title,
        bodyEn = body,
        titleBn = null,
        bodyBn = null,
        score = 1f,
    )

    @Test
    fun `null top hit is unanswerable`() {
        assertTrue(OffTopicGuard.isClearlyUnanswerable("anything", emptyList(), gazetteer))
    }

    @Test
    fun `BP query matched to BP chunk passes`() {
        val top = chunk(title = "Hypertension threshold", body = "BP above 140/90 is high.")
        assertFalse(OffTopicGuard.isClearlyUnanswerable("low BP 90/60", listOf(top), gazetteer))
    }

    @Test
    fun `BP query matched to diarrhoea chunk is unanswerable`() {
        // The diarrhoea-bleed scenario: BM25 returned a diarrhoea card for a BP
        // question. The guard must catch this even if the score was high.
        val top = chunk(title = "Diarrhoea treatment", body = "ORS for loose stool and dehydration.")
        assertTrue(OffTopicGuard.isClearlyUnanswerable("low BP 90/60", listOf(top), gazetteer))
    }

    @Test
    fun `query with no clinical tokens passes through (L1 handles it)`() {
        // L1 is the right gate for "weather today" — this guard must not double-refuse.
        val top = chunk(title = "Hypertension", body = "BP")
        assertFalse(OffTopicGuard.isClearlyUnanswerable("weather today", listOf(top), gazetteer))
    }

    @Test
    fun `bangla query matched to bangla chunk passes`() {
        val top = GroundingChunk(
            source = GroundingChunk.Source.CARD,
            moduleFamilyId = "fam1", positionalId = 0,
            titleEn = null, bodyEn = null,
            titleBn = "ডায়রিয়ার চিকিৎসা",
            bodyBn = "শিশুকে ORS দিন।",
            score = 1f,
        )
        assertFalse(OffTopicGuard.isClearlyUnanswerable("ডায়রিয়া হলে কী করব", listOf(top), gazetteer))
    }

    @Test
    fun `relevant hit later in top-k avoids false refusal`() {
        val wrongTop = chunk(title = "Diarrhoea treatment", body = "ORS for loose stool and dehydration.")
        val correctLater = chunk(title = "Hypertension threshold", body = "BP above 140/90 is high.")
            .copy(positionalId = 1, score = 0.7f)
        assertFalse(
            OffTopicGuard.isClearlyUnanswerable(
                "low BP 90/60",
                listOf(wrongTop, correctLater),
                gazetteer,
            ),
        )
    }

    @Test
    fun `weak clinically overlapping hit is not safe for fallback serve`() {
        val weak = chunk(title = "Hypertension threshold", body = "BP above 140/90 is high.")
            .copy(score = 17.1f)
        assertTrue(
            OffTopicGuard.bestFallbackHit(
                query = "low BP 90/60",
                hits = listOf(weak),
                clinicalTerms = gazetteer,
                minScore = 20f,
            ) == null,
        )
    }

    @Test
    fun `strong clinically overlapping hit is safe for fallback serve`() {
        val strong = chunk(title = "Hypertension threshold", body = "BP above 140/90 is high.")
            .copy(score = 26f)
        assertTrue(
            OffTopicGuard.bestFallbackHit(
                query = "low BP 90/60",
                hits = listOf(strong),
                clinicalTerms = gazetteer,
                minScore = 20f,
            ) != null,
        )
    }

    @Test
    fun `confident top hit bypasses low-end refusal despite zero overlap`() {
        val top = chunk(
            title = "Family Planning Methods Suitable for Couples with One Child",
            body = "IUD and implants are suitable methods.",
        ).copy(score = 505.9f)
        assertTrue(OffTopicGuard.hasConfidentTopHit(listOf(top)))
        assertFalse(
            OffTopicGuard.shouldRefuseLowEnd(
                query = "garbled bn query with no gazetteer overlap",
                hits = listOf(top),
                clinicalTerms = gazetteer,
            ),
        )
    }

    @Test
    fun `weak top hit still refuses when clinically unanswerable`() {
        val top = chunk(title = "Diarrhoea treatment", body = "ORS for loose stool and dehydration.")
            .copy(score = 17.1f)
        assertFalse(OffTopicGuard.hasConfidentTopHit(listOf(top)))
        assertTrue(
            OffTopicGuard.shouldRefuseLowEnd(
                query = "low BP 90/60",
                hits = listOf(top),
                clinicalTerms = gazetteer,
            ),
        )
    }

    @Test
    fun `referral timing question promotes when-to-refer card over uhc services list`() {
        val servicesAtUhc = chunk(
            title = "PPFP Services at Upazila Health Complex (UHC)",
            body = "Counselling and IUD insertion at UHC.",
        ).copy(moduleFamilyId = "fam-ppfp", positionalId = 2, score = 511.6f)
        val whenToRefer = chunk(
            title = "When to Refer for Maternal Danger Signs",
            body = "Refer immediately for bleeding, convulsions, and severe headache.",
        ).copy(moduleFamilyId = "fam-danger", positionalId = 3, score = 400.7f)
        val query =
            "কখন দ্রুত উপজেলা স্বাস্থ্য কমপ্লেক্সে রেফার করা উচিত? " +
                "When should you refer to upazila health complexes quickly?"
        val selected = OffTopicGuard.selectLowEndServeHit(
            query = query,
            hits = listOf(servicesAtUhc, whenToRefer),
            clinicalTerms = gazetteer,
        )
        assertEquals("When to Refer for Maternal Danger Signs", selected?.titleEn)
    }

    @Test
    fun `stub top hit promotes substantive sibling within score band`() {
        val stubTop = GroundingChunk(
            source = GroundingChunk.Source.CARD,
            moduleFamilyId = "fam-bp",
            positionalId = 3,
            titleEn = "Birth Preparedness for Pregnant Women and Their Families",
            bodyEn = "Birth preparedness heading only:",
            titleBn = "প্রসব প্রস্তুতি",
            bodyBn = "প্রসব প্রস্তুতি শিরোনাম:",
            score = 86.5f,
        )
        val substantive = GroundingChunk(
            source = GroundingChunk.Source.CARD,
            moduleFamilyId = "fam-bp-2",
            positionalId = 2,
            titleEn = "Birth Preparedness for Pregnant Women and Families",
            bodyEn = "Plan transport, save money, identify blood donors, and arrange skilled birth attendance.",
            titleBn = "প্রসব প্রস্তুতি",
            bodyBn = "যানবাহন, অর্থ, রক্তদাতা এবং দক্ষ প্রসবকারীর ব্যবস্থা করুন।",
            score = 52.8f,
        )
        val selected = OffTopicGuard.selectLowEndServeHit(
            query = "Birth Preparedness for Pregnant Women management steps",
            hits = listOf(stubTop, substantive),
            clinicalTerms = gazetteer,
        )
        assertEquals("fam-bp-2", selected?.moduleFamilyId)
    }

    @Test
    fun `definition question keeps bm25 rank1 diabetes card over diagnostic sibling`() {
        val definition = GroundingChunk(
            source = GroundingChunk.Source.CARD,
            moduleFamilyId = "e834f287",
            positionalId = 0,
            titleEn = "What is Diabetes?",
            bodyEn = "Diabetes is when blood glucose is higher than normal.",
            titleBn = "ডায়াবেটিস কী",
            bodyBn = "ডায়াবেটিস হলো রক্তে গ্লুকোজ স্বাভাবিকের চেয়ে বেশি হওয়া।",
            score = 253.15f,
        )
        val diagnostic = GroundingChunk(
            source = GroundingChunk.Source.CARD,
            moduleFamilyId = "e834f287",
            positionalId = 1,
            titleEn = "Diabetes Diagnostic Blood Glucose Levels",
            bodyEn = "Fasting = 7.0 mmol/L or more. Random = 11.1 mmol/L or more.",
            titleBn = "ডায়াবেটিস নির্ণয়ের রক্তের গ্লুকোজ মাত্রা",
            bodyBn = "খালি পেটে ৭.০ মিলিমোল/লিটার বা তার বেশি।",
            score = 221.11f,
        )
        val selected = OffTopicGuard.selectLowEndServeHit(
            query = "ডায়াবেটিস কী? সম্পর্কে বলুন।",
            hits = listOf(definition, diagnostic),
            clinicalTerms = gazetteer + setOf("diabetes", "ডায়াবেটিস", "glucose"),
        )
        assertEquals(0, selected?.positionalId)
        assertEquals("What is Diabetes?", selected?.titleEn)
    }

    @Test
    fun `definition question keeps bm25 rank1 hypertension card over advice sibling`() {
        val definition = GroundingChunk(
            source = GroundingChunk.Source.CARD,
            moduleFamilyId = "24a4f192",
            positionalId = 0,
            titleEn = "What is Hypertension?",
            bodyEn = "Hypertension is blood pressure higher than normal.",
            titleBn = "উচ্চ রক্তচাপ কী",
            bodyBn = "স্বাভাবিকের চেয়ে অতিরিক্ত রক্তচাপকে উচ্চ রক্তচাপ বলে।",
            score = 326.17f,
        )
        val advice = GroundingChunk(
            source = GroundingChunk.Source.CARD,
            moduleFamilyId = "24a4f192",
            positionalId = 3,
            titleEn = "Advice and Prevention for Hypertension",
            bodyEn = "Take medicine daily, exercise, avoid salt and smoking.",
            titleBn = "উচ্চ রক্তচাপের পরামর্শ ও প্রতিরোধ",
            bodyBn = "প্রতিদিন ঔষধ খান, ব্যায়াম করুন, লবণ ও ধূমপান এড়িয়ে চলুন।",
            score = 290.57f,
        )
        val selected = OffTopicGuard.selectLowEndServeHit(
            query = "উচ্চ রক্তচাপ কী? সম্পর্কে বলুন।",
            hits = listOf(definition, advice),
            clinicalTerms = gazetteer + setOf("hypertension", "রক্তচাপ", "blood pressure"),
        )
        assertEquals(0, selected?.positionalId)
        assertEquals("What is Hypertension?", selected?.titleEn)
    }
}
