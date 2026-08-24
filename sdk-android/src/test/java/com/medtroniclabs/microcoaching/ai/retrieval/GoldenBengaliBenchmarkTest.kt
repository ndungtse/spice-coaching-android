package com.medtroniclabs.microcoaching.ai.retrieval

import com.medtroniclabs.microcoaching.data.db.entity.ModuleEntity
import com.medtroniclabs.microcoaching.data.db.entity.moduleEntityFixture
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.floatOrNull
import kotlinx.serialization.json.jsonArray
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.util.Locale

/**
 * Bengali-mode retrieval benchmark over a real on-device corpus and a labelled question
 * set. This is the Bangla-mode counterpart to [RealCorpusProbeTest]: no translation is
 * involved, so it measures the retrieval ceiling the English path can never exceed.
 *
 * Not an assertion test — it prints a report. Skipped unless both inputs are supplied:
 *
 *   MC_REAL_CORPUS=corpus.json MC_QUESTION_SET=questions.json \
 *     ./gradlew :sdk-android:testDebugUnitTest --tests '*GoldenBengaliBenchmarkTest*' -i
 *
 * Question set: JSON array of {id, question_bn, expected ("<family8>:<cardIndex>"),
 * label_overlap, label_margin}. `expected` is the card whose body answers the question;
 * `label_margin` is how much better it matched than the runner-up, so weakly-labelled
 * questions can be reported separately rather than silently counted.
 */
class GoldenBengaliBenchmarkTest {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    private data class Q(
        val id: String,
        val questionBn: String,
        val expected: String,
        val labelMargin: Float,
    )

    /**
     * Colloquial Bengali a CHW types, mapped to the formal register the curriculum uses.
     * Measured gap: 'প্রেশার' appears in real CHW questions but in zero card bodies and
     * zero authored hints; the corpus only ever says 'রক্তচাপ'. Same for রিস্ক/নরমাল/পেশেন্ট.
     */
    private val banglaColloquial: Map<String, List<String>> = mapOf(
        "প্রেশার" to listOf("রক্তচাপ"),
        "প্রেসার" to listOf("রক্তচাপ"),
        "সুগার" to listOf("শর্করা", "গ্লুকোজ", "ডায়াবেটিস"),
        "রিস্ক" to listOf("ঝুঁকি", "ঝুঁকিপূর্ণ"),
        "রিস্কি" to listOf("ঝুঁকিপূর্ণ"),
        "নরমাল" to listOf("স্বাভাবিক"),
        "পেশেন্ট" to listOf("রোগী"),
        "টেস্ট" to listOf("পরীক্ষা"),
        "রিপোর্ট" to listOf("ফলাফল"),
        "ডেলিভারি" to listOf("প্রসব"),
        "চেকআপ" to listOf("পরিচর্যা", "ভিজিট", "পরীক্ষা"),
        "ইনজেকশন" to listOf("টিকা", "ইঞ্জেকশন"),
        "পালস" to listOf("নাড়ি", "নাড়ির"),
        "ওয়েট" to listOf("ওজন"),
        "ব্লিডিং" to listOf("রক্তক্ষরণ", "রক্তপাত"),
    )

    /** Append formal-register equivalents for any colloquial word the query uses. */
    private fun expandColloquial(query: String): String {
        val extra = BanglaTokenizer.tokenizeQuery(query)
            .flatMap { banglaColloquial[it] ?: emptyList() }
            .distinct()
        return if (extra.isEmpty()) query else query + " " + extra.joinToString(" ")
    }

