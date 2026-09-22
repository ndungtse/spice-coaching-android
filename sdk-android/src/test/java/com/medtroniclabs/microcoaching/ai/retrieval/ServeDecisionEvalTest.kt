package com.medtroniclabs.microcoaching.ai.retrieval

import com.medtroniclabs.microcoaching.data.db.entity.ModuleEntity
import com.medtroniclabs.microcoaching.data.db.entity.moduleEntityFixture
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The merge gate for offline serve/refuse behaviour: a change that serves more wrong
 * cards, or loses an answer it used to get right, fails here.
 *
 * Runs the real retrieval-only pipeline ([GroundingSelector] + [ServeDecision]) over a
 * labelled question set and a corpus snapshot, both committed as test resources — so
 * unlike [RealCorpusProbeTest] and [GoldenBengaliBenchmarkTest] this needs no
 * environment and always runs in CI.
 *
 *  - `retrieval/audit_corpus_2026-08.json` — a 26-module device corpus in the
 *    `module_cache` dump shape, produced by `ignored/_chat-audit/export_corpus.py`
 *    with image blocks stripped, since retrieval only reads text nodes.
 *  - `retrieval/audit_labelled.json` — 52 rows of
 *    `{id, lang, question, native_query, english_query, acceptable}`. `acceptable`
 *    lists every card that answers the question, fully or partly, as
 *    `"<family8>:<cardIndex>"`, or is the string `"refuse"` when the corpus holds no
 *    answer and refusing is the correct outcome. English rows carry the MLKit EN→BN
 *    query recorded on-device, because a JVM test cannot run MLKit.
 *
 * Each question lands in one of four verdicts: RIGHT (served an acceptable card),
 * GOOD_REFUSE (refused where refusal was correct), WRONG (served anything else,
 * including serving where it should refuse), MISS (refused although an acceptable
 * card exists).
 *
 * @see ServeDecision
 */
class ServeDecisionEvalTest {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    private data class LabelledQuestion(
        val id: String,
        val lang: String,
        val question: String,
        val nativeQuery: String,
        val englishQuery: String?,
        /** null means the correct outcome is a refusal. */
        val acceptable: Set<String>?,
    )

    private data class Tally(var right: Int = 0, var goodRefuse: Int = 0, var wrong: Int = 0, var miss: Int = 0)

    /**
     * The pipeline's current measured behaviour, asserted as bounds: `wrong` may only
     * fall, `goodRefuse` may only rise; `right` is printed. Tighten them whenever a change
     * improves the numbers, so the gain cannot silently regress later; loosening one
     * means accepting worse answers and needs a decision behind it, not a test edit.
     *
     * `wrong == 0` is the target end state. The gap to it is corpus coverage as much
     * as ranking: several questions have no answering card, and the remaining wrong
     * serves cluster on cards whose authored hints pull them toward many questions.
     */
    private val enBaseline = Tally(right = 4, goodRefuse = 14, wrong = 5, miss = 3)
    private val bnBaseline = Tally(right = 9, goodRefuse = 15, wrong = 1, miss = 1)

