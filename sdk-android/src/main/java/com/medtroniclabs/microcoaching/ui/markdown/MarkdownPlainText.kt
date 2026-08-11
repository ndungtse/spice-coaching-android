package com.medtroniclabs.microcoaching.ui.markdown

import org.intellij.markdown.MarkdownElementTypes
import org.intellij.markdown.MarkdownTokenTypes
import org.intellij.markdown.ast.ASTNode
import org.intellij.markdown.flavours.gfm.GFMElementTypes
import org.intellij.markdown.flavours.gfm.GFMTokenTypes

/**
 * Renders a markdown card body as plain prose suitable for Android TTS.
 *
 * Walks the AST built by [MarkdownTreeBuilder] and drops markdown syntax (asterisks,
 * pipes, bullets, fences) so the speech engine never reads symbols aloud. Tables are
 * flattened: each body cell is paired with its column header so a comparison table
 * becomes a sequence of "Header: cell. Header: cell." sentences.
 */
internal fun markdownToSpokenText(content: String): String {
    if (content.isBlank()) return ""
    val parsed = MarkdownTreeBuilder.parse(content)
    val builder = StringBuilder()
    parsed.root.children.forEach { renderBlock(it, parsed.source, builder) }
    return builder.toString()
        .replace(Regex("\\s+"), " ")
        .replace(Regex("\\s*\\.\\s*\\."), ".")
        .trim()
}

private fun renderBlock(node: ASTNode, source: CharSequence, out: StringBuilder) {
    when (node.type) {
        MarkdownElementTypes.PARAGRAPH,
        MarkdownElementTypes.ATX_1, MarkdownElementTypes.ATX_2, MarkdownElementTypes.ATX_3,
        MarkdownElementTypes.ATX_4, MarkdownElementTypes.ATX_5, MarkdownElementTypes.ATX_6,
        MarkdownElementTypes.SETEXT_1, MarkdownElementTypes.SETEXT_2,
        -> {
            val text = inlineText(node, source).trim()
            if (text.isNotEmpty()) {
                out.append(text)
                if (!text.endsWithSentenceTerminator()) out.append('.')
                out.append(' ')
            }
        }

        MarkdownElementTypes.ORDERED_LIST,
        MarkdownElementTypes.UNORDERED_LIST,
        -> {
            node.children
                .filter { it.type == MarkdownElementTypes.LIST_ITEM }
                .forEach { item ->
                    item.children
                        .filter { it.type != MarkdownTokenTypes.LIST_BULLET &&
                            it.type != MarkdownTokenTypes.LIST_NUMBER &&
                            it.type != MarkdownTokenTypes.WHITE_SPACE &&
                            it.type != MarkdownTokenTypes.EOL }
                        .forEach { child -> renderBlock(child, source, out) }
                }
        }

        GFMElementTypes.TABLE -> renderTable(node, source, out)

        MarkdownElementTypes.BLOCK_QUOTE -> {
            node.children.forEach { renderBlock(it, source, out) }
        }

        // Code blocks read aloud as symbols/gibberish — skip.
        MarkdownElementTypes.CODE_FENCE,
        MarkdownElementTypes.CODE_BLOCK,
        MarkdownTokenTypes.HORIZONTAL_RULE,
        MarkdownElementTypes.LINK_DEFINITION,
        -> Unit

        // Anything else: try inline rendering so user text isn't lost.
        else -> {
            val text = inlineText(node, source).trim()
            if (text.isNotEmpty()) {
                out.append(text)
                if (!text.endsWithSentenceTerminator()) out.append('.')
                out.append(' ')
            }
        }
    }
}

