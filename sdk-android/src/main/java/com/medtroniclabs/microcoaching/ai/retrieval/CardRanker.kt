package com.medtroniclabs.microcoaching.ai.retrieval

import com.medtroniclabs.microcoaching.ServeTuning

/**
 * Picks the one card a turn will offer, from the fused candidates.
 *
 * Every candidate competes, servable or not: choosing the card and judging it are separate
 * steps, so the gate never swaps in a different card. Candidates within [ServeTuning.promoteRatio]
 * of the top BM25 score compete, plus any with dense agreement, which has no BM25 score to
 * clear the ratio with. Among them the first difference wins:
 *  1. dense dominance
 *  2. any topic term, so a card about the subject beats one about only the population
 *  3. title and hint overlap, since authors write hints as the questions a card answers
 *  4. condition terms plus concepts
 *  5. dense agreement
 *  6. BM25 score
 */
object CardRanker {

    /** The chosen card, its evidence, its 1-based position in the fused order, and the band it won in. */
    data class Pick(
        val hit: GroundingChunk,
        val evidence: CardEvidence.Evidence,
        val fusedRank: Int,
        val band: List<GroundingChunk>,
    )

    fun pick(
        hits: List<GroundingChunk>,
        evidences: List<CardEvidence.Evidence>,
        tuning: ServeTuning,
    ): Pick? {
        if (hits.isEmpty()) return null
        val byChunk = evidences.associateBy { it.chunkId }
        val topScore = hits.first().score
        val band = hits.filter {
            it.score >= topScore * tuning.promoteRatio || byChunk.getValue(it.chunkId).denseAgrees
        }.ifEmpty { listOf(hits.first()) }
        val chosen = band.maxWithOrNull(
            compareBy(
                { byChunk.getValue(it.chunkId).denseDominant },
                { byChunk.getValue(it.chunkId).topicTermCount > 0 },
                { byChunk.getValue(it.chunkId).titleHintOverlap },
                { byChunk.getValue(it.chunkId).conditionTerms.size + byChunk.getValue(it.chunkId).sharedConcepts.size },
                { byChunk.getValue(it.chunkId).denseAgrees },
                { it.score },
            ),
        ) ?: hits.first()
        return Pick(
            hit = chosen,
            evidence = byChunk.getValue(chosen.chunkId),
            fusedRank = hits.indexOfFirst { it.chunkId == chosen.chunkId } + 1,
            band = band,
        )
    }
}
