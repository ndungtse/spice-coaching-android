package com.medtroniclabs.microcoaching.ui.markdown

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import com.medtroniclabs.microcoaching.ui.markdown.blocks.BlockQuoteBlock
import com.medtroniclabs.microcoaching.ui.markdown.blocks.CodeBlock
import com.medtroniclabs.microcoaching.ui.markdown.blocks.HeadingBlock
import com.medtroniclabs.microcoaching.ui.markdown.blocks.HorizontalRuleBlock
import com.medtroniclabs.microcoaching.ui.markdown.blocks.ListBlock
import com.medtroniclabs.microcoaching.ui.markdown.blocks.ParagraphBlock
import com.medtroniclabs.microcoaching.ui.markdown.blocks.TableBlock
import org.intellij.markdown.MarkdownElementTypes
import org.intellij.markdown.MarkdownTokenTypes
import org.intellij.markdown.ast.ASTNode
import org.intellij.markdown.flavours.gfm.GFMElementTypes

/**
 * Renders a markdown string with full GFM coverage: headings, paragraphs, ordered
 * + unordered + nested lists, GFM tables (with alignment), block quotes, fenced
 * + indented code blocks, horizontal rules, and inline formatting (bold, italic,
 * strikethrough, code spans, links).
 *
 * Parses with [org.jetbrains:markdown](https://github.com/JetBrains/markdown)
 * (GFM flavour) and dispatches each top-level block to a small Composable in
 * [com.medtroniclabs.microcoaching.ui.markdown.blocks]. Override visual defaults
 * via [MarkdownDefaults.style].
 */
@Composable
fun MarkdownText(
    content: String,
    modifier: Modifier = Modifier,
    style: MarkdownStyle = MarkdownDefaults.style(),
) {
    if (content.isBlank()) return
    val parsed = remember(content) { MarkdownTreeBuilder.parse(content) }
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(style.blockSpacing),
    ) {
        parsed.root.children.forEach { child -> RenderBlock(child, parsed.source, style) }
    }
}

@Composable
private fun RenderBlock(node: ASTNode, source: CharSequence, style: MarkdownStyle) {
    when (node.type) {
        MarkdownElementTypes.ATX_1,
        MarkdownElementTypes.ATX_2,
        MarkdownElementTypes.ATX_3,
        MarkdownElementTypes.ATX_4,
        MarkdownElementTypes.ATX_5,
        MarkdownElementTypes.ATX_6,
        MarkdownElementTypes.SETEXT_1,
        MarkdownElementTypes.SETEXT_2,
        -> HeadingBlock(node, source, style)

        MarkdownElementTypes.PARAGRAPH -> ParagraphBlock(node, source, style)

        MarkdownElementTypes.ORDERED_LIST,
        MarkdownElementTypes.UNORDERED_LIST,
        -> ListBlock(
            node = node,
            source = source,
            style = style,
            depth = 0,
            content = { child -> NestedBlock(child, source, style, depth = 1) },
        )

        GFMElementTypes.TABLE -> TableBlock(node, source, style)

        MarkdownElementTypes.BLOCK_QUOTE -> BlockQuoteBlock(node, style) { child ->
            RenderBlock(child, source, style)
        }

        MarkdownElementTypes.CODE_FENCE,
        MarkdownElementTypes.CODE_BLOCK,
        -> CodeBlock(node, source, style)

        MarkdownTokenTypes.HORIZONTAL_RULE -> HorizontalRuleBlock(style)

        // Top-level marker tokens and irrelevant noise — skip silently.
        MarkdownTokenTypes.EOL,
        MarkdownTokenTypes.WHITE_SPACE,
        MarkdownElementTypes.LINK_DEFINITION,
        -> Unit

        // HTML blocks and anything else fall through as plain paragraphs so we
        // never silently drop content.
        else -> ParagraphBlock(node, source, style)
    }
}

@Composable
private fun NestedBlock(
    node: ASTNode,
    source: CharSequence,
    style: MarkdownStyle,
    depth: Int,
) {
    when (node.type) {
        MarkdownElementTypes.ORDERED_LIST,
        MarkdownElementTypes.UNORDERED_LIST,
        -> ListBlock(
            node = node,
            source = source,
            style = style,
            depth = depth,
            content = { child -> NestedBlock(child, source, style, depth + 1) },
        )
        else -> RenderBlock(node, source, style)
    }
}
