package com.medtroniclabs.microcoaching.ui.markdown.blocks

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.medtroniclabs.microcoaching.ui.markdown.MarkdownStyle
import com.medtroniclabs.microcoaching.ui.markdown.literalText
import org.intellij.markdown.MarkdownElementTypes
import org.intellij.markdown.MarkdownTokenTypes
import org.intellij.markdown.ast.ASTNode
import org.intellij.markdown.ast.findChildOfType

/**
 * Renders ordered or unordered lists. Each list item displays:
 *
 *   marker  ┃ first paragraph (inline with marker)
 *           ┃ nested blocks (next paragraph, sub-list, code, etc.)
 *
 * Nested lists work because [content] is the same dispatcher [MarkdownText] uses
 * for top-level blocks — sub-lists re-enter this composable with `depth + 1`.
 */
@Composable
internal fun ListBlock(
    node: ASTNode,
    source: CharSequence,
    style: MarkdownStyle,
    depth: Int = 0,
    content: @Composable (ASTNode) -> Unit,
    modifier: Modifier = Modifier,
) {
    val ordered = node.type == MarkdownElementTypes.ORDERED_LIST
    val items = node.children.filter { it.type == MarkdownElementTypes.LIST_ITEM }
    val startNumber: Int = if (ordered) {
        items.firstOrNull()
            ?.findChildOfType(MarkdownTokenTypes.LIST_NUMBER)
            ?.literalText(source)
            ?.trimEnd { it == '.' || it == ')' || it.isWhitespace() }
            ?.toIntOrNull()
            ?: 1
    } else 1

    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        items.forEachIndexed { index, item ->
            val marker = if (ordered) "${startNumber + index}."
            else unorderedMarker(depth)
            Row(verticalAlignment = Alignment.Top) {
                Text(
                    text = marker,
                    style = style.textStyle.copy(fontWeight = FontWeight.SemiBold),
                    modifier = Modifier.width(style.listIndent),
                )
                Column(
                    modifier = Modifier.padding(start = 4.dp),
                    verticalArrangement = Arrangement.spacedBy(style.blockSpacing / 2),
                ) {
                    item.children
                        .filter { it.shouldRenderInListItem() }
                        .forEach { content(it) }
                }
            }
        }
    }
}

private fun unorderedMarker(depth: Int): String = when (depth % 3) {
    0 -> "•"
    1 -> "◦"
    else -> "▪"
}

private fun ASTNode.shouldRenderInListItem(): Boolean = when (type) {
    MarkdownTokenTypes.LIST_BULLET,
    MarkdownTokenTypes.LIST_NUMBER,
    MarkdownTokenTypes.EOL,
    MarkdownTokenTypes.WHITE_SPACE,
    -> false
    else -> true
}
