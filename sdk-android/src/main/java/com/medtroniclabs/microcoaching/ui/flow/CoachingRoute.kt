package com.medtroniclabs.microcoaching.ui.flow

/**
 * All routes in [CoachingNavGraph].
 *
 * Route strings are intentionally prefixed with "coaching_" to avoid
 * conflicts if a host app's NavHost ever merges this graph.
 */
sealed class CoachingRoute(val route: String) {

    /** One-time coach mark overlay (first launch only). */
    object CoachMark : CoachingRoute("coaching_coach_mark")

    /** Three-slide onboarding carousel. */
    object OnboardingSlides : CoachingRoute("coaching_onboarding_slides")

    /** Module card — "Start learning" entry point. */
    object ModuleReady : CoachingRoute("coaching_module_ready")

    /**
     * Full grid of every module of a given type ("training" | "knowledge" | "refresher"),
     * reached via the "See all" link on [ModuleReady]'s section headers.
     * Full route: `coaching_all_modules/{moduleType}`.
     */
    object AllModules : CoachingRoute("coaching_all_modules/{moduleType}") {
        const val ARG_MODULE_TYPE = "moduleType"
        fun routeFor(moduleType: String) = "coaching_all_modules/$moduleType"
    }

    /** Reference data lesson (BP thresholds table). */
    object LessonContent : CoachingRoute("coaching_lesson_content")

    /** Lesson completion screen — expandable table, "Back to modules". */
    object LessonComplete : CoachingRoute("coaching_lesson_complete")

    /**
     * Per-question quiz screen. Carries the question index as a path arg.
     * Full route: `coaching_quiz_question/{questionIndex}`
     */
    object QuizQuestion : CoachingRoute("coaching_quiz_question/{questionIndex}") {
        const val ARG_QUESTION_INDEX = "questionIndex"
        fun routeFor(index: Int) = "coaching_quiz_question/$index"
    }

    /** Final quiz result — score arc, badge, "Back to HOME". */
    object QuizResult : CoachingRoute("coaching_quiz_result")

    /** Card-by-card lesson player — shows cards_json content before the quiz. */
    object LessonPlayer : CoachingRoute("coaching_lesson_player")

    // ── Training requests (SK only) ────────────────────────────────────────────

    /** Training-request hub — the CHW's past requests + "New Request". */
    object TrainingRequests : CoachingRoute("coaching_training_requests")

    /** Training-request form — reached from the hub's "New Request"; the picker chooses the module. */
    object TrainingRequestForm : CoachingRoute("coaching_training_request_form")

    // ── PO dashboard drill-downs ───────────────────────────────────────────────

    /** "Active this week" — SKs grouped by status. */
    object ActiveSks : CoachingRoute("coaching_po_active_sks")

    /** "Chatbot Usage" — SKs grouped by chatbot usage. */
    object ChatbotUsage : CoachingRoute("coaching_po_chatbot_usage")

    /** "Modules Completed" — per-module completion accordion. */
    object ModulesCompleted : CoachingRoute("coaching_po_modules_completed")

    /** Single SK detail ("My SK"). Carries the SK id as a path arg. */
    object SkDetail : CoachingRoute("coaching_po_sk_detail/{skId}") {
        const val ARG_SK_ID = "skId"
        fun routeFor(skId: String) = "coaching_po_sk_detail/$skId"
    }

    /** "Top Searched Existing" module drill-down. Carries module_id. */
    object SearchedModuleDetail : CoachingRoute("coaching_po_searched_module/{moduleId}") {
        const val ARG_MODULE_ID = "moduleId"
        fun routeFor(moduleId: String) = "coaching_po_searched_module/$moduleId"
    }

    /** Knowledge-document usage drill-down. Carries source_document_id. */
    object DocumentUsageDetail : CoachingRoute("coaching_po_document/{documentId}") {
        const val ARG_DOCUMENT_ID = "documentId"
        fun routeFor(documentId: String) = "coaching_po_document/$documentId"
    }

    /** "Top Searched Suggested" module/topic drill-down. Carries suggestion_id. */
    object SuggestionDetail : CoachingRoute("coaching_po_suggestion/{suggestionId}") {
        const val ARG_SUGGESTION_ID = "suggestionId"
        fun routeFor(suggestionId: String) = "coaching_po_suggestion/$suggestionId"
    }

    /**
     * Full "Show all" list for one dashboard section. Carries the section token and the
     * selected date range (from/to millis) so the full screen matches the tab's window.
     */
    object SectionAll : CoachingRoute("coaching_po_section/{section}/{from}/{to}") {
        const val ARG_SECTION = "section"
        const val ARG_FROM = "from"
        const val ARG_TO = "to"
        fun routeFor(section: String, from: Long, to: Long) = "coaching_po_section/$section/$from/$to"
    }
}
