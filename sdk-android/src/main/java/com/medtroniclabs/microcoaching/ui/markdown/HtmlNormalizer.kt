package com.medtroniclabs.microcoaching.ui.markdown

/**
 * Converts HTML fragments in lesson card bodies into equivalent markdown so the
 * existing parser + Compose renderer can handle them uniformly.
 *
 * The backend payload mixes raw HTML (`<ul><li>…</li></ul>`) with markdown across
 * different modules. Rather than teaching every block/inline composable about
 * HTML, we normalise to markdown at the very front of [MarkdownTreeBuilder.parse]
 * so all downstream code (block dispatch, inline rendering, table layout, the
 * TTS plain-text path) gets a single consistent input format.
 *
 * Supported tags (anything else is stripped with its content kept):
 *   - block: `<ul>`, `<ol>`, `<li>` (nested), `<p>`, `<blockquote>`, `<hr>`
 *   - inline: `<strong>`, `<b>`, `<em>`, `<i>`, `<code>`, `<a href="…">`, `<br>`
 *   - entities: `&amp; &lt; &gt; &quot; &apos; &nbsp;` plus numeric `&#N;` / `&#xH;`
 *
 * Idempotent: running on already-converted markdown is a no-op.
 */
internal fun convertHtmlToMarkdown(source: String): String {
    if (source.isEmpty()) return source
    // Fast path: no HTML tags and no entities — the input is already markdown.
    if (!source.contains('<') && !source.contains('&')) return source

    var s = source
    s = stripScriptsAndStyles(s)
    s = convertInlineTags(s)
    s = convertParagraphsAndBreaks(s)
    s = convertBlockquotes(s)
    s = convertLists(s)
    s = stripRemainingTags(s)
    s = decodeEntities(s)
    return s
}

// ── Stage 1: remove scripts/styles entirely (content + tags) ────────────────

private val scriptOrStyle = Regex(
    """<(script|style)\b[^>]*>[\s\S]*?</\1\s*>""",
    RegexOption.IGNORE_CASE,
)

private fun stripScriptsAndStyles(s: String): String = scriptOrStyle.replace(s, "")

// ── Stage 2: inline tags → markdown wrappers ────────────────────────────────

private val strongTag = Regex("""<(strong|b)\b[^>]*>([\s\S]*?)</\1\s*>""", RegexOption.IGNORE_CASE)
private val emphTag = Regex("""<(em|i)\b[^>]*>([\s\S]*?)</\1\s*>""", RegexOption.IGNORE_CASE)
private val codeTag = Regex("""<code\b[^>]*>([\s\S]*?)</code\s*>""", RegexOption.IGNORE_CASE)
private val anchorTag = Regex(
    """<a\b[^>]*\bhref\s*=\s*(?:"([^"]*)"|'([^']*)'|([^\s>]+))[^>]*>([\s\S]*?)</a\s*>""",
    RegexOption.IGNORE_CASE,
)
private val brTag = Regex("""<br\s*/?\s*>""", RegexOption.IGNORE_CASE)
private val hrTag = Regex("""<hr\s*/?\s*>""", RegexOption.IGNORE_CASE)

private fun convertInlineTags(input: String): String {
    // Run repeatedly so nested cases like `<strong><em>x</em></strong>` collapse.
    var s = input
    var iterations = 0
    while (iterations++ < 8) {
        val next = s
            .let { strongTag.replace(it) { m -> "**${m.groupValues[2]}**" } }
            .let { emphTag.replace(it) { m -> "*${m.groupValues[2]}*" } }
            .let { codeTag.replace(it) { m -> "`${m.groupValues[1]}`" } }
            .let { anchorTag.replace(it) { m ->
                val href = m.groupValues[1].ifEmpty { m.groupValues[2].ifEmpty { m.groupValues[3] } }
                val text = m.groupValues[4]
                if (href.isBlank()) text else "[$text]($href)"
            } }
            .let { brTag.replace(it, "\n") }
            .let { hrTag.replace(it, "\n---\n") }
        if (next == s) break
        s = next
    }
    return s
}

// ── Stage 3: paragraphs ─────────────────────────────────────────────────────

private val pTag = Regex("""<p\b[^>]*>([\s\S]*?)</p\s*>""", RegexOption.IGNORE_CASE)

private fun convertParagraphsAndBreaks(input: String): String =
    pTag.replace(input) { m -> "\n\n${m.groupValues[1].trim()}\n\n" }

// ── Stage 4: blockquotes ────────────────────────────────────────────────────

