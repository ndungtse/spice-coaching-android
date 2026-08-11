package com.medtroniclabs.microcoaching.ui.markdown

import org.intellij.markdown.MarkdownElementTypes
import org.intellij.markdown.ast.ASTNode
import org.intellij.markdown.ast.getTextInNode
import org.intellij.markdown.flavours.gfm.GFMElementTypes
import org.intellij.markdown.flavours.gfm.GFMFlavourDescriptor
import org.intellij.markdown.parser.MarkdownParser as JbMarkdownParser

/**
 * Thin wrapper over JetBrains' Markdown parser with GFM enabled (tables, strikethrough,
 * task lists, autolinks). The AST it returns is rendered by Composables in
 * [com.medtroniclabs.microcoaching.ui.markdown.blocks] and
 * [com.medtroniclabs.microcoaching.ui.markdown.inline].
 *
 * A [ParsedMarkdown] bundles the source string with its root AST node — both are needed
 * because every node refers back to ranges inside the original source.
 */
internal data class ParsedMarkdown(val source: String, val root: ASTNode)

internal object MarkdownTreeBuilder {

    private val flavour = GFMFlavourDescriptor()

    fun parse(source: String): ParsedMarkdown {
        val htmlConverted = convertHtmlToMarkdown(source)
        val normalized = normalizeForGfm(htmlConverted)
        val root = JbMarkdownParser(flavour).buildMarkdownTreeFromString(normalized)
        return ParsedMarkdown(normalized, root)
    }
}

/**
 * Smooths over two authoring patterns that strict CommonMark/GFM rejects but the
 * backend payload uses widely:
 *
 *  1. Tables not preceded by a blank line — GFM only recognises a table when the
 *     preceding line is blank. We insert a blank line in that case so the table
 *     is detected.
 *  2. Sub-bullets directly under an ordered-list item without indentation — GFM
 *     treats them as a separate top-level list. We indent contiguous bullet lines
 *     that follow a numbered item (until the next blank line) so they nest under
 *     the parent step.
 *
 * Idempotent: running twice yields the same result.
 */
internal fun normalizeForGfm(source: String): String {
    if (source.isEmpty()) return source
    val lines = source.split('\n')
    val dividerRegex = Regex("""^\s*\|?[\s|:\-]+\|?\s*$""")
    val pipeRowRegex = Regex("""^\s*\|""")
    val orderedRegex = Regex("""^\s*\d+[.)]\s""")
    val bulletRegex = Regex("""^([*+\-])\s""")

    val out = mutableListOf<String>()
    var insideOrderedList = false

    lines.forEachIndexed { index, raw ->
        val line = raw
        val trimmedIsBlank = line.isBlank()

        // Track ordered-list scope. A blank line ends the current scope.
        when {
            orderedRegex.containsMatchIn(line) -> insideOrderedList = true
            trimmedIsBlank -> insideOrderedList = false
        }

        // Fix 1: insert blank line before a table whose preceding line is non-blank text.
        val isPipeRow = pipeRowRegex.containsMatchIn(line)
        val nextLine = lines.getOrNull(index + 1) ?: ""
        val nextIsDivider = dividerRegex.matches(nextLine) && nextLine.contains('-')
        val prev = out.lastOrNull()
        val prevIsBlank = prev?.isBlank() ?: true
        val prevIsPipeRow = prev?.let { pipeRowRegex.containsMatchIn(it) } == true
        if (isPipeRow && nextIsDivider && !prevIsBlank && !prevIsPipeRow) {
            out += ""
        }

        // Fix 2: indent bullets that follow a numbered step so they nest under it.
        // CommonMark requires the bullet's start column to be at or past the parent
        // item's content column. For markers up to two digits (`10. `) that means
        // four spaces — safe for any single- or double-digit ordered list.
        val indented = if (insideOrderedList && bulletRegex.containsMatchIn(line) &&
            !orderedRegex.containsMatchIn(line)
        ) "    $line" else line

        out += indented
    }
    return out.joinToString("\n")
}

internal fun ASTNode.literalText(source: CharSequence): String =
    getTextInNode(source).toString()

/**
 * Walks the tree (depth-first) and returns true if [predicate] matches any node.
 */
internal fun ASTNode.anyNode(predicate: (ASTNode) -> Boolean): Boolean {
    if (predicate(this)) return true
    return children.any { it.anyNode(predicate) }
}

// ── Detection helpers ────────────────────────────────────────────────────────
// Pure functions usable from non-Compose code (e.g. unit tests, lightweight UI
// gating in lesson cards) without paying for full rendering.

internal fun hasTable(content: String): Boolean =
    MarkdownTreeBuilder.parse(content).root.anyNode { it.type == GFMElementTypes.TABLE }

internal fun hasOrderedList(content: String): Boolean =
    MarkdownTreeBuilder.parse(content).root.anyNode { it.type == MarkdownElementTypes.ORDERED_LIST }

internal fun hasUnorderedList(content: String): Boolean =
    MarkdownTreeBuilder.parse(content).root.anyNode { it.type == MarkdownElementTypes.UNORDERED_LIST }

/**
 * True when any list item directly contains another list (i.e. the layout has
 * indented sub-bullets), which is the case for cards like the "Ways to prevent…"
 * card where each numbered step nests bullet points.
 */
internal fun hasNestedList(content: String): Boolean =
    MarkdownTreeBuilder.parse(content).root.anyNode { node ->
        if (node.type != MarkdownElementTypes.LIST_ITEM) return@anyNode false
        node.children.any { child ->
            child.type == MarkdownElementTypes.UNORDERED_LIST ||
                child.type == MarkdownElementTypes.ORDERED_LIST
        }
    }
