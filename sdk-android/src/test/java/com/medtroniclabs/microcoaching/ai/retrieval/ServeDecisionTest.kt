package com.medtroniclabs.microcoaching.ai.retrieval

import com.medtroniclabs.microcoaching.ServeTuning
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for [ServeDecision]: what counts as evidence, what vetoes a hit, and
 * which hit wins when several are servable.
 */
class ServeDecisionTest {

    private val gazetteer = setOf(
        "blood pressure", "রক্তচাপ", "diarrhoea", "ডায়রিয়া", "malaria", "ম্যালেরিয়া",
        "hypertension", "জরায়ু", "ইডিমা", "এডিমা", "টিকা", "রেফার", "নবজাতক", "বিপদ",
        "বিপদচিহ্ন", "স্তন", "লবণ",
    )

    /** Floors zeroed so structural tests are independent of score calibration. */
    private val tuning = ServeTuning(bnScoreFloor = 0f, enScoreFloor = 0f, promoteRatio = 0.55f)

    private fun chunk(
        title: String,
        body: String,
        score: Float = 100f,
        positionalId: Int = 0,
        family: String = "fam-$title",
        hintsBn: List<String> = emptyList(),
        questionsBn: List<String> = emptyList(),
        denseCos: Float? = null,
    ) = GroundingChunk(
        source = GroundingChunk.Source.CARD,
        moduleFamilyId = family,
        positionalId = positionalId,
        titleEn = null,
        bodyEn = null,
        titleBn = title,
        bodyBn = body,
        score = score,
        hintsBn = hintsBn,
        questionsBn = questionsBn,
        denseCos = denseCos,
    )

    private fun decide(query: String, hits: List<GroundingChunk>, bangla: Boolean = true) =
        ServeDecision.decide(query, hits, gazetteer, tuning, isBanglaTurn = bangla)

    // ── refusal basics ────────────────────────────────────────────────────────

    @Test
    fun `empty hits refuse with NO_HITS`() {
        val d = decide("রক্তচাপ বেশি হলে কি করব", emptyList())
        assertTrue(d is ServeDecision.Decision.Refuse)
        assertEquals(ServeDecision.RefuseReason.NO_HITS, (d as ServeDecision.Decision.Refuse).reason)
    }

    @Test
    fun `BP query matched to BP chunk serves`() {
        val hit = chunk("রক্তচাপ মাপার নিয়ম", "রক্তচাপ মাপতে হবে এবং বেশি হলে রেফার করতে হবে দ্রুত হাসপাতালে")
        val d = decide("রক্তচাপ বেশি হলে কি করব", listOf(hit))
        assertTrue(d is ServeDecision.Decision.Serve)
    }

    @Test
    fun `BP query matched only to a diarrhoea chunk refuses`() {
        val hit = chunk("ডায়রিয়ার ভয়াবহতা", "ডায়রিয়া হলে খাবার স্যালাইন দিতে হবে এবং পানি শূন্যতা দেখতে হবে")
        val d = decide("রক্তচাপ বেশি হলে কি করব", listOf(hit))
        assertTrue(d is ServeDecision.Decision.Refuse)
        assertEquals(ServeDecision.RefuseReason.NO_EVIDENCE, (d as ServeDecision.Decision.Refuse).reason)
    }

    @Test
    fun `relevant hit later in top-k is served instead of refusing`() {
        val wrong = chunk("ডায়রিয়ার ভয়াবহতা", "ডায়রিয়া হলে খাবার স্যালাইন দিতে হবে এবং পানি দেখতে হবে", score = 100f)
        val right = chunk("রক্তচাপ মাপার নিয়ম", "রক্তচাপ বেশি হলে দ্রুত রেফার করতে হবে হাসপাতালে যেতে হবে", score = 70f)
        val d = decide("রক্তচাপ বেশি হলে কি করব", listOf(wrong, right))
        assertTrue(d is ServeDecision.Decision.Serve)
        assertEquals(right.chunkId, (d as ServeDecision.Decision.Serve).hit.chunkId)
    }

    /** Score does not substitute for evidence below the mangled-input rescue band. */
    @Test
    fun `zero-evidence hit refuses at any score below the rescue band`() {
        val hit = chunk(
            "ডায়রিয়ার ভয়াবহতা",
            "ডায়রিয়া হলে খাবার স্যালাইন দিতে হবে এবং পানি শূন্যতা দেখতে হবে",
            score = 240f,
        )
        val d = decide("রক্তচাপ বেশি হলে কি করব", listOf(hit))
        assertTrue(d is ServeDecision.Decision.Refuse)
    }

    // ── demographic / template / numeric words are not evidence ──────────────

