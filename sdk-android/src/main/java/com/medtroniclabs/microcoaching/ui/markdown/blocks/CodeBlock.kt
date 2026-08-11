package com.medtroniclabs.microcoaching.ui.markdown.blocks

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import com.medtroniclabs.microcoaching.ui.markdown.MarkdownStyle
import com.medtroniclabs.microcoaching.ui.markdown.literalText
import org.intellij.markdown.MarkdownElementTypes
import org.intellij.markdown.MarkdownTokenTypes
import org.intellij.markdown.ast.ASTNode

/**
 * Renders both fenced code blocks (` ``` `) and indented code blocks. For fenced
 * blocks we strip the fence + language tokens so only the code lines reach the
 * Text composable. The block is horizontally scrollable so long lines don't wrap
 * (preserving code readability).
 */
@Composable
internal fun CodeBlock(
    node: ASTNode,
    source: CharSequence,
    style: MarkdownStyle,
    modifier: Modifier = Modifier,
) {
    val code = when (node.type) {
        MarkdownElementTypes.CODE_FENCE -> extractFenceCode(node, source)
        MarkdownElementTypes.CODE_BLOCK -> node.literalText(source).trimEnd('\n')
        else -> node.literalText(source).trimEnd('\n')
    }
    Text(
        text = code,
        style = style.codeStyle.copy(color = style.textColor),
        modifier = modifier
            .clip(RoundedCornerShape(8.dp))
            .background(style.codeBackground)
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 12.dp, vertical = 10.dp),
    )
}

private fun extractFenceCode(node: ASTNode, source: CharSequence): String {
    // Children typically: CODE_FENCE_START, FENCE_LANG?, EOL, CODE_FENCE_CONTENT*,
    // EOL, CODE_FENCE_END. Concatenate only the CODE_FENCE_CONTENT pieces (with
    // EOLs between) to preserve line structure without including the ``` markers.
    val builder = StringBuilder()
    var sawContent = false
    node.children.forEach { child ->
        when (child.type) {
            MarkdownTokenTypes.CODE_FENCE_CONTENT -> {
                if (sawContent) builder.append('\n')
                builder.append(child.literalText(source))
                sawContent = true
            }
            MarkdownTokenTypes.EOL -> { /* line breaks handled by content joins */ }
            else -> Unit
        }
    }
    return builder.toString()
}
