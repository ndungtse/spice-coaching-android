package com.medtroniclabs.microcoaching.content.richtext

/**
 * Flattens a parsed TipTap [RichBlock] tree into plain prose.
 *
 * Two consumers rely on this:
 *  - Android TTS (the lesson voice reader), which must never read JSON, URLs, or
 *    markup symbols aloud.
 *  - BM25 search / chat RAG indexing, which must see the *meaningful text* of a
 *    card body — not field names, node types, or media object paths.
 *
 * Rules: links emit their visible text only (href dropped); images/videos are
 * dropped entirely (their captions are filenames in the current payload); list
 * items are read in order. Output is whitespace-normalised the same way as
 * [com.medtroniclabs.microcoaching.ui.markdown.markdownToSpokenText] so the two
 * body paths produce comparable prose.
 */
internal fun blocksToPlainText(blocks: List<RichBlock>): String {
    val out = StringBuilder()
    blocks.forEach { appendBlock(it, out) }
    return out.toString()
        .replace(Regex("\\s+"), " ")
        .replace(Regex("\\s*\\.\\s*\\."), ".")
        .trim()
}

private fun appendBlock(block: RichBlock, out: StringBuilder) {
    when (block) {
        is RichBlock.Paragraph -> appendSentence(inlineText(block.inlines), out)
        is RichBlock.Heading -> appendSentence(inlineText(block.inlines), out)
        is RichBlock.BulletList -> block.items.forEach { appendBlock(it, out) }
        is RichBlock.OrderedList -> block.items.forEach { appendBlock(it, out) }
        is RichBlock.ListItem -> block.blocks.forEach { appendBlock(it, out) }
        is RichBlock.Blockquote -> block.blocks.forEach { appendBlock(it, out) }
        is RichBlock.Unknown -> block.children.forEach { appendBlock(it, out) }
        // Media and code read aloud / index as noise — drop.
        is RichBlock.Image,
        is RichBlock.Video,
        is RichBlock.CodeBlock,
        RichBlock.HorizontalRule,
        -> Unit
    }
}

private fun appendSentence(text: String, out: StringBuilder) {
    val trimmed = text.trim()
    if (trimmed.isEmpty()) return
    out.append(trimmed)
    if (!trimmed.endsWithSentenceTerminator()) out.append('.')
    out.append(' ')
}

/** Visible text of a run of inlines — marks dropped, hrefs dropped (text kept). */
private fun inlineText(inlines: List<RichInline>): String =
    inlines.joinToString("") { it.text }

private fun String.endsWithSentenceTerminator(): Boolean {
    val last = trimEnd().lastOrNull() ?: return false
    return last == '.' || last == '!' || last == '?' ||
        last == '।' /* Bengali full-stop */ ||
        last == ':' || last == ';'
}
