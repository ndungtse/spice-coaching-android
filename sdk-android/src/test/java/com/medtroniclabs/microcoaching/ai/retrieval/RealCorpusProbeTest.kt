package com.medtroniclabs.microcoaching.ai.retrieval

import com.medtroniclabs.microcoaching.data.db.entity.ModuleEntity
import com.medtroniclabs.microcoaching.data.db.entity.moduleEntityFixture
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.util.Locale
import kotlin.math.ln

/**
 * Retrieval probe over a REAL on-device corpus dump, used to diagnose wrong-card
 * answers with the production ranking code instead of synthetic fixtures, and to
 * compare candidate scoring variants against labelled ground truth.
 *
 * Not an assertion test — it prints a report. Skipped unless a dump is supplied, so
 * CI is unaffected:
 *
 *   MC_REAL_CORPUS=/path/to/real_corpus_probe.json \
 *     ./gradlew :sdk-android:testDebugUnitTest --tests '*RealCorpusProbeTest*' -i
 *
 * The dump is a JSON array of {module_id, module_family_id, version, title_bn,
 * cards_json, search_metadata_json} — one entry per `module_cache` row.
 *
 * Bengali queries are the strings MLKit actually produced on the device for the
 * English QA questions (read off the `EN→BN retrieval translate:` trace line), so the
 * probe scores the same text production searched with.
 */
class RealCorpusProbeTest {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    private data class Probe(
        val id: String,
        val english: String,
        val bangla: String,
        /** "family:cardIndex" pairs that answer the question; empty = nothing in corpus answers it. */
        val acceptable: Set<String> = emptySet(),
    )

    private val probes = listOf(
        Probe(
            "q1-nutrition", "what should a pregnant woman eat every day",
            "একজন গর্ভবতী মহিলা আমাকে জিজ্ঞেস করলো সে কি প্রতিদিন খেতে হবে। আমি তাকে কি পরামর্শ দিতে হবে?",
            setOf("8f92a57a:0", "8f92a57a:1", "8f92a57a:2", "c0670405:0", "c0670405:1", "c0670405:2"),
        ),
        Probe(
            "q2-water", "how much water should she drink every day",
            "একজন গর্ভবতী মহিলা আমাকে জিজ্ঞেস করলো, প্রতিদিন কতটা পানি পান করা উচিত। আমি তাকে কি বলব?",
            setOf("8f92a57a:1", "8f92a57a:2", "c0670405:1"),
        ),
        Probe(
            "q3-tt", "took 3 TT doses before pregnancy, need another?",
            "একটি গর্ভবতী মহিলার ইতিমধ্যে গর্ভাবস্থার আগে 3 টিটি ডোজ গ্রহণ। তিনি এখন অন্য টিটি ভ্যাকসিন প্রয়োজন?",
            emptySet(), // no card states the TT dosing rule → refusal is the correct outcome
        ),
        Probe(
            "q4-bp", "BP above 140/90, what should I do",
            "আমি একটি গর্ভবতী মহিলা bp চেক এবং এটি 140/90 mmhg উপরে। আমি কি করবো?",
            setOf("0a3a9e46:2", "c27f3a36:2", "753cd45d:8", "12360c48:1"),
        ),
        Probe(
            "q5-swelling", "high BP plus swollen hands and feet",
            "একটি গর্ভবতী মহিলার উচ্চ বিপি আছে এবং তার হাত এবং ফুট এছাড়াও ফুসকুড়ি হয়। আমি কি করবো?",
            setOf("753cd45d:8", "753cd45d:9"),
        ),
        Probe(
            "q6-waterbroke", "<37 weeks, water broken, green/brown fluid",
            "একটি গর্ভবতী মহিলার 37 সপ্তাহেরও কম গর্ভবতী এবং তার পানি ভেঙ্গে গেছে। তরল সবুজ বা বাদামী দেখায়। আমি কি করবো?",
            emptySet(), // no PROM card in this corpus → refusal is the correct outcome
        ),
        Probe("d5-malaria", "what causes malaria", "ম্যালেরিয়া কি কারণ?", setOf("97910426:0")),
        Probe(
            "d6-tb", "what is tuberculosis", "যক্ষ্মা কি?",
            setOf("40cbaf4a:0", "26712591:0", "3566aa82:0", "27297bd8:0", "5eb03775:0", "4ab50119:0", "85bdf4ce:0"),
        ),
    )