    @Test
    fun `bengali golden benchmark`() {
        val corpusPath = System.getenv("MC_REAL_CORPUS")
        val questionPath = System.getenv("MC_QUESTION_SET")
        assumeTrue("MC_REAL_CORPUS / MC_QUESTION_SET not set", corpusPath != null && questionPath != null)

        val modules = loadModules(File(corpusPath!!))
        val index = ModuleKnowledgeIndex.build(modules)
        val scope = ScopeClassifier.buildFrom(modules)
        val bank = QuestionBank(modules)
        val questions = loadQuestions(File(questionPath!!))

        println("\n============ BENGALI GOLDEN BENCHMARK ============")
        println("corpus: ${modules.size} modules / ${index.size} chunks")
        println("questions: ${questions.size} labelled (${questions.count { it.labelMargin >= 0.15f }} unambiguous)")
        println("question bank: ${bank.size} phrases over ${bank.cardCount} cards\n")

        var r1 = 0; var r3 = 0; var r10 = 0; var absent = 0; var refused = 0
        var r1Family = 0; var r1Equivalent = 0
        var r1Strict = 0; var strictTotal = 0
        var bankR1 = 0; var bankCovered = 0
        val correctScores = mutableListOf<Float>()
        val wrongScores = mutableListOf<Float>()
        val correctMargins = mutableListOf<Float>()
        val wrongMargins = mutableListOf<Float>()
        val lines = mutableListOf<String>()

        for (q in questions) {
            val hits = index.search(q.questionBn, k = 10, scoreThreshold = 1.5f, language = ModuleKnowledgeIndex.Lang.BN)
            val rank = hits.indexOfFirst { key(it) == q.expected }
            val top = hits.firstOrNull()
            val margin = if (hits.size > 1 && hits[1].score > 0f) hits[0].score / hits[1].score else Float.NaN
            val wouldRefuse = OffTopicGuard.shouldRefuseLowEnd(q.questionBn, hits.take(3), scope.scopeTerms())
            if (wouldRefuse) refused++

            when {
                rank == 0 -> r1++
                rank in 1..2 -> r3++
                rank in 3..9 -> r10++
                else -> absent++
            }
            if (q.labelMargin >= 0.15f) {
                strictTotal++
                if (rank == 0) r1Strict++
            }
            if (top != null) {
                if (rank == 0) {
                    correctScores += top.score
                    if (!margin.isNaN()) correctMargins += margin
                } else {
                    wrongScores += top.score
                    if (!margin.isNaN()) wrongMargins += margin
                }
            }

            // Duplicate families mean the served card can be a content-identical copy of
            // the labelled one. Counting those as misses would understate accuracy, so
            // credit same-family hits and near-identical bodies separately.
            if (top != null) {
                val expectedChunk = index.search(q.questionBn, k = 400, scoreThreshold = 0f, language = ModuleKnowledgeIndex.Lang.BN)
                    .firstOrNull { key(it) == q.expected }
                if (key(top).substringBefore(':') == q.expected.substringBefore(':')) r1Family++
                if (expectedChunk != null && bodyOverlap(top, expectedChunk) >= 0.6f) r1Equivalent++
            }

            val bankHit = bank.best(q.questionBn)
            if (bankHit != null && bankHit.second >= 20f) {
                bankCovered++
                if (key(bankHit.first) == q.expected) bankR1++
            }

            lines.add(
                "%-6s rank=%-6s top1=%-7s margin=%-5s bank=%-6s expected=%-12s served=%s".format(
                    q.id,
                    if (rank < 0) "absent" else "#${rank + 1}",
                    "%.0f".format(Locale.US, top?.score ?: 0f),
                    if (margin.isNaN()) "n/a" else "%.2f".format(Locale.US, margin),
                    bankHit?.let { if (key(it.first) == q.expected) "RIGHT" else "wrong" } ?: "none",
                    q.expected,
                    top?.let { key(it) } ?: "∅",
                )
            )
        }

        val n = questions.size
        fun pct(v: Int) = "%d/%d (%.0f%%)".format(v, n, 100.0 * v / n)
        println("── PROSE BM25 (production path, Bengali query, no translation)")
        println("   recall@1      : ${pct(r1)}")
        println("   recall@1 same family        : ${pct(r1Family)}")
        println("   recall@1 content-equivalent : ${pct(r1Equivalent)}   (served body ~= labelled body; absorbs duplicate families)")
        println("   recall@3      : ${pct(r1 + r3)}")
        println("   recall@10     : ${pct(r1 + r3 + r10)}")
        println("   not in top-10 : ${pct(absent)}")
        println("   would refuse  : ${pct(refused)}   (OffTopicGuard on the low-end path)")
        if (strictTotal > 0) {
            println("   recall@1 on unambiguously-labelled subset: $r1Strict/$strictTotal " +
                "(%.0f%%)".format(100.0 * r1Strict / strictTotal))
        }

        // Cheap client-side candidate: expand colloquial CHW words to the formal register
        // the curriculum uses, then run the unchanged production search.
        var cR1 = 0; var cR3 = 0; var changed = 0
        for (q in questions) {
            val expanded = expandColloquial(q.questionBn)
            if (expanded != q.questionBn) changed++
            val hits = index.search(expanded, k = 10, scoreThreshold = 1.5f, language = ModuleKnowledgeIndex.Lang.BN)
            val rank = hits.indexOfFirst { key(it) == q.expected }
            if (rank == 0) cR1++
            if (rank in 0..2) cR3++
        }
        println("\n── + BENGALI COLLOQUIAL EXPANSION (candidate client fix)")
        println("   queries actually rewritten : $changed/$n")
        println("   recall@1                   : ${pct(cR1)}   (was ${pct(r1)})")
        println("   recall@3                   : ${pct(cR3)}   (was ${pct(r1 + r3)})")

        println("\n── QUESTION BANK (match against per-card CHW questions)")
        println("   answered (bank score >= 20): ${pct(bankCovered)}")
        println("   recall@1 overall           : ${pct(bankR1)}")
        if (bankCovered > 0) {
            println("   recall@1 where it answered : $bankR1/$bankCovered " +
                "(%.0f%%)".format(100.0 * bankR1 / bankCovered))
        }

        println("\n── CAN WE THRESHOLD? (production top-1)")
        fun stats(label: String, xs: List<Float>) {
            if (xs.isEmpty()) { println("   $label: none"); return }
            val s = xs.sorted()
            println(
                "   %-10s n=%-3d min=%.2f p25=%.2f median=%.2f p75=%.2f max=%.2f".format(
                    Locale.US, label, s.size, s.first(),
                    s[s.size / 4], s[s.size / 2], s[(s.size * 3) / 4], s.last(),
                )
            )
        }
        stats("score OK", correctScores); stats("score BAD", wrongScores)
        stats("margin OK", correctMargins); stats("margin BAD", wrongMargins)
        println("   " + separation("score", correctScores, wrongScores))
        println("   " + separation("margin", correctMargins, wrongMargins))

        // ── Bengali case-suffix stripping ───────────────────────────────────
        // Device turn 6 asked "ম্যালেরিয়ার জীবাণুর নাম কী?" (genitive) and the malaria card
        // fell to rank #9, while the English "what causes malaria" hit it at rank #1. Bangla
        // inflects heavily and there is no Bangla stemmer — only character bigrams, which
        // bridge right and wrong cards equally. This appends a suffix-stripped form of each
        // query word alongside the surface form, the same dual-emit trick EnglishStemmer uses.
        // ── DEFINITION BOOST: does the gated rule help, and how often does it fire? ──
        var fires = 0
        for (q in questions) if (DefinitionQueryBoost.topicOf(q.questionBn) != null) fires++
        println("\n── GATED DEFINITION BOOST")
        println("   gate fires on $fires/$n questions")
        for (on in listOf(false, true)) {
            var r1 = 0; var r3 = 0; var r10 = 0
            for (q in questions) {
                val hits = index.search(q.questionBn, k = 10, scoreThreshold = 1.5f,
                    language = ModuleKnowledgeIndex.Lang.BN, definitionBoost = on)
                val r = hits.indexOfFirst { key(it) == q.expected }
                if (r == 0) r1++
                if (r in 0..2) r3++
                if (r in 0..9) r10++
            }
            var sTot = 0; var sR1 = 0
            for (m in modules) {
                for ((chunk, _, meta) in ModuleCorpusParser.extractCardChunks(m)) {
                    for (phrase in (meta.hintsBn + meta.questionsBn)) {
                        if (phrase.isBlank()) continue
                        sTot++
                        val h = index.search(phrase, k = 1, scoreThreshold = 1.5f,
                            language = ModuleKnowledgeIndex.Lang.BN, definitionBoost = on)
                        if (h.firstOrNull()?.chunkId == chunk.chunkId) sR1++
                    }
                }
            }
            println("   boost=%-5b recall@1=%2d/%d (%2d%%)  @3=%2d (%2d%%)  @10=%2d (%2d%%)  self@1=%d%%".format(
                on, r1, n, 100 * r1 / n, r3, 100 * r3 / n, r10, 100 * r10 / n, 100 * sR1 / sTot))
        }
        println("   definition-shaped questions in the set and what they now serve:")
        for (q in questions) {
            val topic = DefinitionQueryBoost.topicOf(q.questionBn) ?: continue
            val before = index.search(q.questionBn, k = 1, scoreThreshold = 1.5f,
                language = ModuleKnowledgeIndex.Lang.BN, definitionBoost = false).firstOrNull()
            val after = index.search(q.questionBn, k = 1, scoreThreshold = 1.5f,
                language = ModuleKnowledgeIndex.Lang.BN, definitionBoost = true).firstOrNull()
            println("     %-6s topic=%-24s %s -> %s%s".format(
                q.id, topic,
                before?.let { key(it) } ?: "∅", after?.let { key(it) } ?: "∅",
                if (after != null && key(after) == q.expected) "  ✓ correct" else ""))
        }

        // ── FIELD-WEIGHT sweep: does "titled this" deserve more than "mentions this"? ──
        println("\n── FIELD WEIGHT SWEEP (title×body; question/keyword unchanged)")
        println("   title  body   recall@1   recall@3   recall@10   self@1")
        for ((tw, bw) in listOf(
            3.0f to 1.0f, 4.0f to 1.0f, 5.0f to 1.0f, 6.0f to 1.0f, 8.0f to 1.0f,
            3.0f to 0.5f, 3.0f to 0.25f, 5.0f to 0.5f,
        )) {
            val fw = ModuleKnowledgeIndex.FieldWeights(title = tw, body = bw)
            var r1 = 0; var r3 = 0; var r10 = 0
            for (q in questions) {
                val hits = index.search(q.questionBn, k = 10, scoreThreshold = 1.5f,
                    language = ModuleKnowledgeIndex.Lang.BN, fieldWeights = fw)
                val r = hits.indexOfFirst { key(it) == q.expected }
                if (r == 0) r1++
                if (r in 0..2) r3++
                if (r in 0..9) r10++
            }
            var sTot = 0; var sR1 = 0
            for (m in modules) {
                for ((chunk, _, meta) in ModuleCorpusParser.extractCardChunks(m)) {
                    for (phrase in (meta.hintsBn + meta.questionsBn)) {
                        if (phrase.isBlank()) continue
                        sTot++
                        val h = index.search(phrase, k = 1, scoreThreshold = 1.5f,
                            language = ModuleKnowledgeIndex.Lang.BN, fieldWeights = fw)
                        if (h.firstOrNull()?.chunkId == chunk.chunkId) sR1++
                    }
                }
            }
            println("   %-6.1f %-6.2f %2d/%d (%2d%%)  %2d/%d (%2d%%)  %2d/%d (%2d%%)   %d%%".format(
                tw, bw, r1, n, 100 * r1 / n, r3, n, 100 * r3 / n, r10, n, 100 * r10 / n,
                100 * sR1 / sTot))
        }

        // ── Is BODY length penalising the definition card? ──────────────────
        println("\n── LENGTH NORMALISATION on the malaria pair (BM25 b=0.75)")
        run {
            val all = index.search("ম্যালেরিয়া", k = 400, scoreThreshold = 0f,
                language = ModuleKnowledgeIndex.Lang.BN)
            for (id in listOf("97910426:1", "97910426:0")) {
                val chunk = all.firstOrNull { key(it) == id } ?: continue
                val len = index.fieldLength(chunk.chunkId, ModuleKnowledgeIndex.Field.BODY,
                    ModuleKnowledgeIndex.Lang.BN)
                println("   %-12s BODY indexed tokens=%-5s chars=%-5d %s".format(
                    id, len?.toString() ?: "?", (chunk.bodyBn ?: "").length, (chunk.titleBn ?: "").take(30)))
            }
        }

        // What the sweep does to the malaria case the lab surfaced: does down-weighting
        // bigrams actually promote the definition card, or only demote the noise card?
        println("\n── CASE: \"ম্যালেরিয়া কী?\" top-3 by bigram weight")
        for (w in listOf(1.0f, 0.25f, 0.0f)) {
            val hits = index.search("ম্যালেরিয়া কী?", k = 3, scoreThreshold = 1.5f,
                language = ModuleKnowledgeIndex.Lang.BN, charBigramWeight = w)
            val line = hits.joinToString("  |  ") {
                "%.1f %s".format(it.score, (it.titleBn ?: "").take(26))
            }
            println("   w=%.2f  %s".format(w, line))
        }

        // ── CHAR-BIGRAM WEIGHT sweep ────────────────────────────────────────
        // Bigrams bridge Bangla inflection but outnumber real words ~10:1 on a short
        // question. Sweep their weight to see whether the bridge or the noise dominates.
        println("\n── CHAR-BIGRAM WEIGHT SWEEP (1.0 = today)")
        println("   weight   recall@1   recall@3   recall@10   self-retrieval@1")
        for (w in listOf(1.0f, 0.5f, 0.25f, 0.1f, 0.0f)) {
            var r1 = 0; var r3 = 0; var r10 = 0
            for (q in questions) {
                val hits = index.search(q.questionBn, k = 10, scoreThreshold = 1.5f,
                    language = ModuleKnowledgeIndex.Lang.BN, charBigramWeight = w)
                val r = hits.indexOfFirst { key(it) == q.expected }
                if (r == 0) r1++
                if (r in 0..2) r3++
                if (r in 0..9) r10++
            }
            // label-free control: a hint must still retrieve its own card
            var sTot = 0; var sR1 = 0
            for (m in modules) {
                for ((chunk, _, meta) in ModuleCorpusParser.extractCardChunks(m)) {
                    for (phrase in (meta.hintsBn + meta.questionsBn)) {
                        if (phrase.isBlank()) continue
                        sTot++
                        val h = index.search(phrase, k = 1, scoreThreshold = 1.5f,
                            language = ModuleKnowledgeIndex.Lang.BN, charBigramWeight = w)
                        if (h.firstOrNull()?.chunkId == chunk.chunkId) sR1++
                    }
                }
            }
            println("   %-8.2f %2d/%d (%2d%%)  %2d/%d (%2d%%)  %2d/%d (%2d%%)   %d/%d (%d%%)".format(
                w, r1, n, 100 * r1 / n, r3, n, 100 * r3 / n, r10, n, 100 * r10 / n,
                sR1, sTot, 100 * sR1 / sTot))
        }

        // ── LOW-END PATH simulation (what most CHWs actually get) ───────────
        // Mirrors handleRetrievalOnlyMessage exactly: GroundingSelector top-3, then
        // selectLowEndServeHit, then the new topical gate.
        var lRight = 0; var lFalseRefuse = 0; var lStopWrong = 0; var lWrongServed = 0; var lPreRefuse = 0
        for (q in questions) {
            val sel = GroundingSelector.select(
                nativeQuery = q.questionBn,
                englishQuery = q.questionBn + " (en)",
                nativeLanguage = ModuleKnowledgeIndex.Lang.BN,
                index = index,
                k = 3,
                scoreThreshold = 1.5f,
            )
            val grounding = sel.hits
            if (grounding.isEmpty()) { lPreRefuse++; continue }
            if (OffTopicGuard.shouldRefuseLowEnd(q.questionBn, grounding, scope.scopeTerms())) { lPreRefuse++; continue }
            val top = OffTopicGuard.selectLowEndServeHit(q.questionBn, grounding, scope.scopeTerms())
                ?: grounding.first()
            val correct = key(top) == q.expected
            val related = OffTopicGuard.sharesTopicalTerm(q.questionBn, top)
            when {
                correct && related -> lRight++
                correct && !related -> lFalseRefuse++
                !correct && !related -> lStopWrong++
                else -> lWrongServed++
            }
        }
        println("\n── LOW-END PATH with the topical gate (production simulation)")
        println("   refused before the gate (existing guards) : $lPreRefuse")
        println("   correct served                           : $lRight")
        println("   CORRECT WRONGLY REFUSED                  : $lFalseRefuse   <- cost")
        println("   WRONG CARDS STOPPED                      : $lStopWrong   <- benefit")
        println("   wrong cards still served                 : $lWrongServed")
        val lServed = lRight + lWrongServed
        if (lServed > 0) {
            println("   precision of served answers: $lRight/$lServed (%.0f%%)".format(100.0 * lRight / lServed))
            println("   refusal rate: %.0f%% of turns".format(100.0 * (lPreRefuse + lStopWrong + lFalseRefuse) / n))
        }

        // ── TOPICAL GATE impact (the new question↔evidence refusal) ─────────
        // Measures the trade the gate actually makes: how many WRONG cards it stops
        // serving, versus how many RIGHT ones it would wrongly refuse.
        var gStopWrong = 0; var gFalseRefuse = 0; var gKeepRight = 0; var gKeepWrong = 0
        for (q in questions) {
            val hits = index.search(q.questionBn, k = 10, scoreThreshold = 1.5f, language = ModuleKnowledgeIndex.Lang.BN)
            val top = hits.firstOrNull() ?: continue
            val related = OffTopicGuard.sharesTopicalTerm(q.questionBn, top)
            val correct = key(top) == q.expected
            when {
                correct && related -> gKeepRight++
                correct && !related -> gFalseRefuse++
                !correct && !related -> gStopWrong++
                else -> gKeepWrong++
            }
        }
        println("\n── TOPICAL GATE (refuse when the top card shares no topical word)")
        println("   right kept      : $gKeepRight")
        println("   RIGHT REFUSED   : $gFalseRefuse   <- the cost")
        println("   WRONG STOPPED   : $gStopWrong   <- the benefit")
        println("   wrong still served: $gKeepWrong")
        val served = gKeepRight + gKeepWrong
        if (served > 0) {
            println("   precision of what still gets served: $gKeepRight/$served " +
                "(%.0f%%) vs %.0f%% before".format(100.0 * gKeepRight / served, 100.0 * (gKeepRight + gFalseRefuse) / n))
        }

        // ── RECALL@K CEILING for an LLM-selector stage ──────────────────────
        // A "let the LLM pick from the top-k" stage can never beat the rate at which the
        // correct card is PRESENT in the top-k list it is shown. This is that ceiling,
        // for cards and modules, as k grows.
        val ks = listOf(1, 3, 5, 10, 20, 30, 50)
        val cardHit = IntArray(ks.size); val famHit = IntArray(ks.size)
        for (q in questions) {
            val deep = index.search(q.questionBn, k = 60, scoreThreshold = 0f, language = ModuleKnowledgeIndex.Lang.BN)
            val cardRank = deep.indexOfFirst { key(it) == q.expected }
            val expFam = q.expected.substringBefore(':')
            val famRank = deep.map { it.moduleFamilyId.take(8) }.indexOf(expFam)
            ks.forEachIndexed { i, kk ->
                if (cardRank in 0 until kk) cardHit[i]++
                if (famRank in 0 until kk) famHit[i]++
            }
        }
        println("\n── RECALL@K CEILING (what a perfect selector over top-k could score)")
        println("   k      card-present   module-present")
        ks.forEachIndexed { i, kk ->
            println("   %-6d %d/%d (%.0f%%)     %d/%d (%.0f%%)".format(
                kk, cardHit[i], n, 100.0 * cardHit[i] / n, famHit[i], n, 100.0 * famHit[i] / n))
        }

        // ── MODULE-level accuracy ───────────────────────────────────────────
        // Card selection is the hard problem; the backend scores 0.984 module hit@5 against
        // 0.858 at card level. If module selection is materially better here too, a UX that
        // names the topic and lets the CHW pick the card beats asserting a wrong answer.
        var mR1 = 0; var mR3 = 0; var mR5 = 0; var mR10 = 0; var cR5 = 0
        for (q in questions) {
            val expFam = q.expected.substringBefore(':')
            val hits = index.search(q.questionBn, k = 10, scoreThreshold = 1.5f, language = ModuleKnowledgeIndex.Lang.BN)
            val famRank = hits.map { it.moduleFamilyId.take(8) }.indexOf(expFam)
            if (famRank == 0) mR1++
            if (famRank in 0..2) mR3++
            if (famRank in 0..4) mR5++
            if (famRank in 0..9) mR10++
            if (hits.indexOfFirst { key(it) == q.expected } in 0..4) cR5++
        }
        println("\n── MODULE-LEVEL vs CARD-LEVEL (is \"name the topic\" a better product?)")
        println("   module recall@1  : $mR1/$n (%.0f%%)".format(100.0 * mR1 / n))
        println("   module recall@3  : $mR3/$n (%.0f%%)".format(100.0 * mR3 / n))
        println("   module recall@5  : $mR5/$n (%.0f%%)".format(100.0 * mR5 / n))
        println("   module recall@10 : $mR10/$n (%.0f%%)".format(100.0 * mR10 / n))
        println("   card   recall@5  : $cR5/$n (%.0f%%)".format(100.0 * cR5 / n))

        var stemR1 = 0; var stemR3 = 0; var stemChanged = 0
        for (q in questions) {
            val stemmed = stripBanglaSuffixes(q.questionBn)
            if (stemmed != q.questionBn) stemChanged++
            val hits = index.search(stemmed, k = 10, scoreThreshold = 1.5f, language = ModuleKnowledgeIndex.Lang.BN)
            val rank = hits.indexOfFirst { key(it) == q.expected }
            if (rank == 0) stemR1++
            if (rank in 0..2) stemR3++
        }
        println("\n── + BANGLA CASE-SUFFIX STRIPPING (candidate client fix)")
        println("   queries rewritten : $stemChanged/$n")
        println("   recall@1          : $stemR1/$n (%.0f%%)   (was $r1/$n)".format(100.0 * stemR1 / n))
        println("   recall@3          : $stemR3/$n (%.0f%%)   (was ${r1 + r3}/$n)".format(100.0 * stemR3 / n))

        // ── GroundingSelector: what production ACTUALLY serves ───────────────
        // index.search() is raw BM25 order. Production calls GroundingSelector, and in
        // Bengali mode `runEnglishSearch` is always true (the BN->EN translation differs
        // from the BN query), so the merge/rerank path runs even though the EN index is
        // empty — applying a sibling boost that only cards from multi-card families can
        // earn. Measured here against raw order, plus the candidate fix of skipping the
        // rerank when the English side contributed nothing.
        var selR1 = 0; var rawR1 = 0; var fixR1 = 0; var reordered = 0
        for (q in questions) {
            val raw = index.search(q.questionBn, k = 3, scoreThreshold = 1.5f, language = ModuleKnowledgeIndex.Lang.BN)
            // production: englishQuery is the BN->EN translation, i.e. always != nativeQuery
            val prod = GroundingSelector.select(
                nativeQuery = q.questionBn,
                englishQuery = q.questionBn + " (en)",
                nativeLanguage = ModuleKnowledgeIndex.Lang.BN,
                index = index,
                k = 3,
                scoreThreshold = 1.5f,
            )
            // candidate fix: no cross-index hits -> keep native BM25 order
            val fixed = if (prod.englishHits.isEmpty()) raw else prod.hits

            if (raw.firstOrNull()?.chunkId != prod.hits.firstOrNull()?.chunkId) reordered++
            if (raw.firstOrNull()?.let { key(it) } == q.expected) rawR1++
            if (prod.hits.firstOrNull()?.let { key(it) } == q.expected) selR1++
            if (fixed.firstOrNull()?.let { key(it) } == q.expected) fixR1++
        }
        println("\n── GROUNDING SELECTOR vs RAW BM25 ORDER")
        println("   top-1 changed by the reranker : $reordered/$n")
        println("   recall@1 raw BM25 order       : $rawR1/$n (%.0f%%)".format(100.0 * rawR1 / n))
        println("   recall@1 production selector  : $selR1/$n (%.0f%%)   <- what CHWs actually get".format(100.0 * selR1 / n))
        println("   recall@1 with candidate fix   : $fixR1/$n (%.0f%%)   (skip rerank when EN side is empty)".format(100.0 * fixR1 / n))

        // ── Self-retrieval: label-free ground truth ─────────────────────────
        // Each card's own authored questions/hints are queries whose correct answer is
        // that card by definition. No external labels, so no label noise: this isolates
        // the ranker + corpus from any disagreement about what "the right card" is.
        println("\n── SELF-RETRIEVAL (card's own authored questions as queries; ground truth definitional)")
        var selfTotal = 0; var selfR1 = 0; var selfR3 = 0; var selfFam = 0
        for (m in modules) {
            for ((chunk, _, meta) in ModuleCorpusParser.extractCardChunks(m)) {
                for (phrase in (meta.hintsBn + meta.questionsBn)) {
                    if (phrase.isBlank()) continue
                    selfTotal++
                    val hits = index.search(phrase, k = 3, scoreThreshold = 1.5f, language = ModuleKnowledgeIndex.Lang.BN)
                    val r = hits.indexOfFirst { it.chunkId == chunk.chunkId }
                    if (r == 0) selfR1++
                    if (r in 0..2) selfR3++
                    if (hits.firstOrNull()?.moduleFamilyId == chunk.moduleFamilyId) selfFam++
                }
            }
        }
        if (selfTotal > 0) {
            println("   queries        : $selfTotal (every card's own hint/question phrases)")
            println("   recall@1       : $selfR1/$selfTotal (%.0f%%)".format(100.0 * selfR1 / selfTotal))
            println("   recall@3       : $selfR3/$selfTotal (%.0f%%)".format(100.0 * selfR3 / selfTotal))
            println("   same family@1  : $selfFam/$selfTotal (%.0f%%)".format(100.0 * selfFam / selfTotal))
            println("   → a hint that cannot retrieve its OWN card is a corpus defect, not a phrasing gap.")
        }

        println("\n── PRECISION vs COVERAGE (score cut on production top-1)")
        println("   cut      answers  correct  precision  coverage")
        val allScored = correctScores.map { it to true } + wrongScores.map { it to false }
        for (cut in listOf(0f, 150f, 200f, 250f, 300f, 350f, 400f, 450f, 500f)) {
            val kept = allScored.filter { it.first >= cut }
            val ok = kept.count { it.second }
            if (kept.isEmpty()) { println("   %-8.0f 0        0        n/a        0%%".format(Locale.US, cut)); continue }
            println(
                "   %-8.0f %-8d %-8d %-10s %.0f%%".format(
                    Locale.US, cut, kept.size, ok,
                    "%.0f%%".format(100.0 * ok / kept.size), 100.0 * kept.size / n,
                )
            )
        }

        println("\n── PER QUESTION")
        lines.forEach { println("   $it") }
        println("\n=================================================")
    }