    @Test
    fun `card sharing only demographic words refuses`() {
        // Both texts are about pregnant women; only one is about food.
        val hit = chunk("রক্তস্বল্পতার মাত্রা", "গর্ভবতী মায়ের রক্তস্বল্পতা হলে আয়রন বড়ি খেতে হবে প্রতিদিন নিয়ম করে")
        val d = decide("গর্ভবতী মা প্রতিদিন কি খাবেন", listOf(hit))
        assertTrue(d is ServeDecision.Decision.Refuse)
    }

    @Test
    fun `template-only overlap refuses - symptoms question vs another disease symptom card`() {
        // "লক্ষণ" (symptoms) is shared by every symptom card, whatever the disease.
        val hit = chunk("ডায়রিয়ার লক্ষণ", "ডায়রিয়ার লক্ষণগুলো হলো বার বার পাতলা পায়খানা হওয়া এবং পানি পিপাসা")
        val d = decide("হাইপোগ্লাইসেমিয়ার লক্ষণ সমুহ কি কি", listOf(hit))
        assertTrue(d is ServeDecision.Decision.Refuse)
    }

    @Test
    fun `template-only overlap refuses - prevention question vs another disease prevention card`() {
        // "প্রতিরোধে করণীয়" (what to do to prevent) is shared by every prevention card.
        val hit = chunk("ডেঙ্গু প্রতিরোধে করণীয়", "ডেঙ্গু প্রতিরোধে বাড়ির চারপাশ পরিষ্কার রাখুন এবং জমা পানি ফেলে দিন")
        val d = decide("হাইপোগ্লাইসেমিয়া প্রতিরোধে করণীয় কি কি", listOf(hit))
        assertTrue(d is ServeDecision.Decision.Refuse)
    }

    @Test
    fun `numbers and units are not topical evidence`() {
        // A shared unit says both texts mention a lab value, not that they share a topic.
        val hit = chunk(
            "পরীক্ষার ফলাফল লেখা",
            "গ্লুকোজ নির্ণয় করা হয়ে থাকলে ফলাফল লিখতে হবে যেমন 7.0 মিলিমোল লিটার হিসেবে",
        )
        val d = decide("রক্তে গ্লকোজের পরিমান ৩.৯ মিলিমোল লিটার এর থেকে কম", listOf(hit))
        assertTrue(d is ServeDecision.Decision.Refuse)
    }

    // ── population-scope veto ─────────────────────────────────────────────────

    @Test
    fun `pregnancy-scoped card is vetoed for a general NCD question`() {
        // Advice written for pregnant mothers does not answer a question about an
        // adult client with no pregnancy mentioned.
        val hit = chunk("গর্ভকালীন রক্তচাপ: অস্বাভাবিক হলে করণীয়", "গর্ভবতী মায়ের রক্তচাপ অস্বাভাবিক হলে রেফার করুন এবং পরামর্শ দিন")
        val d = decide("সেবাগ্রহীতার রক্তচাপ বেশি পেলে কি করব", listOf(hit))
        assertTrue(d is ServeDecision.Decision.Refuse)
    }

    @Test
    fun `the same pregnancy-scoped card serves when the question is about a pregnant mother`() {
        val hit = chunk("গর্ভকালীন রক্তচাপ: অস্বাভাবিক হলে করণীয়", "গর্ভবতী মায়ের রক্তচাপ অস্বাভাবিক হলে রেফার করুন এবং পরামর্শ দিন")
        val d = decide("গর্ভবতী মায়ের রক্তচাপ বেশি পেলে কি করব", listOf(hit))
        assertTrue(d is ServeDecision.Decision.Serve)
    }

    // ── Bangla compound containment + concept bridge ─────────────────────────

    @Test
    fun `inflected compound matches its base condition term`() {
        // The inflected compound must count as evidence for its base term.
        val hit = chunk("রক্তচাপ ব্যবস্থাপনা", "রক্তচাপ বেশি থাকলে বিশ্রামে থাকতে বলুন এবং দ্রুত হাসপাতালে রেফার করুন")
        val d = decide("উচ্চরক্তচাপের রোগীর জন্য কি করব", listOf(hit))
        assertTrue(d is ServeDecision.Decision.Serve)
    }

    @Test
    fun `synonym concept bridges the MLKit spelling to the corpus spelling`() {
        // MLKit writes এডিমা where the corpus writes ইডিমা; the concept group bridges them.
        val hit = chunk("ইডিমা হলে পরামর্শ", "ইডিমা থাকলে পাতে আলগা লবণ খাওয়া যাবে না এবং পা উঁচু করে রাখতে হবে")
        val d = decide("মায়ের পা ফোলা এডিমা হয়েছে কি করব", listOf(hit))
        assertTrue(d is ServeDecision.Decision.Serve)
    }

