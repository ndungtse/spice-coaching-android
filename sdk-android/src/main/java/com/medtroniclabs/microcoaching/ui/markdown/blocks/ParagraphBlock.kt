package com.medtroniclabs.microcoaching.ui.markdown.blocks

import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.medtroniclabs.microcoaching.ui.markdown.MarkdownStyle
import com.medtroniclabs.microcoaching.ui.markdown.inline.renderInline
import org.intellij.markdown.ast.ASTNode

@Composable
internal fun ParagraphBlock(
    node: ASTNode,
    source: CharSequence,
    style: MarkdownStyle,
    modifier: Modifier = Modifier,
) {
    Text(
        text = renderInline(node, source, style),
        style = style.textStyle,
        modifier = modifier,
    )
}