    /** Scoring variants under test. */
    private enum class Variant(val label: String) {
        PROD("V0 production (char bigrams @1.0)"),
        BIGRAM_LOW("V1 char bigrams @0.15"),
        BIGRAM_OOV("V2 char bigrams only for OOV words"),
        WORDS_ONLY("V3 words only (no char bigrams)"),
        COVERAGE("V4 field-coverage normalised (fixes enriched-vs-bare bias)"),
        COVERAGE_FRAME("V5 V4 + conversational frame stripped"),
    }

    /**
     * Words the CHW's framing contributes ("a pregnant woman ASKED ME ... what should I
     * ADVISE her") — they carry no clinical signal but do carry BM25 mass.
     */
    private val frameWords = setOf(
        "আমাকে", "জিজ্ঞেস", "করলো", "একজন", "একটি", "তাকে", "বলব", "করবো",
        "পরামর্শ", "দিতে", "জিজ্ঞাসা", "প্রশ্ন", "তিনি", "এখন",
    )

    /**
     * Who the question is about, not what it is about. These sit below the generic-df
     * cut-off yet carry no topical signal, so a card matching only these is not an
     * answer — the "pregnant woman" trap behind every wrong card in the QA set.
     */
    private val demographicWords = setOf(
        "মহিলা", "মহিলার", "নারী", "নারীর", "রোগী", "রোগীর", "মা", "মায়ের",
        "গর্ভবতী", "গর্ভবতীর", "শিশু", "শিশুর",
    )

