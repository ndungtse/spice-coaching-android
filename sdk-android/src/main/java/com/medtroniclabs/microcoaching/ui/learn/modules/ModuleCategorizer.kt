package com.medtroniclabs.microcoaching.ui.learn.modules

import android.util.Log
import com.medtroniclabs.microcoaching.ui.learn.LearnModule

/**
 * Three partitions of the input module list. **Pairwise non-disjoint** —
 * a single module can appear in more than one section (e.g. an
 * `initial_training` module that's also morning-card-surfaced with wrong
 * questions lands in **both** Training and Refresher). This is intentional:
 * the Refresher row exists to push "drill this today" prominence
 * regardless of the underlying module type, while Training/Knowledge are
 * the type-based libraries.
 *
 * Invariant: each list preserves the input order and contains no
 * duplicates among itself.
 *
 * Modules that don't match any rule are silently dropped (logged via
 * [android.util.Log] at debug level). See [ModuleCategorizer] for the
 * rule contract.
 */
data class ModuleSections(
    val refreshers: List<LearnModule>,
    val knowledge: List<LearnModule>,
    val training: List<LearnModule>,
)

/**
 * Partitions [LearnModule]s into the three sections rendered by
 * [com.medtroniclabs.microcoaching.ui.learn.modules.ModulesScreen]:
 *
 *  - **Training** — `moduleType == "initial_training"`, always. Even
 *    completed initial-training modules render here (the "completed
 *    wins → Knowledge" rule was removed by product direction).
 *  - **Knowledge** — `moduleType == "digital_proficiency"`, always.
 *    Exclusively type-based; status / source / wrong-question count
 *    don't affect placement.
 *  - **Refresher** — "active drilling" queue. Unchanged from the
 *    previous rule:
 *      `(source != null OR moduleType == "refresher")
 *       AND wrongQuestionCount > 0
 *       AND status != "completed"`
 *    This means a morning-card-surfaced `initial_training` module with
 *    wrong questions appears in **both** Refresher *and* Training — the
 *    sections overlap by design (see [ModuleSections]).
 *
 * Modules matching **none** of the above are silently dropped (logged
 * at debug level). Today this includes:
 *   - `content_update` modules (no longer have a section)
 *   - stale `refresher`-type modules with no wrong questions and no
 *     morning-card source
 *   - completed `refresher`-type modules
 *
 * The contract is pinned by `ModuleCategorizerTest`.
 *
 * TODO: TO think about
 *
 * `digital_proficiency` modules now all land in Knowledge regardless of
 * progress. `content_update` modules currently have no home. If product
 * wants those to surface differently later, adjust the predicates here.
 */
object ModuleCategorizer {

    fun categorize(modules: List<LearnModule>): ModuleSections {
        val refreshers = ArrayList<LearnModule>()
        val knowledge = ArrayList<LearnModule>()
        val training = ArrayList<LearnModule>()

        for (m in modules) {
            // Each module is offered to all three section predicates
            // independently — overlap is allowed (a refresher-by-source
            // initial_training module can land in both Training and
            // Refresher; see ModuleSections docs).
            var placed = false
            if (isTraining(m)) {
                training += m
                placed = true
            }
            if (isKnowledge(m)) {
                knowledge += m
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
        return ModuleSections(refreshers, knowledge, training)
    }

    private fun isTraining(m: LearnModule): Boolean =
        m.moduleType == "initial_training"

    private fun isKnowledge(m: LearnModule): Boolean =
        m.moduleType == "digital_proficiency"

    private fun isRefresher(m: LearnModule): Boolean {
        val isRefresherByNature = m.source != null || m.moduleType == "refresher"
        return isRefresherByNature &&
            (m.wrongQuestionCount ?: 0) > 0 &&
            m.status != "completed"
    }
}
