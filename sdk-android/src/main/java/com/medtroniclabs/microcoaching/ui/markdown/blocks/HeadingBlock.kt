package com.medtroniclabs.microcoaching.ui.markdown.blocks

import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.AnnotatedString
import com.medtroniclabs.microcoaching.ui.markdown.MarkdownStyle
import com.medtroniclabs.microcoaching.ui.markdown.inline.renderInline
import org.intellij.markdown.MarkdownElementTypes
import org.intellij.markdown.ast.ASTNode

@Composable
internal fun HeadingBlock(
    node: ASTNode,
    source: CharSequence,
    style: MarkdownStyle,
    modifier: Modifier = Modifier,
) {
    val textStyle = when (node.type) {
        MarkdownElementTypes.ATX_1, MarkdownElementTypes.SETEXT_1 -> style.h1
        MarkdownElementTypes.ATX_2, MarkdownElementTypes.SETEXT_2 -> style.h2
        MarkdownElementTypes.ATX_3 -> style.h3
        MarkdownElementTypes.ATX_4 -> style.h4
        MarkdownElementTypes.ATX_5 -> style.h5
        else -> style.h6
    }
    // Marker tokens (ATX_HEADER `#`, SETEXT_1/2 underline) are filtered out in
    // InlineRenderer; what remains is the heading's inline content. We trim
    // leading/trailing whitespace so headings don't pick up the space after `#`.
    Text(
        text = renderInline(node, source, style).trimAnnotated(),
        style = textStyle.copy(color = style.headingColor),
        modifier = modifier,
    )
}

private fun AnnotatedString.trimAnnotated(): AnnotatedString {
    val start = indexOfFirst { !it.isWhitespace() }.coerceAtLeast(0)
    val endExclusive = (indexOfLast { !it.isWhitespace() } + 1).coerceAtMost(length)
    return if (start <= 0 && endExclusive >= length) this
    else subSequence(start, endExclusive.coerceAtLeast(start))
}