    @Test
    fun `real corpus retrieval probe`() {
        val path = System.getenv("MC_REAL_CORPUS")
        assumeTrue("MC_REAL_CORPUS not set — skipping real-corpus probe", path != null)
        val modules = loadModules(File(path!!))
        val index = ModuleKnowledgeIndex.build(modules)
        val scope = ScopeClassifier.buildFrom(modules)
        val probe = FieldProbe(modules)

        println("\n================ REAL CORPUS PROBE ================")
        println("modules=${modules.size} indexedChunks=${index.size}")

        // ── Part 1: production behaviour, per query ──────────────────────────
        for (p in probes) {
            println("\n─────── [${p.id}] ${p.english}")
            println("  BN: ${p.bangla}")
            val hits = index.search(p.bangla, k = 10, scoreThreshold = 1.5f, language = ModuleKnowledgeIndex.Lang.BN)
            val margin = margin(hits)
            println(
                "  PRODUCTION top1=%.1f margin=%.3f top3SameFamily=%b served=%s verdict=%s".format(
                    Locale.US, hits.firstOrNull()?.score ?: 0f, margin,
                    hits.take(3).map { it.moduleFamilyId }.distinct().size == 1,
                    hits.firstOrNull()?.let { key(it) } ?: "∅",
                    verdict(p, hits.firstOrNull(), refused = OffTopicGuard.shouldRefuseLowEnd(p.bangla, hits.take(3), scope.scopeTerms())),
                )
            )
            probe.reportQuery(p.bangla)
            hits.take(4).forEachIndexed { i, h ->
                val mark = if (key(h) in p.acceptable) "  <== ACCEPTABLE" else ""
                println("   #%d %7.2f %-12s %s%s".format(Locale.US, i + 1, h.score, key(h), (h.titleBn ?: "").take(50), mark))
                println("        " + probe.breakdown(p.bangla, h))
            }
            if (p.acceptable.isNotEmpty()) {
                val deep = index.search(p.bangla, k = 400, scoreThreshold = 0f, language = ModuleKnowledgeIndex.Lang.BN)
                val best = deep.withIndex().firstOrNull { key(it.value) in p.acceptable }
                println(
                    "  best ACCEPTABLE card: " + (best?.let { "#${it.index + 1} ${key(it.value)} score=%.1f".format(Locale.US, it.value.score) }
                        ?: "not retrieved at all")
                )
            }
        }

        // ── Part 2: variant comparison ──────────────────────────────────────
        println("\n\n================ VARIANT COMPARISON ================")
        println("verdicts: RIGHT=acceptable card served · WRONG=wrong card served ·")
        println("          GOOD_REFUSE=nothing answers it and we refuse · MISS=answerable but refused\n")
        val marginGate = 1.30f
        for (v in Variant.entries) {
            var right = 0; var wrong = 0; var goodRefuse = 0; var miss = 0
            val rows = mutableListOf<String>()
            for (p in probes) {
                val ranked = probe.rank(p.bangla, v)
                val top = ranked.firstOrNull()
                val m = if (ranked.size > 1 && ranked[1].second > 0f) ranked[0].second / ranked[1].second else Float.NaN
                val servedKey = top?.first?.let { key(it) }
                val ok = servedKey != null && servedKey in p.acceptable
                val gateBlocks = !m.isNaN() && m < marginGate
                val label = when {
                    p.acceptable.isEmpty() && ok -> "?"
                    p.acceptable.isEmpty() -> "WRONG"
                    ok -> "RIGHT"
                    else -> "WRONG"
                }
                when (label) {
                    "RIGHT" -> right++
                    "WRONG" -> if (p.acceptable.isEmpty()) wrong++ else wrong++
                    else -> {}
                }
                rows.add(
                    "   %-15s %-6s top=%-12s margin=%5s %s".format(
                        p.id, label, servedKey ?: "∅",
                        if (m.isNaN()) "n/a" else "%.2f".format(Locale.US, m),
                        if (gateBlocks) "[margin<$marginGate → would hedge/refuse]" else "",
                    )
                )
            }
            println("── ${v.label}")
            rows.forEach { println(it) }
            println("   → right=$right wrong=$wrong")
        }

        // ── Part 3b: content-word overlap as a gate signal ──────────────────
        // Raw score and margin both failed to separate right from wrong. This asks a
        // different question: how many of the CHW's own clinical words (frame words and
        // character bigrams excluded, out-of-corpus words excluded) actually appear in
        // the card we are about to serve?
        println("\n\n================ CONTENT-WORD OVERLAP (production ranking) ================")
        println("   overlap = query words (non-frame, df>0) present in the served card's text\n")
        for (p in probes) {
            val hits = index.search(p.bangla, k = 10, scoreThreshold = 1.5f, language = ModuleKnowledgeIndex.Lang.BN)
            val top = hits.firstOrNull()
            val servedOverlap = top?.let { probe.contentOverlap(p.bangla, it) } ?: emptyList()
            val deep = index.search(p.bangla, k = 400, scoreThreshold = 0f, language = ModuleKnowledgeIndex.Lang.BN)
            val bestOk = deep.firstOrNull { key(it) in p.acceptable }
            val okOverlap = bestOk?.let { probe.contentOverlap(p.bangla, it) } ?: emptyList()
            println(
                "   %-15s answerable=%-5s served=%-12s overlap=%d %s".format(
                    p.id, p.acceptable.isNotEmpty(), top?.let { key(it) } ?: "∅",
                    servedOverlap.size, servedOverlap.take(6),
                )
            )
            if (bestOk != null) {
                println("   %-15s %-18s correct=%-12s overlap=%d %s".format("", "", key(bestOk), okOverlap.size, okOverlap.take(6)))
            }
        }

        // ── Part 3c: TOPICAL-TERM gate ──────────────────────────────────────
        // The overlap table shows wrong answers overlap only on demographic words
        // (গর্ভবতী/মহিলা) while right answers overlap on the question's topical word
        // (ম্যালেরিয়া, যক্ষ্মা, 140/90, খেতে, পানি). So gate on *specificity*: keep only
        // candidates that contain at least one topical query word — a word that is not
        // framing, exists in the corpus, and is not corpus-generic — and refuse when no
        // candidate has one.
        println("\n\n================ TOPICAL-TERM GATE ================")
        println("   topical = query word, non-frame, 0 < df <= ${probe.genericDf} (10% of corpus)\n")
        var gateRight = 0; var gateWrong = 0; var gateGoodRefuse = 0; var gateMiss = 0
        for (p in probes) {
            val ranked = probe.rank(p.bangla, Variant.PROD)
            val topical = probe.topicalWords(p.bangla)
            val kept = ranked.filter { (c, _) -> probe.containsAnyTopical(p.bangla, c) }
            val served = kept.firstOrNull()?.first
            val decision = when {
                served == null -> if (p.acceptable.isEmpty()) "GOOD_REFUSE" else "MISS"
                p.acceptable.isEmpty() -> "WRONG(should refuse)"
                key(served) in p.acceptable -> "RIGHT"
                else -> "WRONG"
            }
            when (decision) {
                "RIGHT" -> gateRight++
                "GOOD_REFUSE" -> gateGoodRefuse++
                "MISS" -> gateMiss++
                else -> gateWrong++
            }
            println(
                "   %-15s %-20s served=%-12s candidates=%d topical=%s".format(
                    p.id, decision, served?.let { key(it) } ?: "REFUSE", kept.size, topical,
                )
            )
        }
        println("\n   → RIGHT=$gateRight WRONG=$gateWrong GOOD_REFUSE=$gateGoodRefuse FALSE_REFUSAL=$gateMiss")

        // ── Part 3d: stricter topical rules ─────────────────────────────────
        // The plain filter never refuses: with 30–95 cards containing *some* topical
        // word there is always a candidate. These rules ask for more topical evidence.
        println("\n\n================ STRICTER TOPICAL RULES ================")
        for (rule in listOf("R1 contains one of the 2 rarest topical words", "R2 contains >=2 topical words", "R3 rank by topical coverage, then score")) {
            var right = 0; var wrong = 0; var good = 0; var miss = 0
            val rows = mutableListOf<String>()
            for (p in probes) {
                val ranked = probe.rank(p.bangla, Variant.PROD)
                val topical = probe.topicalWords(p.bangla)
                val rarest2 = topical.take(2).toSet()
                val scored = ranked.map { (c, s) -> Triple(c, s, probe.topicalMatches(p.bangla, c)) }
                val served: GroundingChunk? = when {
                    rule.startsWith("R1") -> scored.firstOrNull { it.third.any { w -> w in rarest2 } }?.first
                    rule.startsWith("R2") -> scored.firstOrNull { it.third.size >= 2 }?.first
                    else -> scored.filter { it.third.isNotEmpty() }
                        .sortedWith(compareByDescending<Triple<GroundingChunk, Float, List<String>>> { it.third.size }.thenByDescending { it.second })
                        .firstOrNull()?.first
                }
                val decision = when {
                    served == null -> if (p.acceptable.isEmpty()) "GOOD_REFUSE".also { good++ } else "FALSE_REFUSAL".also { miss++ }
                    p.acceptable.isEmpty() -> "WRONG(should refuse)".also { wrong++ }
                    key(served) in p.acceptable -> "RIGHT".also { right++ }
                    else -> "WRONG".also { wrong++ }
                }
                rows.add("   %-15s %-20s served=%s".format(p.id, decision, served?.let { key(it) } ?: "REFUSE"))
            }
            println("── $rule")
            rows.forEach { println(it) }
            println("   → RIGHT=$right WRONG=$wrong GOOD_REFUSE=$good FALSE_REFUSAL=$miss")
        }

        // ── Part 3e: QUESTION-BANK matching ─────────────────────────────────
        // The backend answers well because it matches semantically at MODULE level and
        // then lets a large model read whole modules. We cannot copy that on device, but
        // the corpus already ships CHW-phrased questions per card (retrieval_hints.bn +
        // questions.bn, 987 phrases). This treats each phrase as its own document and
        // matches question-against-question instead of question-against-prose — the same
        // shape as a shipped Q&A pack.
        println("\n\n================ QUESTION-BANK MATCHING ================")
        val bank = QuestionBank(modules)
        println("   bank: ${bank.size} question phrases over ${bank.cardCount} cards\n")
        var bRight = 0; var bWrong = 0; var bGood = 0; var bMiss = 0
        for (p in probes) {
            val best = bank.best(p.bangla)
            // Both sides are questions of similar length, so the score scale is stable
            // enough to threshold — unlike prose BM25.
            val gate = 4.0f
            val served = best?.takeIf { it.second >= gate }
            val decision = when {
                served == null -> if (p.acceptable.isEmpty()) "GOOD_REFUSE".also { bGood++ } else "FALSE_REFUSAL".also { bMiss++ }
                p.acceptable.isEmpty() -> "WRONG(should refuse)".also { bWrong++ }
                key(served.first) in p.acceptable -> "RIGHT".also { bRight++ }
                else -> "WRONG".also { bWrong++ }
            }
            println(
                "   %-15s %-20s %s".format(
                    p.id, decision,
                    best?.let { "score=%.1f %s  q=\"%s\"".format(Locale.US, it.second, key(it.first), it.third.take(60)) } ?: "no match",
                )
            )
        }
        println("\n   → RIGHT=$bRight WRONG=$bWrong GOOD_REFUSE=$bGood FALSE_REFUSAL=$bMiss")

        // ── Part 3: margin as a gate signal ─────────────────────────────────
        println("\n\n================ MARGIN vs CORRECTNESS (V1) ================")
        for (p in probes) {
            val ranked = probe.rank(p.bangla, Variant.BIGRAM_LOW)
            val m = if (ranked.size > 1 && ranked[1].second > 0f) ranked[0].second / ranked[1].second else Float.NaN
            val servedKey = ranked.firstOrNull()?.first?.let { key(it) }
            val ok = servedKey != null && servedKey in p.acceptable
            println(
                "   %-15s margin=%5s served=%-12s correct=%s answerable=%s".format(
                    p.id, if (m.isNaN()) "n/a" else "%.2f".format(Locale.US, m),
                    servedKey ?: "∅", ok, p.acceptable.isNotEmpty(),
                )
            )
        }
        println("\n====================================================")
    }