    @Test
    fun `wrong-card serves never exceed the bound and good outcomes never regress`() {
        val modules = loadModules()
        val index = ModuleKnowledgeIndex.build(modules)
        val scope = ScopeClassifier.buildFrom(modules)
        val questions = loadQuestions()

        val tallies = mapOf("en" to Tally(), "bn" to Tally())
        val failures = StringBuilder()

        for (q in questions) {
            val (servedKey, why) = runPipeline(q, index, scope)
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
        println("ServeDecisionEval dashboard —")
        println("  EN: right=${en.right} goodRefuse=${en.goodRefuse} wrong=${en.wrong} miss=${en.miss}")
        println("  BN: right=${bn.right} goodRefuse=${bn.goodRefuse} wrong=${bn.wrong} miss=${bn.miss}")
        println("  (right is printed, not pinned: accuracy is judged on the untuned question banks)")
        val report = linkedMapOf("EN" to EvalReport.Tally(), "BN" to EvalReport.Tally())
        for (q in questions) {
            report.getValue(q.lang.uppercase()).add(EvalReport.verdict(q.acceptable, runPipeline(q, index, scope).first))
        }
        EvalReport.print("Trainer set — BM25-only", report)
        print(failures)

        check(en.goodRefuse >= enBaseline.goodRefuse) { "EN goodRefuse regressed: ${en.goodRefuse} < ${enBaseline.goodRefuse}" }
        check(en.wrong <= enBaseline.wrong) { "EN wrong grew: ${en.wrong} > ${enBaseline.wrong}" }
        check(bn.goodRefuse >= bnBaseline.goodRefuse) { "BN goodRefuse regressed: ${bn.goodRefuse} < ${bnBaseline.goodRefuse}" }
        check(bn.wrong <= bnBaseline.wrong) { "BN wrong grew: ${bn.wrong} > ${bnBaseline.wrong}" }
        // Totals must account for every question — a parsing slip must not pass silently.
        assertEquals(26, en.right + en.goodRefuse + en.wrong + en.miss)
        assertEquals(26, bn.right + bn.goodRefuse + bn.wrong + bn.miss)
    }

    /**
     * Replays the retrieval-only serve path for one question, returning the served
     * card's `"<family8>:<cardIndex>"` key (or null when it refuses) alongside the
     * decision's own explanation, which is printed for every non-RIGHT outcome.
     *
     * Under ExtendedClinical strictness L1 is advisory, so only the L0 deny-list can
     * refuse before retrieval.
     */
    private fun runPipeline(
        q: LabelledQuestion,
        index: ModuleKnowledgeIndex,
        scope: ScopeClassifier,
    ): Pair<String?, String> {
        if (scope.isOutOfScope(q.question)) return null to "L0 deny-list"

        val selection = GroundingSelector.select(
            nativeQuery = q.nativeQuery,
            englishQuery = q.englishQuery,
            nativeLanguage = ModuleKnowledgeIndex.Lang.BN,
            index = index,
            // Production values: ChatViewModel.GROUNDING_K and the ChatTuning default.
            k = 3,
            scoreThreshold = 1.5f,
        )
        val hits = selection.hits
        if (hits.isEmpty()) return null to "no hits cleared the BM25 floor"

        // The guard query the serve paths assemble: typed text plus its translation.
        val guardQuery = when {
            q.lang == "en" && q.nativeQuery != q.question -> "${q.question} ${q.nativeQuery}"
            q.lang == "bn" && q.englishQuery != null && q.englishQuery != q.question ->
                "${q.question} ${q.englishQuery}"
            else -> q.question
        }
        val decision = ServeDecision.decide(
            query = guardQuery,
            hits = hits,
            clinicalTerms = scope.scopeTerms(),
            tuning = com.medtroniclabs.microcoaching.ServeTuning(),
            isBanglaTurn = q.lang == "bn",
        )
        val why = ServeDecision.describe(decision)
        val top = (decision as? ServeDecision.Decision.Serve)?.hit ?: return null to why
        return "${top.moduleFamilyId.take(8)}:${top.positionalId}" to why
    }

    // ── resource loading ─────────────────────────────────────────────────────

    private fun resourceText(name: String): String =
        checkNotNull(javaClass.classLoader?.getResourceAsStream(name)) { "missing test resource $name" }
            .bufferedReader().readText()

    private fun loadQuestions(): List<LabelledQuestion> =
        json.parseToJsonElement(resourceText("retrieval/audit_labelled.json")).jsonArray.map { el ->
            val o = el as JsonObject
            fun s(k: String) = (o[k] as? JsonPrimitive)?.contentOrNull
            val acceptable = when (val a = o["acceptable"]) {
                is JsonArray -> a.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }.toSet()
                else -> null // the string "refuse"
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