    /**
     * Best accuracy a single threshold on this signal could buy: for every candidate cut,
     * how many wrong answers it suppresses versus how many right ones it destroys.
     */
    private fun separation(name: String, ok: List<Float>, bad: List<Float>): String {
        if (ok.isEmpty() || bad.isEmpty()) return "$name separation: n/a"
        val cuts = (ok + bad).distinct().sorted()
        var best = Triple(0f, 0, 0) // cut, wrong suppressed, right lost
        var bestNet = Int.MIN_VALUE
        for (c in cuts) {
            val suppressed = bad.count { it < c }
            val lost = ok.count { it < c }
            val net = suppressed - lost
            if (net > bestNet) { bestNet = net; best = Triple(c, suppressed, lost) }
        }
        return "$name separation: best cut=%.2f suppresses %d/%d wrong, costs %d/%d right (net %+d)".format(
            Locale.US, best.first, best.second, bad.size, best.third, ok.size, bestNet,
        )
    }

    /**
     * Common Bangla case/postposition endings. Bangla marks case by suffix
     * (ম্যালেরিয়া -> ম্যালেরিয়ার), so the surface form a CHW types often differs from the
     * form the card uses. Longest-match first; the original word is kept too, so a
     * stripped form can only add recall, never remove an exact match.
     */
    private val banglaSuffixes = listOf(
        "গুলোর", "গুলির", "গুলো", "গুলি", "দের", "েরা", "ের", "কে", "তে", "রা", "য়", "র",
    )

