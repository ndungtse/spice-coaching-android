package com.medtroniclabs.microcoaching.ai.retrieval

/**
 * Scores one retrieval outcome against a labelled question and prints tallies as one table
 * shape, so every eval set is read the same way.
 *
 * A served-card key is `"<family8>:<cardIndex>"`. `acceptable` lists the keys that answer
 * the question; null means the correct outcome is a refusal.
 */
internal object EvalReport {

    enum class Verdict { STRICT, FAMILY, WRONG_FAMILY, FALSE_REFUSE, GOOD_REFUSE, SERVED_IRRELEVANT }

    fun verdict(acceptable: Set<String>?, served: String?): Verdict = when {
        served == null && acceptable == null -> Verdict.GOOD_REFUSE
        served == null -> Verdict.FALSE_REFUSE
        acceptable == null -> Verdict.SERVED_IRRELEVANT
        served in acceptable -> Verdict.STRICT
        acceptable.any { it.substringBefore(':') == served.substringBefore(':') } -> Verdict.FAMILY
        else -> Verdict.WRONG_FAMILY
    }

    class Tally {
        private val counts = Verdict.entries.associateWithTo(linkedMapOf()) { 0 }
        fun add(v: Verdict) { counts[v] = counts.getValue(v) + 1 }
        operator fun get(v: Verdict): Int = counts.getValue(v)

        val strict get() = this[Verdict.STRICT]
        val family get() = this[Verdict.FAMILY]
        val wrongFamily get() = this[Verdict.WRONG_FAMILY]
        val falseRefuse get() = this[Verdict.FALSE_REFUSE]
        val goodRefuse get() = this[Verdict.GOOD_REFUSE]
        val servedIrrelevant get() = this[Verdict.SERVED_IRRELEVANT]
        val total get() = counts.values.sum()
        /** Questions that have an answer in the corpus. */
        val clinical get() = total - goodRefuse - servedIrrelevant

        // The trainer-set pins use a four-bucket vocabulary in which a neighbour card is a
        // wrong serve. These map onto it exactly so those pins keep their meaning.
        val legacyRight get() = strict
        val legacyWrong get() = family + wrongFamily + servedIrrelevant
        val legacyMiss get() = falseRefuse
    }

    fun print(title: String, columns: Map<String, Tally>) {
        val names = columns.keys.toList()
        println("═══ $title ═══")
        println("  ${"".padEnd(36)}${names.joinToString("") { it.padStart(14) }}")
        fun line(label: String, f: (Tally) -> String) =
            println("  ${label.padEnd(36)}${names.joinToString("") { f(columns.getValue(it)).padStart(14) }}")
        line("exact card") { "${it.strict}/${it.clinical}" }
        line("right module (exact + neighbour)") { "${it.strict + it.family}/${it.clinical}" }
        line("wrong family served") { "${it.wrongFamily}" }
        line("false refusals") { "${it.falseRefuse}" }
        line("off-topic refused") { "${it.goodRefuse}/${it.goodRefuse + it.servedIrrelevant}" }
        line("off-topic answered") { "${it.servedIrrelevant}" }
        println()
    }
}