    private fun key(c: GroundingChunk) = "${c.moduleFamilyId.take(8)}:${c.positionalId}"

    private fun margin(hits: List<GroundingChunk>): Float =
        if (hits.size > 1 && hits[1].score > 0f) hits[0].score / hits[1].score else Float.NaN

    private fun verdict(p: Probe, top: GroundingChunk?, refused: Boolean): String = when {
        refused && p.acceptable.isEmpty() -> "GOOD_REFUSE"
        refused -> "MISS(false refusal)"
        top == null -> "no hit"
        p.acceptable.isEmpty() -> "WRONG(should have refused)"
        key(top) in p.acceptable -> "RIGHT"
        else -> "WRONG"
    }

    /**
     * Rebuilds the BN field scorers with the same tokenisation the index uses, so the
     * probe can attribute score to TITLE / BODY / QUESTION / KEYWORD, show which query
     * terms are rare, and re-rank under alternative query-term weightings.
     */
    private inner class FieldProbe(modules: List<ModuleEntity>) {
        val chunks = mutableListOf<GroundingChunk>()
        private val title = mutableListOf<List<String>>()
        private val body = mutableListOf<List<String>>()
        private val question = mutableListOf<List<String>>()
        private val keyword = mutableListOf<List<String>>()

        init {
            for (m in modules) {
                val meta = ModuleCorpusParser.parseSearchMetadata(m.searchMetadataJson)
                val modKw = (meta.keywordsBn + meta.phrasesBn).flatMap { tok(it) }
                for ((chunk, rmeta, cardMeta) in ModuleCorpusParser.extractCardChunks(m)) {
                    chunks += chunk
                    title += tok(chunk.titleBn)
                    body += tok(chunk.bodyBn)
                    question += (cardMeta.hintsBn + cardMeta.questionsBn).flatMap { tok(it) }
                    keyword += modKw + (rmeta.allTerms + cardMeta.keywordsBn).flatMap { tok(it) }
                }
            }
        }

        private fun tok(s: String?): List<String> =
            if (s.isNullOrBlank()) emptyList()
            else BanglaTokenizer.tokenize(s) + BanglaTokenizer.wordBigrams(s)

        private val scorers = listOf(
            Triple("TITLE", 3.0f, Bm25Scorer(title)),
            Triple("BODY", 1.0f, Bm25Scorer(body)),
            Triple("QUEST", 2.5f, Bm25Scorer(question)),
            Triple("KEYWD", 0.5f, Bm25Scorer(keyword)),
        )

        fun df(term: String): Int = scorers.maxOf { it.third.documentFrequency(term) }

        private fun idf(df: Int): Float {
            val n = chunks.size.toFloat()
            return ln((n - df + 0.5f) / (df + 0.5f) + 1f)
        }

        /**
         * Split query tokens into real words and Bangla character bigrams.
         * [BanglaTokenizer.wordBigrams] pairs adjacent content words, so every content
         * word appears as a component of some word-bigram; anything left that is a bare
         * 2-char Bangla token is a character bigram.
         */
        private fun words(query: String): Set<String> {
            val all = BanglaTokenizer.tokenizeQuery(query).toSet()
            val fromBigrams = BanglaTokenizer.wordBigrams(query).flatMap { it.split("_") }.toSet()
            return all.filterTo(mutableSetOf()) { t ->
                t in fromBigrams || t.length > 2 || t.any { c -> c.code < 128 }
            }
        }

        private fun charBigrams(query: String): Set<String> =
            BanglaTokenizer.tokenizeQuery(query).toSet() - words(query)

        /** Which character bigrams come from an out-of-corpus word (a vocabulary bridge). */
        private fun oovBigrams(query: String): Set<String> {
            val oovWords = words(query).filter { df(it) == 0 }
            if (oovWords.isEmpty()) return emptySet()
            val keep = oovWords.flatMap { w -> BanglaTokenizer.tokenize(w).filter { it != w } }.toSet()
            return charBigrams(query).intersect(keep)
        }

        private fun weights(query: String, variant: Variant): Map<String, Float> {
            val keep = when (variant) {
                Variant.COVERAGE_FRAME -> words(query).filterNot { it in frameWords }.toSet()
                else -> words(query)
            }
            val w = keep.associateWith { 1f }.toMutableMap()
            val bigrams = charBigrams(query)
            when (variant) {
                Variant.PROD -> bigrams.forEach { w[it] = 1f }
                Variant.BIGRAM_LOW -> bigrams.forEach { w[it] = 0.15f }
                Variant.BIGRAM_OOV -> oovBigrams(query).forEach { w[it] = 1f }
                Variant.WORDS_ONLY -> {}
                // Keep production's bigram behaviour so the delta isolates the
                // coverage fix rather than mixing two changes.
                Variant.COVERAGE, Variant.COVERAGE_FRAME -> bigrams.forEach { w[it] = 1f }
            }
            return w
        }

        /** Field weights of the fields this chunk actually has content in. */
        private fun availableWeight(i: Int): Float {
            var w = 0f
            if (title[i].isNotEmpty()) w += 3.0f
            if (body[i].isNotEmpty()) w += 1.0f
            if (question[i].isNotEmpty()) w += 2.5f
            if (keyword[i].isNotEmpty()) w += 0.5f
            return w
        }

        fun rank(query: String, variant: Variant): List<Pair<GroundingChunk, Float>> {
            val wts = weights(query, variant)
            val normalise = variant == Variant.COVERAGE || variant == Variant.COVERAGE_FRAME
            val totalWeight = scorers.sumOf { it.second.toDouble() }.toFloat()
            return chunks.indices
                .map { i ->
                    val raw = scorers.sumOf { (_, fw, sc) -> (fw * sc.scoreWeighted(wts, i)).toDouble() }.toFloat()
                    // A card with no QUESTION/KEYWORD text scores 0 on a ×2.5 and a ×0.5
                    // field and can never catch an enriched sibling. Normalising by the
                    // weight actually available to the card removes that structural
                    // advantage without touching content.
                    val score = if (normalise) raw * totalWeight / availableWeight(i).coerceAtLeast(0.01f) else raw
                    chunks[i] to score
                }
                .filter { it.second > 0f }
                .sortedByDescending { it.second }
        }

        fun reportQuery(query: String) {
            val ws = words(query)
            val dfs = ws.map { it to df(it) }.sortedBy { it.second }
            println("  terms: words=${ws.size} charBigrams=${charBigrams(query).size} oovWords=${ws.count { df(it) == 0 }}")
            println("  rarest→commonest: " + dfs.joinToString(" ") { (w, d) -> "$w(df=$d)" }.take(280))
        }

        /** A word in more than this many chunks is corpus-generic, not topical. */
        val genericDf: Int = (chunks.size * 0.10f).toInt()

        /**
         * The question's topical words: the CHW's own words, minus framing and
         * demographics, that exist in the corpus but are not corpus-generic. These are
         * the terms that make the question *this* question.
         */
        fun topicalWords(query: String): List<String> =
            words(query)
                .filterNot { it in frameWords || it in demographicWords }
                .filter { df(it) in 1..genericDf }
                .sortedBy { df(it) }

        /** Which topical query words this card actually contains. */
        fun topicalMatches(query: String, hit: GroundingChunk): List<String> {
            val topical = topicalWords(query)
            val text = contentOverlapRaw(hit)
            return topical.filter { it in text }
        }

        fun containsAnyTopical(query: String, hit: GroundingChunk): Boolean {
            val topical = topicalWords(query).toSet()
            if (topical.isEmpty()) return false
            return contentOverlapRaw(hit).any { it in topical }
        }

        private fun contentOverlapRaw(hit: GroundingChunk): Set<String> {
            val i = chunks.indexOfFirst { it.chunkId == hit.chunkId }
            val text = listOfNotNull(hit.titleBn, hit.bodyBn).joinToString(" ")
            val hints = if (i >= 0) (question[i] + keyword[i]).toSet() else emptySet()
            return BanglaTokenizer.tokenize(text).toSet() + hints
        }

        /**
         * The CHW's own clinical words that actually appear in this card: frame words,
         * character bigrams and out-of-corpus words removed, so what is left is the
         * vocabulary the question and the card genuinely share.
         */
        fun contentOverlap(query: String, hit: GroundingChunk): List<String> {
            val queryWords = words(query).filterNot { it in frameWords }.filter { df(it) > 0 }
            val cardText = listOfNotNull(hit.titleBn, hit.bodyBn).joinToString(" ")
            val i = chunks.indexOfFirst { it.chunkId == hit.chunkId }
            val hintTokens = if (i >= 0) (question[i] + keyword[i]).toSet() else emptySet()
            val cardTokens = BanglaTokenizer.tokenize(cardText).toSet() + hintTokens
            return queryWords.filter { it in cardTokens }
        }

        fun breakdown(query: String, hit: GroundingChunk): String {
            val i = chunks.indexOfFirst { it.chunkId == hit.chunkId }
            if (i < 0) return "(not in probe index)"
            val prod = weights(query, Variant.PROD)
            val wordsOnlyW = weights(query, Variant.WORDS_ONLY)
            val parts = scorers.joinToString(" ") { (n, fw, sc) -> "%s=%.1f".format(Locale.US, n, fw * sc.scoreWeighted(prod, i)) }
            val total = scorers.sumOf { (_, fw, sc) -> (fw * sc.scoreWeighted(prod, i)).toDouble() }
            val wordsOnly = scorers.sumOf { (_, fw, sc) -> (fw * sc.scoreWeighted(wordsOnlyW, i)).toDouble() }
            val share = if (total > 0) 1.0 - wordsOnly / total else 0.0
            return "$parts | wordsOnly=%.1f charBigramShare=%.0f%%".format(Locale.US, wordsOnly, share * 100)
        }
    }