    private fun stripBanglaSuffixes(query: String): String {
        val extra = BanglaTokenizer.tokenizeQuery(query).mapNotNull { w ->
            if (w.length < 5) return@mapNotNull null
            banglaSuffixes.firstOrNull { w.endsWith(it) && w.length - it.length >= 3 }
                ?.let { w.dropLast(it.length) }
        }.distinct()
        return if (extra.isEmpty()) query else query + " " + extra.joinToString(" ")
    }

    private fun key(c: GroundingChunk) = "${c.moduleFamilyId.take(8)}:${c.positionalId}"

    /** Token overlap between two card bodies — detects duplicate content across families. */
    private fun bodyOverlap(a: GroundingChunk, b: GroundingChunk): Float {
        val ta = BanglaTokenizer.tokenize(a.bodyBn ?: return 0f).toSet()
        val tb = BanglaTokenizer.tokenize(b.bodyBn ?: return 0f).toSet()
        if (ta.isEmpty() || tb.isEmpty()) return 0f
        return ta.intersect(tb).size.toFloat() / minOf(ta.size, tb.size).toFloat()
    }

    /** Per-card CHW question phrases indexed one document per phrase. */
    private class QuestionBank(modules: List<ModuleEntity>) {
        private val owners = mutableListOf<GroundingChunk>()
        private val docs = mutableListOf<List<String>>()

