package com.medtroniclabs.microcoaching.ai.retrieval

import com.medtroniclabs.microcoaching.ServeTuning
import com.medtroniclabs.microcoaching.data.db.entity.ModuleEntity
import com.medtroniclabs.microcoaching.data.db.entity.moduleEntityFixture
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.floatOrNull
import kotlinx.serialization.json.jsonArray
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * BM25-only against BM25 + dense on **fresh** questions, scored side by side.
 *
 * [ServeDecisionEvalTest] and [HybridServeDecisionEvalTest] both run the trainer's
 * original 26 questions — the set every threshold in this pipeline was calibrated
 * against — so a good score there partly measures the tuning fitting its own
 * calibration set. This runs 30 questions written afterwards against the same corpus
 * (`retrieval/chw_questions_2026-09.json`, documented in `docs/eval/`), phrased the way
 * a health worker would ask rather than in the cards' own words. Twenty-five are
 * answerable; five have no answer in any module, because a set with no unanswerable
 * questions cannot see a false serve, which is dense retrieval's characteristic failure.
 *
 * Cosines come from `retrieval/chw_dense_cosines_device_2026-09.json` — computed **on
 * device with the quantized encoder that actually ships**, for cards and queries alike,
 * rather than the fp32 fixture the other hybrid test replays. That is the configuration
 * a CHW would run.
 *
 * A report, not a gate: it prints both tallies and asserts only that every question was
 * scored. Pinning it would freeze numbers nobody has yet decided to defend.
 */
