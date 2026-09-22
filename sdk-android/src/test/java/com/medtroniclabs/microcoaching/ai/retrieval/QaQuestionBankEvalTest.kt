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
 * Runs an externally authored question bank through the production retrieval pipeline.
 *
 * `retrieval/qa_questions_bn.json` holds one row per question with a `source` naming the
 * run it came from; new runs append rows under a new source. No gate threshold is tuned on
 * this bank, so it measures generalisation rather than fit.
 *
 * Sources with device-computed cosines in `retrieval/qa_dense_cosines_device.json` run
 * BM25 + dense beside BM25-only; sources without run BM25-only. Scored with [EvalReport].
 *
 * Pinned on the safety axes only: wrong-family serves may not grow and no off-topic
 * question may be answered. Accuracy is printed so a change is read, not enforced here.
 */
class QaQuestionBankEvalTest {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }
    private val tuning = ServeTuning()

    private data class Row(
        val id: String,
        val source: String,
        val question: String,
        val nativeQuery: String,
        val englishQuery: String?,
        val acceptable: Set<String>?,
        val qaStatus: String?,
    )

    /**
     * Wrong-family serves measured by this replay when each source was added; may only
     * fall. A device turn also carries an ML Kit translation of the question, which this
     * replay cannot run, so device counts differ slightly and are not used here.
     */
    private val wrongBaseline = mapOf(
        "qa-uc2-2026-09-17" to 3,
        "qa-uc3-2026-09-21" to 2,
    )

    @Test
    fun `the QA question bank never grows wrong-family serves or answers off-topic`() {
        val modules = loadModules()
        val index = ModuleKnowledgeIndex.build(modules)
        val scope = ScopeClassifier.buildFrom(modules)
        val chunksByKey = modules
            .flatMap { m -> ModuleCorpusParser.extractCardChunks(m).map { it.first } }
            .associateBy { "${it.moduleFamilyId.take(8)}:${it.positionalId}" }
        val dense = loadDenseCosines(chunksByKey)
        val bySource = loadRows().groupBy { it.source }

        for ((source, rows) in bySource) {
            val hasCosines = rows.any { it.id in dense }
            val modes = if (hasCosines) listOf("BM25-only", "BM25+dense") else listOf("BM25-only")

            val outcomes = modes.associateWith { mode ->
                rows.associate { r ->
                    r.id to runPipeline(r, index, scope, if (mode == "BM25-only") emptyList() else dense[r.id].orEmpty())
                }
            }
            val tallies = modes.associateWith { EvalReport.Tally() }
            var decisionAgree = 0
            var decisionKnown = 0
            for (mode in modes) for (r in rows) {
                val served = outcomes.getValue(mode).getValue(r.id).first
                tallies.getValue(mode).add(EvalReport.verdict(r.acceptable, served))
                if (mode == modes.last() && r.qaStatus != null) {
                    decisionKnown++
                    if ((r.qaStatus == "ok") == (served != null)) decisionAgree++
                }
            }
            EvalReport.print("QA bank: $source — ${rows.size} questions", tallies)
            println("  serve/refuse agrees with the QA device: $decisionAgree/$decisionKnown (${modes.last()})")

            val shipped = outcomes.getValue(modes.last())
            val refusals = rows.filter { it.acceptable != null && shipped.getValue(it.id).first == null }
            println("  why the ${refusals.size} false refusals refused (${modes.last()}):")
            for (r in refusals) {
                println("    ${r.id.padEnd(9)} exp=${r.acceptable!!.first().padEnd(12)} ${shipped.getValue(r.id).second.take(230)}")
            }
            val wrong = rows.filter { EvalReport.verdict(it.acceptable, shipped.getValue(it.id).first) == EvalReport.Verdict.WRONG_FAMILY }
            println("  the ${wrong.size} wrong-family serves (${modes.last()}):")
            for (r in wrong) {
                println("    ${r.id.padEnd(9)} exp=${r.acceptable!!.first().padEnd(12)} q=\"${r.question.take(60)}\"")
                println("             ${shipped.getValue(r.id).second.take(200)}")
            }
            println()

            for (t in tallies.values) assertEquals(rows.size, t.total)
            val shippedTally = tallies.getValue(modes.last())
            val bound = wrongBaseline[source] ?: shippedTally.wrongFamily
            check(shippedTally.wrongFamily <= bound) {
                "$source wrong-family serves grew: ${shippedTally.wrongFamily} > $bound"
            }
            check(shippedTally.servedIrrelevant == 0) { "$source answered an off-topic question" }
        }
    }

    private fun runPipeline(
        q: Row,
        index: ModuleKnowledgeIndex,
        scope: ScopeClassifier,
        dense: List<Pair<GroundingChunk, Float>>,
    ): Pair<String?, String> {
        if (scope.isOutOfScope(q.question)) return null to "L0 deny-list"
        val hits = GroundingSelector.select(
            nativeQuery = q.nativeQuery,
            englishQuery = q.englishQuery,
            nativeLanguage = ModuleKnowledgeIndex.Lang.BN,
            index = index,
            k = 3,
            scoreThreshold = 1.5f,
            dense = dense.take(tuning.denseTopK),
            rrfK = tuning.rrfK,
            denseAdmitFloor = tuning.cosFloor,
        ).hits
        if (hits.isEmpty()) return null to "no BM25 hit cleared the floor"
        val decision = ServeDecision.decide(
            query = q.question, hits = hits, clinicalTerms = scope.scopeTerms(),
            tuning = tuning, isBanglaTurn = true,
        )
        val why = ServeDecision.describe(decision)
        val top = (decision as? ServeDecision.Decision.Serve)?.hit ?: return null to why
        return "${top.moduleFamilyId.take(8)}:${top.positionalId}" to why
    }

    private fun resourceText(name: String): String =
        checkNotNull(javaClass.classLoader?.getResourceAsStream(name)) { "missing $name" }.bufferedReader().readText()

    private fun loadDenseCosines(chunksByKey: Map<String, GroundingChunk>): Map<String, List<Pair<GroundingChunk, Float>>> {
        val root = json.parseToJsonElement(resourceText("retrieval/qa_dense_cosines_device.json")) as JsonObject
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

    private fun loadRows(): List<Row> =
        json.parseToJsonElement(resourceText("retrieval/qa_questions_bn.json")).jsonArray.map { el ->
            val o = el as JsonObject
            fun s(k: String) = (o[k] as? JsonPrimitive)?.contentOrNull
            Row(
                id = checkNotNull(s("id")),
                source = checkNotNull(s("source")),
                question = checkNotNull(s("question")),
                nativeQuery = checkNotNull(s("native_query")),
                englishQuery = s("english_query"),
                acceptable = when (val a = o["acceptable"]) {
                    is JsonArray -> a.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }.toSet()
                    else -> null
                },
                qaStatus = s("qa_status"),
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
