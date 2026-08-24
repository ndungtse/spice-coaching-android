package com.medtroniclabs.microcoaching.ui.podashboard.components

/**
 * Line ceiling for free-form backend text on the dashboard — chatbot questions,
 * suggested-topic titles, and the LLM rationale.
 *
 * These come from unbounded `Text` columns with no truncation server-side, so a
 * single row could otherwise grow to many screens tall and push everything below
 * it out of reach. Five lines keeps a genuine multi-sentence question readable
 * while still bounding the row.
 *
 * Short identity fields (names, geography, module titles) are not covered by this
 * — they cap at one or two lines inline, matching the surrounding rows.
 */
internal const val FREE_TEXT_MAX_LINES = 5
