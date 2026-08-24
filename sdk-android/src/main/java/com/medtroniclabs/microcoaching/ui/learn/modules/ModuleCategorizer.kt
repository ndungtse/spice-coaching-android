package com.medtroniclabs.microcoaching.ui.learn.modules

import android.util.Log
import com.medtroniclabs.microcoaching.ui.learn.LearnModule

/**
 * Three partitions of the input module list. **Pairwise non-disjoint** — a module can appear
 * in more than one section, e.g. an `initial_training` module the selector also surfaced
 * lands in both Training and Refresher. That is intentional: Refresher pushes "drill this
 * today" prominence regardless of module type, while Training is the type-based library.
 *
 * Each list preserves input order and holds no duplicates. [knowledge] is always empty —
 * the Knowledge section renders source documents, not modules — and is retained only
 * because callers destructure all three.
 */
data class ModuleSections(
    val refreshers: List<LearnModule>,
    val knowledge: List<LearnModule>,
    val training: List<LearnModule>,
)

/**
 * Partitions [LearnModule]s into [ModuleSections]:
 *
 *  - **Training** — `initial_training` or `digital_proficiency`, always, completed or not.
 *  - **Refresher** — the selector surfaced it ([LearnModule.fromMorningCard]) *and* it still
 *    has something to ask ([LearnModule.refresherKind] non-null). `content_update` never
 *    qualifies: it carries no drill semantics.
 *
 * A module matching neither lands in no section and is logged. That is a categorisation
 * outcome, not a failure — an unsurfaced non-training module was never meant for this screen.
 *
 * Pinned by `ModuleCategorizerTest`.
 */
object ModuleCategorizer {

    fun categorize(modules: List<LearnModule>): ModuleSections {
        val refreshers = ArrayList<LearnModule>()
        val training = ArrayList<LearnModule>()

        for (m in modules) {
            var placed = false
            if (isTraining(m)) {
                training += m
                placed = true
            }
            if (isRefresher(m)) {
                refreshers += m
                placed = true
            }
            if (!placed) {
                Log.d(
                    "ModuleCategorizer",
                    "Dropped module: ${m.title}, moduleType: ${m.moduleType}",
                )
            }
        }
        return ModuleSections(refreshers, emptyList(), training)
    }

    private fun isTraining(m: LearnModule): Boolean =
        m.moduleType == "initial_training" || m.moduleType == "digital_proficiency"

    private fun isRefresher(m: LearnModule): Boolean =
        m.moduleType != "content_update" && m.fromMorningCard && m.refresherKind != null
}
