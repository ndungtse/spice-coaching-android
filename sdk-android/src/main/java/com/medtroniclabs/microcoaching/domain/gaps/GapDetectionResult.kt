package com.medtroniclabs.microcoaching.domain.gaps

/**
 * Outcome of one rule evaluator firing on one SPICE event.
 *
 * The dispatcher returns `List<GapDetectionResult>` per assessment — one entry
 * per *fired* gap. Non-fired rules return `null` from [GapEvaluator.evaluate]
 * and are dropped before this list is built.
 *
 * Each result feeds exactly one `spice_action_observed` telemetry event with
 * `behavioural_gap_id`, `outcome`, and the structured `evidence` payload per
 * GAP_DETECTION_SDK.md §1.
 */
data class GapDetectionResult(
    val gapId: String,
    val gapCode: String,
    val ruleType: String,
    /** "correct" | "incorrect" | "wrong" | "unknown" per design §1. */
    val outcome: String,
    /**
     * De-identified evidence map (≤ 2 KB per §5.4). Identifier fields must be
     * routed through hashing helpers by the evaluator before landing here.
     */
    val evidence: Map<String, Any?>,
)