    // ── title/hint evidence beats body keyword density ───────────────────────

    @Test
    fun `exact-topic title at a lower rank beats an incidental body mention`() {
        // One card is titled for the topic; the other only mentions it in passing.
        val incidental = chunk(
            "মায়ের দুধের উপকারিতা",
            "মায়ের জরায়ু তাড়াতাড়ি আগের অবস্থায় ফিরে আসে এবং রক্ত ক্ষরণ কমায় এবং সময় বাঁচে",
            score = 161f,
        )
        val exact = chunk(
            "মায়ের জরায়ুর উচ্চতা হ্রাস",
            "জরায়ুর উচ্চতা প্রতিদিন কমতে থাকবে এবং বিয়াল্লিশ দিনের মাথায় পূর্বের অবস্থায় ফেরত যাবে",
            score = 144f,
        )
        val d = decide("প্রসবের পর মায়ের জরায়ু সংকুচিত হয়ে আগের অবস্থায় ফিরে আসছে কিনা কীভাবে বুঝবেন", listOf(incidental, exact))
        assertTrue(d is ServeDecision.Decision.Serve)
        assertEquals(exact.chunkId, (d as ServeDecision.Decision.Serve).hit.chunkId)
    }

    @Test
    fun `authored question hints count as title-tier evidence`() {
        val plain = chunk(
            "নবজাতকের যত্ন",
            "নবজাতকের বিপদচিহ্ন দেখা দিলে দ্রুত রেফার করতে হবে হাসপাতালে নিয়ে যেতে হবে",
            score = 100f,
        )
        val hinted = chunk(
            "নবজাতকের বিপদচিহ্নসমূহ",
            "নবজাতকের বিপদচিহ্ন দেখা দিলে দ্রুত রেফার করতে হবে হাসপাতালে নিয়ে যেতে হবে",
            score = 80f,
            questionsBn = listOf("নবজাতকের বিপদজনক লক্ষণ কি কি"),
        )
        val d = decide("নবজাতকের বিপদজনক লক্ষণ কি কি এবং কখন রেফার করতে হবে", listOf(plain, hinted))
        assertTrue(d is ServeDecision.Decision.Serve)
        assertEquals(hinted.chunkId, (d as ServeDecision.Decision.Serve).hit.chunkId)
    }

    // ── referral timing + stub demotion ──────────────────────────────────────

    @Test
    fun `referral timing question promotes the when-to-refer card`() {
        val services = chunk(
            "টিকা সেবা তালিকা",
            "Services at the clinic include টিকা and growth monitoring for children every week",
            score = 100f,
        )
        val whenToRefer = chunk(
            "কখন রেফার করতে হবে",
            "When to refer: danger sign দেখা দিলে দ্রুত রেফার করতে হবে বিপদ চিহ্ন থাকলে",
            score = 75f,
        )
        val d = decide("শিশুর টিকা নিয়ে কখন রেফার করতে হবে", listOf(services, whenToRefer))
        assertTrue(d is ServeDecision.Decision.Serve)
        assertEquals(whenToRefer.chunkId, (d as ServeDecision.Decision.Serve).hit.chunkId)
    }

    @Test
    fun `stub top hit loses to a substantive sibling with the same evidence`() {
        val stub = chunk("রক্তচাপ মাপা:", "রক্তচাপ মাপার নিয়ম:", score = 100f)
        val full = chunk(
            "রক্তচাপ মাপার নিয়ম",
            "রক্তচাপ মাপতে হবে রোগীকে বসিয়ে এবং বেশি হলে চার ঘন্টা পরে আবার মাপতে হবে তারপর রেফার",
            score = 70f,
        )
        val d = decide("রক্তচাপ কীভাবে মাপব", listOf(stub, full))
        assertTrue(d is ServeDecision.Decision.Serve)
        assertEquals(full.chunkId, (d as ServeDecision.Decision.Serve).hit.chunkId)
    }

    // ── floors ────────────────────────────────────────────────────────────────

    @Test
    fun `evidence below the per-language score floor refuses`() {
        val hit = chunk("রক্তচাপ মাপার নিয়ম", "রক্তচাপ বেশি হলে দ্রুত রেফার করতে হবে হাসপাতালে", score = 10f)
        val d = ServeDecision.decide(
            "রক্তচাপ বেশি হলে কি করব",
            listOf(hit),
            gazetteer,
            ServeTuning(bnScoreFloor = 25f, enScoreFloor = 40f),
            isBanglaTurn = true,
        )
        assertTrue(d is ServeDecision.Decision.Refuse)
        assertEquals(ServeDecision.RefuseReason.BELOW_SCORE_FLOOR, (d as ServeDecision.Decision.Refuse).reason)
    }

