package com.medtroniclabs.microcoaching.domain.refresher

/** What a refresher asks the CHW to do — and therefore what its tile and its sheet show. */
enum class RefresherKind { QUIZ, MICROCOACHING, LEARNING }

private const val SOURCE_QUIZ = "quiz"

/**
 * The single rule for a refresher's type, and for whether it still belongs on the list
 * (`null` = drop it).
 *
 * Every surface reads this one answer — both tiles, the sheet's phases, and refresher
 * membership — so a tile can never advertise something opening it won't deliver.
 *
 * @param source `morning_card_cache.source`, the selector's *reason* for the card.
 * @param targetQuizId `morning_card_cache.quiz_id`, already intersected with the module's
 *   own question ids by the caller — a stale id arrives here as null.
 * @param toReinforce Question ids not yet answered correctly.
 * @param cardsRead A recorded completion, not a viewed-card tally: `coaching_event` rows are
 *   purged on sync, so the tally decays to zero and would resurrect finished refreshers.
 */
fun refresherKindOf(
    source: String?,
    targetQuizId: String?,
    toReinforce: Set<String>,
    hasQuestions: Boolean,
    hasCards: Boolean,
    cardsRead: Boolean,
    isActionGap: Boolean,
): RefresherKind? {
    if (targetQuizId != null && targetQuizId in toReinforce) return RefresherKind.QUIZ

    // A wrong-referral re-drill is cleared by a passing attempt since the mistake, never by
    // mastery — so it outlives an empty to-reinforce set and re-runs the whole quiz.
    if (isActionGap && hasQuestions) return RefresherKind.QUIZ

    // A card that exists *because* a question was failed is finished once nothing is left to
    // drill. It does not decay into a reading card: that would invent a reason to show it.
    if (source == SOURCE_QUIZ || targetQuizId != null) {
        return if (toReinforce.isNotEmpty()) RefresherKind.QUIZ else null
    }

    if (toReinforce.isNotEmpty()) {
        return if (hasCards) RefresherKind.MICROCOACHING else RefresherKind.QUIZ
    }

    // Nothing to drill — worth reading once, then done.
    return if (hasCards && !cardsRead) RefresherKind.LEARNING else null
}
