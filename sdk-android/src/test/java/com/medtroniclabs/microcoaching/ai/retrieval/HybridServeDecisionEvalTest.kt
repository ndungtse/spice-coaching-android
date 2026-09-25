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
 * The merge gate for HYBRID (BM25 + dense) retrieval, sibling of
 * [ServeDecisionEvalTest] which pins the BM25-only path.
 *
 * Dense scores come from a committed fixture rather than a live encoder, so CI
 * needs no model: `retrieval/dense_cosines_2026-09.json` holds the cosine of every
 * labelled query against every card, computed offline with the production embedding
 * model (google/embeddinggemma-300m, cards embedded as title+body+authored hints).
 * Regeneration: `ignored/embeddings-eval/gen_fixture.py`.
 *
 * The fusion and gate under test are exactly the production ones —
 * [GroundingSelector.select] with a `dense` candidate list and [ServeGate] with
 * the cosine evidence channel. Only the query embedding is replayed from the fixture.
 */
class HybridServeDecisionEvalTest {

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

    private data class Tally(var right: Int = 0, var goodRefuse: Int = 0, var wrong: Int = 0, var miss: Int = 0)

    /**
     * Hybrid bounds at the calibrated tuning (cosFloor 0.50, rrfK 60, denseTopK 3):
     * `goodRefuse` may only rise and `wrong` only fall; `right` is printed. Loosening a
     * bound is a product decision, not a test edit. The BN `wrong` bound admits a
     * dense-only entrant outranking a lexical match on hint overlap; that ordering is a
     * ranking question for the corpus-derived gazetteer, not for a per-question rule.
     */
    private val enBaseline = Tally(right = 5, goodRefuse = 14, wrong = 5, miss = 2)
    private val bnBaseline = Tally(right = 10, goodRefuse = 15, wrong = 2, miss = 0)

    @Test
    fun `hybrid retrieval never regresses the bm25-only pins`() {
        val modules = loadModules()
        val index = ModuleKnowledgeIndex.build(modules)
        val scope = ScopeClassifier.buildFrom(modules)
        val chunksByKey = modules
            .flatMap { m -> ModuleCorpusParser.extractCardChunks(m).map { it.first } }
            .associateBy { "${it.moduleFamilyId.take(8)}:${it.positionalId}" }
        val denseByQuery = loadDenseCosines(chunksByKey)
        val questions = loadQuestions()

        val tallies = mapOf("en" to Tally(), "bn" to Tally())
        val failures = StringBuilder()

        for (q in questions) {
            val (servedKey, why) = runPipeline(q, index, scope, denseByQuery.getValue(q.id))
            val tally = tallies.getValue(q.lang)
            when {
                servedKey == null && q.acceptable == null -> tally.goodRefuse++
                servedKey == null -> {
                    tally.miss++
                    failures.appendLine("MISS  ${q.id}: refused but acceptable=${q.acceptable}\n      $why")
                }
                q.acceptable == null -> {
                    tally.wrong++
                    failures.appendLine("WRONG ${q.id}: served $servedKey but should refuse\n      $why")
                }
                servedKey in q.acceptable -> tally.right++
                else -> {
                    tally.wrong++
                    failures.appendLine("WRONG ${q.id}: served $servedKey not in ${q.acceptable}\n      $why")
                }
            }
        }

        val en = tallies.getValue("en")
        val bn = tallies.getValue("bn")
        println("HybridServeDecisionEval dashboard —")
        println("  EN: right=${en.right} goodRefuse=${en.goodRefuse} wrong=${en.wrong} miss=${en.miss}")
        println("  BN: right=${bn.right} goodRefuse=${bn.goodRefuse} wrong=${bn.wrong} miss=${bn.miss}")
        println("  (right is printed, not pinned: accuracy is judged on the untuned question banks)")
        val report = linkedMapOf("EN" to EvalReport.Tally(), "BN" to EvalReport.Tally())
        for (q in questions) {
            report.getValue(q.lang.uppercase()).add(EvalReport.verdict(q.acceptable, runPipeline(q, index, scope, dense = denseByQuery[q.id].orEmpty()).first))
        }
        EvalReport.print("Trainer set — BM25+dense", report)
        print(failures)

        check(en.goodRefuse >= enBaseline.goodRefuse) { "EN goodRefuse regressed: ${en.goodRefuse} < ${enBaseline.goodRefuse}" }
        check(en.wrong <= enBaseline.wrong) { "EN wrong grew: ${en.wrong} > ${enBaseline.wrong}" }
        check(en.miss <= enBaseline.miss) { "EN miss grew: ${en.miss} > ${enBaseline.miss}" }
        check(bn.goodRefuse >= bnBaseline.goodRefuse) { "BN goodRefuse regressed: ${bn.goodRefuse} < ${bnBaseline.goodRefuse}" }
        check(bn.wrong <= bnBaseline.wrong) { "BN wrong grew: ${bn.wrong} > ${bnBaseline.wrong}" }
        check(bn.miss <= bnBaseline.miss) { "BN miss grew: ${bn.miss} > ${bnBaseline.miss}" }
        assertEquals(26, en.right + en.goodRefuse + en.wrong + en.miss)
        assertEquals(26, bn.right + bn.goodRefuse + bn.wrong + bn.miss)
    }

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

    // ── resource loading ─────────────────────────────────────────────────────

    private fun resourceText(name: String): String =
        checkNotNull(javaClass.classLoader?.getResourceAsStream(name)) { "missing test resource $name" }
            .bufferedReader().readText()

    /** Per query id: dense candidates (chunk + cosine) sorted descending, full corpus depth. */
    private fun loadDenseCosines(
        chunksByKey: Map<String, GroundingChunk>,
    ): Map<String, List<Pair<GroundingChunk, Float>>> {
        val root = json.parseToJsonElement(resourceText("retrieval/dense_cosines_2026-09.json")) as JsonObject
        val keys = (root["keys"] as JsonArray).map { (it as JsonPrimitive).contentOrNull!! }
        return (root["queries"] as JsonArray).associate { el ->
            val o = el as JsonObject
            val id = (o["id"] as JsonPrimitive).contentOrNull!!
            val cos = (o["cos"] as JsonArray).map { (it as JsonPrimitive).floatOrNull ?: 0f }
            val ranked = keys.indices
                .mapNotNull { i -> chunksByKey[keys[i]]?.let { chunk -> chunk to cos[i] } }
                .sortedByDescending { it.second }
            id to ranked
        }
    }

    private fun loadQuestions(): List<LabelledQuestion> =
        json.parseToJsonElement(resourceText("retrieval/audit_labelled.json")).jsonArray.map { el ->
            val o = el as JsonObject
            fun s(k: String) = (o[k] as? JsonPrimitive)?.contentOrNull
            val acceptable = when (val a = o["acceptable"]) {
                is JsonArray -> a.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }.toSet()
                else -> null
            }
            LabelledQuestion(
                id = checkNotNull(s("id")),
                lang = checkNotNull(s("lang")),
                question = checkNotNull(s("question")),
                nativeQuery = checkNotNull(s("native_query")),
                englishQuery = s("english_query"),
                acceptable = acceptable,
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