    // ── mangled-input rescue ──────────────────────────────────────────────────

    @Test
    fun `mangled query with an extreme bigram score serves rank-1 despite zero word evidence`() {
        // Garbled text matches no word, but the character-bigram channel still scores
        // the card whose words were mangled far above any unrelated card.
        val hit = chunk(
            "কনডোম ও খাবার বড়ি কোথায় পাওয়া যায়",
            "কমিউনিটি ক্লিনিক ও ফার্মেসিতে কনডোম ও খাবার বড়ি পাওয়া যায়।",
            score = 362.58f,
        )
        val d = decide("কনডর্ ও খাবাি বয়ি রকাথাি পাওিা যাি", listOf(hit))
        assertTrue(d is ServeDecision.Decision.Serve)
    }

    @Test
    fun `zero word evidence below the rescue band still refuses`() {
        // A topic mismatch can score highly on shared vocabulary; the rescue band sits
        // above anything such a mismatch reaches.
        val hit = chunk(
            "ডট পদ্ধতিতে চিকিৎসার তথ্য সংরক্ষণ",
            "রোগী নিয়মমাফিক খাওয়ার পর কার্ডের নির্দিষ্ট তারিখের ঘরে টিক চিহ্ন দেবেন",
            score = 189f,
        )
        val d = decide("সেবাগ্রহীতার উচ্চরক্তচাপ নিয়ে ডাক্তার দেখাতে রাজি করাবো কীভাবে", listOf(hit))
        assertTrue(d is ServeDecision.Decision.Refuse)
    }

    /** A question with no topical content has nothing to match, so nothing is served. */
    @Test
    fun `query with only demographic words refuses instead of serving rank-1`() {
        val hit = chunk("রক্তস্বল্পতার মাত্রা", "গর্ভবতী মায়ের রক্তস্বল্পতা হলে আয়রন বড়ি খেতে হবে")
        val d = decide("গর্ভবতী মায়ের জন্য", listOf(hit))
        assertTrue(d is ServeDecision.Decision.Refuse)
    }

    // ── dense (embedding) evidence channel ───────────────────────────────────

    @Test
    fun `dense agreement makes a zero-lexical-overlap hit servable`() {
        val hit = chunk(
            "স্তনের যত্ন",
            "প্রতিদিন স্তন পরিষ্কার করতে হবে এবং বোঁটা ভিতরের দিকে থাকলে তেল দিয়ে মালিশ করতে হবে",
            denseCos = 0.60f,
        )
        val d = decide("মায়ের দুধ কম আসছে কি করব", listOf(hit))
        assertTrue(d is ServeDecision.Decision.Serve)
    }

    @Test
    fun `dense similarity below the floor with no lexical evidence still refuses`() {
        val hit = chunk(
            "স্তনের যত্ন",
            "প্রতিদিন স্তন পরিষ্কার করতে হবে এবং বোঁটা ভিতরের দিকে থাকলে তেল দিয়ে মালিশ করতে হবে",
            denseCos = 0.30f,
        )
        val d = decide("মায়ের দুধ কম আসছে কি করব", listOf(hit))
        assertTrue(d is ServeDecision.Decision.Refuse)
        assertEquals(ServeDecision.RefuseReason.NO_EVIDENCE, (d as ServeDecision.Decision.Refuse).reason)
    }

    @Test
    fun `population veto beats dense agreement`() {
        val hit = chunk(
            "গর্ভকালীন রক্তচাপ: অস্বাভাবিক হলে করণীয়",
            "গর্ভবতী মায়ের রক্তচাপ অস্বাভাবিক হলে রেফার করুন এবং পরামর্শ দিন",
            denseCos = 0.90f,
        )
        val d = decide("সেবাগ্রহীতার রক্তচাপ বেশি পেলে কি করব", listOf(hit))
        assertTrue(d is ServeDecision.Decision.Refuse)
    }

    @Test
    fun `dense-only entrant with zero bm25 score passes the score floor`() {
        val hit = chunk(
            "রক্তচাপ মাপার নিয়ম",
            "রক্তচাপ বেশি হলে দ্রুত রেফার করতে হবে হাসপাতালে",
            score = 0f,
            denseCos = 0.60f,
        )
        val d = ServeDecision.decide(
            "রক্তচাপ বেশি হলে কি করব",
            listOf(hit),
            gazetteer,
            ServeTuning(bnScoreFloor = 25f, enScoreFloor = 40f),
            isBanglaTurn = true,
        )
        assertTrue(d is ServeDecision.Decision.Serve)
    }
}