class ChwQuestionsEvalTest {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }
    private val tuning = ServeTuning()

    private data class LabelledQuestion(
        val id: String,
        val lang: String,
        val question: String,
        val nativeQuery: String,
        val englishQuery: String?,
        val acceptable: Set<String>?,
    )

    private data class Tally(
        var right: Int = 0,
        var goodRefuse: Int = 0,
        var wrong: Int = 0,
        var miss: Int = 0,
    ) {
        val correct: Int get() = right + goodRefuse
        val total: Int get() = right + goodRefuse + wrong + miss
        override fun toString() =
            "right=$right goodRefuse=$goodRefuse wrong=$wrong miss=$miss  →  $correct/$total"
    }

    @Test
    fun `bm25-only against hybrid on questions the tuning has never seen`() {
        val modules = loadModules()
        val index = ModuleKnowledgeIndex.build(modules)
        val scope = ScopeClassifier.buildFrom(modules)
        val chunksByKey = modules
            .flatMap { m -> ModuleCorpusParser.extractCardChunks(m).map { it.first } }
            .associateBy { "${it.moduleFamilyId.take(8)}:${it.positionalId}" }
        val denseByQuery = loadDenseCosines(chunksByKey)
        val questions = loadQuestions()

        val results = linkedMapOf<String, MutableMap<String, Tally>>(
            "BM25-only" to mutableMapOf("en" to Tally(), "bn" to Tally()),
            "BM25+dense" to mutableMapOf("en" to Tally(), "bn" to Tally()),
        )
        // Per question, what each mode did — so a difference can be read off directly
        // instead of inferred from two totals.
        val perQuestion = mapOf("en" to StringBuilder(), "bn" to StringBuilder())

        for (q in questions) {
            val bm25 = runPipeline(q, index, scope, dense = emptyList())
            val hybrid = runPipeline(q, index, scope, dense = denseByQuery[q.id].orEmpty())
            score(results.getValue("BM25-only").getValue(q.lang), q, bm25.first)
            score(results.getValue("BM25+dense").getValue(q.lang), q, hybrid.first)
            val a = verdict(q, bm25.first)
            val b = verdict(q, hybrid.first)
            perQuestion.getValue(q.lang).appendLine(
                "${if (a == b) " " else "*"} ${q.id.padEnd(8)} " +
                    "bm25=${a.padEnd(11)}${(bm25.first ?: "-").padEnd(14)}" +
                    "hybrid=${b.padEnd(11)}${hybrid.first ?: "-"}",
            )
        }

        val report = linkedMapOf("BM25-only" to EvalReport.Tally(), "BM25+dense" to EvalReport.Tally())
        for (q in questions) {
            report.getValue("BM25-only").add(EvalReport.verdict(q.acceptable, runPipeline(q, index, scope, emptyList()).first))
            report.getValue("BM25+dense").add(EvalReport.verdict(q.acceptable, runPipeline(q, index, scope, denseByQuery[q.id].orEmpty()).first))
        }
        EvalReport.print("CHW question set", report)
        println("ChwQuestionsEval — 30 fresh questions (25 answerable, 5 not)")
        for ((mode, byLang) in results) {
            println("  $mode")
            for ((lang, tally) in byLang) println("    ${lang.uppercase()}: $tally")
        }
        for ((lang, detail) in perQuestion) {
            println("\nPer-question, ${lang.uppercase()} (* = the two modes disagree):")
            print(detail)
        }

        for (byLang in results.values) {
            for (tally in byLang.values) assertEquals(30, tally.total)
        }
    }

    private fun verdict(q: LabelledQuestion, served: String?): String = when {
        served == null && q.acceptable == null -> "GOOD_REFUSE"
        served == null -> "MISS"
        q.acceptable == null -> "WRONG"
        served in q.acceptable -> "RIGHT"
        else -> "WRONG"
    }

    private fun score(tally: Tally, q: LabelledQuestion, served: String?) {
        when (verdict(q, served)) {
            "GOOD_REFUSE" -> tally.goodRefuse++
            "MISS" -> tally.miss++
            "RIGHT" -> tally.right++
            else -> tally.wrong++
        }
    }

    /** Production pipeline; an empty [dense] list is the BM25-only path byte for byte. */
    private fun runPipeline(
        q: LabelledQuestion,
        index: ModuleKnowledgeIndex,
        scope: ScopeClassifier,
        dense: List<Pair<GroundingChunk, Float>>,
    ): Pair<String?, String> {
        if (scope.isOutOfScope(q.question)) return null to "L0 deny-list"

        val selection = GroundingSelector.select(
            nativeQuery = q.nativeQuery,
            englishQuery = q.englishQuery,
            nativeLanguage = ModuleKnowledgeIndex.Lang.BN,
            index = index,
            k = 3,
            scoreThreshold = 1.5f,
            dense = dense.take(tuning.denseTopK),
            rrfK = tuning.rrfK,
            denseAdmitFloor = tuning.cosFloor,
        )
        val hits = selection.hits
        if (hits.isEmpty()) return null to "no hits cleared the BM25 floor"

        val guardQuery = when {
            q.lang == "en" && q.nativeQuery != q.question -> "${q.question} ${q.nativeQuery}"
            q.lang == "bn" && q.englishQuery != null && q.englishQuery != q.question ->
                "${q.question} ${q.englishQuery}"
            else -> q.question
        }
        val decision = ServeGate.decide(
            query = guardQuery,
            hits = hits,
            clinicalTerms = scope.scopeTerms(),
            tuning = tuning,
            isBanglaTurn = q.lang == "bn",
        )
        val why = ServeGate.describe(decision)
        val top = (decision as? ServeGate.Decision.Serve)?.hit ?: return null to why
        return "${top.moduleFamilyId.take(8)}:${top.positionalId}" to why
    }

    private fun resourceText(name: String): String =
        checkNotNull(javaClass.classLoader?.getResourceAsStream(name)) { "missing test resource $name" }
            .bufferedReader().readText()

    private fun loadDenseCosines(
        chunksByKey: Map<String, GroundingChunk>,
    ): Map<String, List<Pair<GroundingChunk, Float>>> {
        val root = json.parseToJsonElement(
            resourceText("retrieval/chw_dense_cosines_device_2026-09.json"),
        ) as JsonObject
        val keys = (root["keys"] as JsonArray).map { (it as JsonPrimitive).contentOrNull!! }
        return (root["queries"] as JsonArray).associate { el ->
            val o = el as JsonObject
            val id = (o["id"] as JsonPrimitive).contentOrNull!!
            val cos = (o["cos"] as JsonArray).map { (it as JsonPrimitive).floatOrNull ?: 0f }
            id to keys.indices
                .mapNotNull { i -> chunksByKey[keys[i]]?.let { chunk -> chunk to cos[i] } }
                .sortedByDescending { it.second }
        }
    }

    private fun loadQuestions(): List<LabelledQuestion> =
        json.parseToJsonElement(resourceText("retrieval/chw_questions_2026-09.json")).jsonArray.map { el ->
            val o = el as JsonObject
            fun s(k: String) = (o[k] as? JsonPrimitive)?.contentOrNull
            LabelledQuestion(
                id = checkNotNull(s("id")),
                lang = checkNotNull(s("lang")),
                question = checkNotNull(s("question")),
                nativeQuery = checkNotNull(s("native_query")),
                englishQuery = s("english_query"),
                acceptable = when (val a = o["acceptable"]) {
                    is JsonArray -> a.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }.toSet()
                    else -> null
                },
            )
        }

    private fun loadModules(): List<ModuleEntity> =
        json.parseToJsonElement(resourceText("retrieval/audit_corpus_2026-08.json")).jsonArray.mapNotNull { el ->
            val o = el as? JsonObject ?: return@mapNotNull null
            fun s(k: String) = (o[k] as? JsonPrimitive)?.contentOrNull
            moduleEntityFixture(
                moduleId = s("module_id") ?: return@mapNotNull null,
                moduleFamilyId = s("module_family_id") ?: return@mapNotNull null,
                titleBn = s("title_bn") ?: "",
                cardsJson = (o["cards_json"] as? JsonPrimitive)?.contentOrNull ?: "[]",
                searchMetadataJson = (o["search_metadata_json"] as? JsonPrimitive)?.contentOrNull ?: "{}",
            )
        }
}