    /**
     * Each per-card question phrase (`retrieval_hints.bn` + `questions.bn`) indexed as its
     * own BM25 document, so a CHW question is matched against known questions rather than
     * against card prose. The card that owns the best-matching phrase is the answer.
     */
    private class QuestionBank(modules: List<ModuleEntity>) {
        private val owners = mutableListOf<GroundingChunk>()
        private val phrases = mutableListOf<String>()
        private val docs = mutableListOf<List<String>>()

        init {
            for (m in modules) {
                for ((chunk, _, cardMeta) in ModuleCorpusParser.extractCardChunks(m)) {
                    for (q in cardMeta.hintsBn + cardMeta.questionsBn) {
                        if (q.isBlank()) continue
                        owners += chunk
                        phrases += q
                        docs += BanglaTokenizer.tokenize(q) + BanglaTokenizer.wordBigrams(q)
                    }
                }
            }
        }

        val size: Int get() = phrases.size
        val cardCount: Int get() = owners.distinctBy { it.chunkId }.size

        private val scorer = Bm25Scorer(docs)

        /** Best (card, score, phrase) for this query. */
        fun best(query: String): Triple<GroundingChunk, Float, String>? {
            val tokens = BanglaTokenizer.tokenizeQuery(query)
            if (tokens.isEmpty()) return null
            val weights = tokens.associateWith { 1f }
            return docs.indices
                .map { i -> Triple(owners[i], scorer.scoreWeighted(weights, i), phrases[i]) }
                .maxByOrNull { it.second }
                ?.takeIf { it.second > 0f }
        }
    }

    private fun loadModules(file: File): List<ModuleEntity> {
        val arr = json.parseToJsonElement(file.readText()).jsonArray
        return arr.mapNotNull { el ->
            val o = el as? JsonObject ?: return@mapNotNull null
            fun s(k: String) = (o[k] as? JsonPrimitive)?.contentOrNull
            moduleEntityFixture(
                moduleId = s("module_id") ?: return@mapNotNull null,
                moduleFamilyId = s("module_family_id") ?: return@mapNotNull null,
                titleBn = s("title_bn") ?: "",
                cardsJson = rawJson(o["cards_json"]),
                searchMetadataJson = rawJson(o["search_metadata_json"]),
            )
        }
    }

    /** `cards_json` arrives as a JSON *string* holding JSON; keep it verbatim for the parser. */
    private fun rawJson(el: kotlinx.serialization.json.JsonElement?): String = when (el) {
        null -> "[]"
        is JsonPrimitive -> el.contentOrNull ?: "[]"
        is JsonArray -> el.toString()
        else -> el.toString()
    }
}
