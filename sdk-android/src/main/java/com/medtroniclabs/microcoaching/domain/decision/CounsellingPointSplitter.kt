package com.medtroniclabs.microcoaching.domain.decision

/**
 * Splits a free-text counselling block into 1–[maxPoints] discrete points.
 *
 * The UC-2 spec ([docs/UseCases_v2.md] §Sub-scenario A) shows the card as
 * 3 separate counselling statements, each with its own "Done" checkbox. The
 * backend response schema (`patient_message: str ≤500 chars`) and the CACHED
 * scenario `bangla_card.body` field are both *single* strings — so the SDK
 * has to recover the points client-side.
 *
 * Splitting strategy, in order:
 *   1. Try line-based split (existing behaviour) — handles SOPs/cards already
 *      authored as bullet lists.
 *   2. If we got fewer than 2 lines, fall back to sentence-boundary split:
 *      Bangla `।` (DANDA U+0964) and Latin `.!?`. Empty/short fragments are
 *      dropped.
 *
 * Common bullet-list prefixes (`•`, `-`, `·`, numbering) are stripped.
 */
internal object CounsellingPointSplitter {

    private val SENTENCE_BOUNDARY = Regex("(?<=[।.!?])\\s+")
    private val LEADING_BULLET = Regex("^[\\s•\\-·*]+")
    private val LEADING_NUMBER = Regex("^\\d+[.):]\\s*")

    fun split(raw: String, maxPoints: Int = 5): List<String> {
        if (raw.isBlank()) return emptyList()

        val byLine = raw.lines()
            .map { it.cleanPoint() }
            .filter { it.isNotBlank() }

        val candidates = if (byLine.size >= 2) {
            byLine
        } else {
            // Single paragraph — split on sentence boundaries.
            (byLine.firstOrNull() ?: raw)
                .split(SENTENCE_BOUNDARY)
                .map { it.cleanPoint() }
                .filter { it.length >= 8 }
        }

        return candidates.take(maxPoints)
    }

    private fun String.cleanPoint(): String =
        trim()
            .replace(LEADING_BULLET, "")
            .replace(LEADING_NUMBER, "")
            .trim()
}
