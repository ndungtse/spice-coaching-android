package com.medtroniclabs.microcoaching.ai.retrieval

/**
 * One piece of curriculum content surfaced by [ModuleKnowledgeIndex] as evidence
 * for the LLM's answer (B1 of chat_plan.md).
 *
 * Chunks come from two sources today — module cards and quiz items. The carrier
 * is intentionally untyped beyond [source] so the validator and refusal layers
 * (B4) can treat them uniformly. The English half (`titleEn` / `bodyEn`) is what
 * goes into the LLM prompt; the Bangla half is kept for refusal-path fallback
 * (L4 serves quiz `bodyBn` verbatim when the LLM's answer is rejected).
 */
data class GroundingChunk(
    val source: Source,
    val moduleFamilyId: String,
    val positionalId: Int,
    val titleEn: String?,
    val bodyEn: String?,
    val titleBn: String?,
    val bodyBn: String?,
    val score: Float,
) {
    enum class Source { CARD, QUIZ }

    /** Identifier suitable for telemetry / debugging. Stable per (module, source, position). */
    val chunkId: String = "$moduleFamilyId:${source.name.lowercase()}:$positionalId"

    /** English text injected into the LLM reference block. Falls back to BN if no EN side. */
    fun referenceText(): String = when {
        !bodyEn.isNullOrBlank() -> {
            val header = titleEn ?: titleBn ?: source.name
            "$header — $bodyEn"
        }
        !bodyBn.isNullOrBlank() -> {
            val header = titleEn ?: titleBn ?: source.name
            "$header — $bodyBn"
        }
        else -> titleEn ?: titleBn ?: ""
    }
}