private val blockquoteTag = Regex("""<blockquote\b[^>]*>([\s\S]*?)</blockquote\s*>""", RegexOption.IGNORE_CASE)

private fun convertBlockquotes(input: String): String = blockquoteTag.replace(input) { m ->
    val content = m.groupValues[1].trim()
    val quoted = content.split('\n').joinToString("\n") { line ->
        if (line.isBlank()) ">" else "> $line"
    }
    "\n$quoted\n"
}

// ── Stage 5: lists (innermost-first, with indent fixup for nesting) ─────────

private val listOpenCloseToken = Regex("""<(/?)(ul|ol)\b[^>]*>""", RegexOption.IGNORE_CASE)
private val listItemTag = Regex("""<li\b[^>]*>([\s\S]*?)</li\s*>""", RegexOption.IGNORE_CASE)
private val nestedBulletLine = Regex("""^\s*(\*|\d+\.)\s.*$""")

private fun convertLists(input: String): String {
    if (!input.contains("<ul", ignoreCase = true) && !input.contains("<ol", ignoreCase = true)) return input
    var s = input
    var safety = 0
    while (safety++ < 64) {
        val range = findInnermostListRange(s) ?: break
        val rendered = renderInnermostList(s.substring(range))
        s = s.replaceRange(range, rendered)
    }
    return s
}

/**
 * Forward-scan for the innermost `<ul>` or `<ol>` block. The first `</ul>` or
 * `</ol>` we encounter while walking forward is by definition the innermost
 * close — its matching open is the deepest open on our stack.
 */
private fun findInnermostListRange(s: String): IntRange? {
    val opens = ArrayDeque<MatchResult>()
    listOpenCloseToken.findAll(s).forEach { token ->
        val isClose = token.groupValues[1] == "/"
        if (!isClose) {
            opens.addLast(token)
        } else {
            val opener = opens.lastOrNull() ?: return@forEach
            if (opener.groupValues[2].equals(token.groupValues[2], ignoreCase = true)) {
                return opener.range.first..token.range.last
            }
            // Mismatched close tag — drop it and keep scanning.
            opens.removeLast()
        }
    }
    return null
}

private fun renderInnermostList(slice: String): String {
    val kind = if (slice.startsWith("<ol", ignoreCase = true)) "ol" else "ul"
    val marker = if (kind == "ol") "1. " else "* "
    val rendered = StringBuilder()
    rendered.append('\n')
    listItemTag.findAll(slice).forEach { itemMatch ->
        val rawItem = itemMatch.groupValues[1].trim()
        if (rawItem.isEmpty()) return@forEach
        // If this item carries bullet lines from a previous (deeper) pass,
        // indent them with two spaces so CommonMark treats them as nested.
        val itemLines = rawItem.split('\n').map { line ->
            if (nestedBulletLine.matches(line)) "  $line" else line
        }
        rendered.append(marker)
        rendered.append(itemLines.first().trimStart())
        rendered.append('\n')
        itemLines.drop(1).forEach { line ->
            if (line.isNotBlank()) {
                // Lines that already start with two spaces (nested bullets) stay
                // as-is; other continuation lines get a 4-space indent.
                val prefixed = if (line.startsWith("  ")) line else "    $line"
                rendered.append(prefixed)
                rendered.append('\n')
            }
        }
    }
    return rendered.toString()
}

// ── Stage 6: anything left is unknown — strip the tags, keep inner text ─────

private val anyTag = Regex("""</?[a-zA-Z][^>]*>""")

private fun stripRemainingTags(input: String): String = anyTag.replace(input, "")

// ── Stage 7: decode HTML entities ───────────────────────────────────────────

private val numericEntity = Regex("""&#(x[0-9a-fA-F]+|\d+);""")

private fun decodeEntities(input: String): String {
    if (!input.contains('&')) return input
    val withNamed = input
        .replace("&nbsp;", " ")
        .replace("&lt;", "<")
        .replace("&gt;", ">")
        .replace("&quot;", "\"")
        .replace("&apos;", "'")
        .replace("&amp;", "&") // must come last so we don't double-decode `&amp;lt;`
    return numericEntity.replace(withNamed) { m ->
        val raw = m.groupValues[1]
        val codepoint = if (raw.startsWith("x") || raw.startsWith("X"))
            raw.substring(1).toIntOrNull(16)
        else
            raw.toIntOrNull()
        codepoint?.takeIf { Character.isValidCodePoint(it) }
            ?.let { String(Character.toChars(it)) }
            ?: m.value
    }
}
