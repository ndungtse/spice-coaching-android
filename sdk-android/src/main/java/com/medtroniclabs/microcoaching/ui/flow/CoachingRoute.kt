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
     * Full grid of every module of a given type ("training" | "knowledge"),
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
}
