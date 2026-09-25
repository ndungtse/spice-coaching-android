package com.medtroniclabs.microcoaching.ai.retrieval

import com.medtroniclabs.microcoaching.ServeTuning

/**
 * Serves or refuses the one card [CardRanker] picked. It never chooses a different card.
 *
 * The policy: **a card is served only when it demonstrably shares the question's subject**,
 * because a CHW cannot tell a confidently served wrong card from a right one. The pick is
 * refused when its evidence is not [CardEvidence.Evidence.servable], or when its BM25 score
 * is under the per-language floor and it has no dense agreement. Both offline paths call
 * this before any model runs, so a refusal costs no inference and the model only rewords a
 * card that passed.
 */
object ServeGate {

    enum class RefuseReason { NO_HITS, NO_EVIDENCE, BELOW_SCORE_FLOOR }

    sealed class Decision {
        /** [all] covers every candidate, for the trace. */
        data class Serve(val pick: CardRanker.Pick, val all: List<CardEvidence.Evidence>) : Decision() {
            val hit: GroundingChunk get() = pick.hit
            val evidence: CardEvidence.Evidence get() = pick.evidence
        }

        /** [pick] is the refused card; null only when there was nothing to pick. */
        data class Refuse(
            val reason: RefuseReason,
            val evidence: List<CardEvidence.Evidence>,
            val pick: CardRanker.Pick? = null,
        ) : Decision()
    }

    /**
     * @param query the guard query: the typed question plus its other-language translation.
     * @param hits fused candidates in fused order.
     * @param clinicalTerms the [ScopeClassifier] gazetteer.
     * @param isBanglaTurn which per-language score floor applies.
     */
    fun decide(
        query: String,
        hits: List<GroundingChunk>,
        clinicalTerms: Set<String>,
        tuning: ServeTuning,
        isBanglaTurn: Boolean,
    ): Decision {
        if (hits.isEmpty()) return Decision.Refuse(RefuseReason.NO_HITS, emptyList())
        val evidences = CardEvidence.compute(query, hits, clinicalTerms, tuning)
        val pick = CardRanker.pick(hits, evidences, tuning)
            ?: return Decision.Refuse(RefuseReason.NO_HITS, evidences)
        if (!pick.evidence.servable) return Decision.Refuse(RefuseReason.NO_EVIDENCE, evidences, pick)
        val floor = if (isBanglaTurn) tuning.bnScoreFloor else tuning.enScoreFloor
        // The floor is a BM25 calibration; a dense-only entrant has no BM25 score, and its
        // cosine already cleared its own floor.
        if (pick.hit.score < floor && !pick.evidence.denseAgrees) {
            return Decision.Refuse(RefuseReason.BELOW_SCORE_FLOOR, evidences, pick)
        }
        return Decision.Serve(pick, evidences)
    }

    /** The `RANK` trace line: the pick, its fused rank, the band, and every candidate's evidence. */
    fun describeRank(decision: Decision): String? {
        val pick = when (decision) {
            is Decision.Serve -> decision.pick
            is Decision.Refuse -> decision.pick
        } ?: return null
        val all = when (decision) {
            is Decision.Serve -> decision.all
            is Decision.Refuse -> decision.evidence
        }
        return "RANK pick=${pick.hit.shortKey} fused=${pick.fusedRank} " +
            "band=[${pick.band.joinToString(",") { it.shortKey }}] | " +
            all.joinToString(" | ") { it.describe() }
    }

    /** The `GATE` trace line: what was served, or what was refused and why. */
    fun describeGate(decision: Decision): String = when (decision) {
        is Decision.Serve -> "GATE serve ${decision.hit.shortKey}"
        is Decision.Refuse -> "GATE refuse ${decision.pick?.hit?.shortKey ?: "-"} reason=${decision.reason}"
    }

    /** Both lines, for tests and tools that print one string per decision. */
    fun describe(decision: Decision): String =
        listOfNotNull(describeRank(decision), describeGate(decision)).joinToString("\n")
}