private fun renderTable(table: ASTNode, source: CharSequence, out: StringBuilder) {
    val header = table.children.firstOrNull { it.type == GFMElementTypes.HEADER }
    val headerCells = header?.children
        ?.filter { it.type == GFMTokenTypes.CELL }
        ?.map { it.literalText(source).trim() }
        .orEmpty()
    val bodyRows = table.children.filter { it.type == GFMElementTypes.ROW }

    bodyRows.forEach { row ->
        val cells = row.children
            .filter { it.type == GFMTokenTypes.CELL }
            .map { it.literalText(source).trim() }
        cells.forEachIndexed { index, cellText ->
            if (cellText.isEmpty()) return@forEachIndexed
            val label = headerCells.getOrNull(index)?.takeIf { it.isNotBlank() }
            if (label != null) out.append(label).append(": ")
            out.append(cellText)
            if (!cellText.endsWithSentenceTerminator()) out.append('.')
            out.append(' ')
        }
    }
}

private fun inlineText(node: ASTNode, source: CharSequence): String {
    val sb = StringBuilder()
    appendInline(node, source, sb)
    return sb.toString()
}

private fun appendInline(node: ASTNode, source: CharSequence, sb: StringBuilder) {
    when (node.type) {
        MarkdownTokenTypes.TEXT,
        MarkdownTokenTypes.WHITE_SPACE,
        MarkdownTokenTypes.SINGLE_QUOTE,
        MarkdownTokenTypes.DOUBLE_QUOTE,
        MarkdownTokenTypes.LPAREN,
        MarkdownTokenTypes.RPAREN,
        MarkdownTokenTypes.LBRACKET,
        MarkdownTokenTypes.RBRACKET,
        MarkdownTokenTypes.LT,
        MarkdownTokenTypes.GT,
        MarkdownTokenTypes.COLON,
        MarkdownTokenTypes.EXCLAMATION_MARK,
        MarkdownTokenTypes.URL,
        MarkdownTokenTypes.AUTOLINK,
        MarkdownTokenTypes.EMAIL_AUTOLINK,
        -> sb.append(node.literalText(source))

        MarkdownTokenTypes.EOL -> sb.append(' ')
        MarkdownTokenTypes.HARD_LINE_BREAK -> sb.append(' ')

        // For links, speak the visible text only (not the URL).
        MarkdownElementTypes.INLINE_LINK,
        MarkdownElementTypes.FULL_REFERENCE_LINK,
        MarkdownElementTypes.SHORT_REFERENCE_LINK,
        -> {
            val linkText = node.children.firstOrNull { it.type == MarkdownElementTypes.LINK_TEXT }
            if (linkText != null) appendInline(linkText, source, sb)
            else node.children.forEach { appendInline(it, source, sb) }
        }

        // Strikethrough: skip the contents (the user crossed it out for a reason).
        GFMElementTypes.STRIKETHROUGH -> Unit

        // Image: emit alt text only.
        MarkdownElementTypes.IMAGE -> {
            val alt = node.children.firstOrNull { it.type == MarkdownElementTypes.LINK_TEXT }
            if (alt != null) appendInline(alt, source, sb)
        }

        // Drop markdown markers entirely.
        MarkdownTokenTypes.EMPH,
        MarkdownTokenTypes.BACKTICK,
        MarkdownTokenTypes.ESCAPED_BACKTICKS,
        MarkdownTokenTypes.LINK_ID,
        MarkdownTokenTypes.LINK_TITLE,
        MarkdownTokenTypes.ATX_HEADER,
        MarkdownTokenTypes.SETEXT_1,
        MarkdownTokenTypes.SETEXT_2,
        MarkdownTokenTypes.LIST_BULLET,
        MarkdownTokenTypes.LIST_NUMBER,
        GFMTokenTypes.TILDE,
        GFMTokenTypes.TABLE_SEPARATOR,
        MarkdownElementTypes.LINK_DESTINATION,
        MarkdownElementTypes.LINK_LABEL,
        -> Unit

        else -> node.children.forEach { appendInline(it, source, sb) }
    }
}

private fun String.endsWithSentenceTerminator(): Boolean {
    val last = trimEnd().lastOrNull() ?: return false
    return last == '.' || last == '!' || last == '?' ||
        last == '।' /* Bengali full-stop */ ||
        last == ':' || last == ';'
}