        init {
            for (m in modules) {
                for ((chunk, _, meta) in ModuleCorpusParser.extractCardChunks(m)) {
                    for (q in meta.hintsBn + meta.questionsBn) {
                        if (q.isBlank()) continue
                        owners += chunk
                        docs += BanglaTokenizer.tokenize(q) + BanglaTokenizer.wordBigrams(q)
                    }
                }
            }
        }

        val size: Int get() = docs.size
        val cardCount: Int get() = owners.distinctBy { it.chunkId }.size
        private val scorer = Bm25Scorer(docs)

        fun best(query: String): Pair<GroundingChunk, Float>? {
            val tokens = BanglaTokenizer.tokenizeQuery(query)
            if (tokens.isEmpty()) return null
            val weights = tokens.associateWith { 1f }
            return docs.indices
                .map { i -> owners[i] to scorer.scoreWeighted(weights, i) }
                .maxByOrNull { it.second }
                ?.takeIf { it.second > 0f }
        }
    }

    private fun loadQuestions(file: File): List<Q> =
        json.parseToJsonElement(file.readText()).jsonArray.mapNotNull { el ->
            val o = el as? JsonObject ?: return@mapNotNull null
            fun s(k: String) = (o[k] as? JsonPrimitive)?.contentOrNull
            Q(
                id = s("id") ?: return@mapNotNull null,
                questionBn = s("question_bn") ?: return@mapNotNull null,
                expected = s("expected") ?: return@mapNotNull null,
                labelMargin = (o["label_margin"] as? JsonPrimitive)?.floatOrNull ?: 0f,
            )
        }

    private fun loadModules(file: File): List<ModuleEntity> =
        json.parseToJsonElement(file.readText()).jsonArray.mapNotNull { el ->
            val o = el as? JsonObject ?: return@mapNotNull null
            fun s(k: String) = (o[k] as? JsonPrimitive)?.contentOrNull
            moduleEntityFixture(
                moduleId = s("module_id") ?: return@mapNotNull null,
                moduleFamilyId = s("module_family_id") ?: return@mapNotNull null,
                titleBn = s("title_bn") ?: "",
                cardsJson = raw(o["cards_json"]),
                searchMetadataJson = raw(o["search_metadata_json"]),
            )
        }

    private fun raw(el: kotlinx.serialization.json.JsonElement?): String = when (el) {
        null -> "[]"
        is JsonPrimitive -> el.contentOrNull ?: "[]"
        is JsonArray -> el.toString()
        else -> el.toString()
    }
}
