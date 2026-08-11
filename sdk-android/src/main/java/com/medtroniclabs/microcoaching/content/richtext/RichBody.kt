package com.medtroniclabs.microcoaching.content.richtext

import com.medtroniclabs.microcoaching.ui.markdown.markdownToSpokenText

/**
 * Single entry point for consuming a raw card body that may be **either** a
 * TipTap/ProseMirror JSON array **or** a legacy markdown string.
 *
 * - The Compose renderer ([com.medtroniclabs.microcoaching.ui.richtext.RichCardBody])
 *   uses [parseRichBlocksOrNull] and falls back to the markdown renderer on null.
 * - TTS and search use [bodyToPlainText] / [bodyToSpokenText], which dispatch to the
 *   rich extractor for JSON bodies and to the markdown stripper for string bodies.
 *
 * Keeping the array-vs-markdown decision in one place guarantees the renderer, the
 * voice reader, and the BM25 index all agree on how a given body is interpreted.
 */

/** Parsed TipTap blocks if [raw] is a JSON array, else null (treat as markdown). */
fun parseRichBody(raw: String): List<RichBlock>? = parseRichBlocksOrNull(raw)

/**
 * Plain, search/TTS-ready prose for [raw]. JSON bodies are flattened via
 * [blocksToPlainText]; markdown bodies are stripped via [markdownToSpokenText].
 * Returns "" for blank input.
 */
fun bodyToPlainText(raw: String): String {
    if (raw.isBlank()) return ""
    val blocks = parseRichBlocksOrNull(raw)
    return if (blocks != null) blocksToPlainText(blocks) else markdownToSpokenText(raw)
}

/** Alias of [bodyToPlainText] — both the voice reader and indexer want the same prose. */
fun bodyToSpokenText(raw: String): String = bodyToPlainText(raw)
