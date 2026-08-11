package com.medtroniclabs.microcoaching.ui.markdown.blocks

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import com.medtroniclabs.microcoaching.ui.markdown.MarkdownStyle
import org.intellij.markdown.ast.ASTNode

/**
 * Block quote with a vertical accent bar on the left and a tinted background.
 * Renders inner block content through the provided [content] callback so nested
 * paragraphs / lists / code blocks compose correctly.
 */
@Composable
internal fun BlockQuoteBlock(
    node: ASTNode,
    style: MarkdownStyle,
    modifier: Modifier = Modifier,
    content: @Composable (ASTNode) -> Unit,
) {
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(6.dp))
            .background(style.blockQuoteBackground),
    ) {
        Box(
            modifier = Modifier
                .width(4.dp)
                .fillMaxHeight()
                .background(style.blockQuoteAccent),
        )
        androidx.compose.foundation.layout.Column(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(style.blockSpacing),
        ) {
            // Children of a BLOCK_QUOTE include the `>` markers plus inner blocks.
            // Renderer-side dispatcher in [MarkdownText] filters markers, so we
            // can hand every child to [content] and let it decide.
            node.children.forEach { content(it) }
        }
    }
}
