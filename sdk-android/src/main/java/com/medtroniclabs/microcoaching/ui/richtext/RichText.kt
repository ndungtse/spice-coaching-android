package com.medtroniclabs.microcoaching.ui.richtext

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.medtroniclabs.microcoaching.content.richtext.RichBlock
import com.medtroniclabs.microcoaching.ui.markdown.MarkdownDefaults
import com.medtroniclabs.microcoaching.ui.markdown.MarkdownStyle
import com.medtroniclabs.microcoaching.ui.richtext.blocks.RichImageBlock
import com.medtroniclabs.microcoaching.ui.richtext.blocks.RichVideoBlock
import com.medtroniclabs.microcoaching.ui.richtext.inline.renderRichInline

/**
 * Renders a parsed TipTap [RichBlock] tree in Compose. Theming (text styles,
 * heading sizes, link colour, spacing) is shared with the markdown renderer via
 * [MarkdownStyle] so rich and markdown card bodies are visually consistent.
 *
 * Block coverage: paragraph, heading, ordered/bullet lists (nested), image
 * (Coil), video (tap-to-play), block quote, code block, horizontal rule, and an
 * Unknown catch-all that renders any preserved children.
 */
@Composable
fun RichText(
    blocks: List<RichBlock>,
    modifier: Modifier = Modifier,
    style: MarkdownStyle = MarkdownDefaults.style(),
) {
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(style.blockSpacing),
    ) {
        blocks.forEach { RenderBlock(it, style) }
    }
}

@Composable
private fun RenderBlock(block: RichBlock, style: MarkdownStyle) {
    when (block) {
        is RichBlock.Paragraph -> Text(
            text = renderRichInline(block.inlines, style),
            style = style.textStyle,
        )

        is RichBlock.Heading -> Text(
            text = renderRichInline(block.inlines, style),
            style = headingStyle(block.level, style).copy(color = style.headingColor),
        )

        is RichBlock.BulletList -> Column(
            verticalArrangement = Arrangement.spacedBy(style.blockSpacing / 2),
        ) {
            block.items.forEach { item -> ListRow(marker = "•", style = style, item = item) }
        }

        is RichBlock.OrderedList -> Column(
            verticalArrangement = Arrangement.spacedBy(style.blockSpacing / 2),
        ) {
            block.items.forEachIndexed { idx, item ->
                ListRow(marker = "${block.start + idx}.", style = style, item = item)
            }
        }

        // A bare ListItem (shouldn't appear at top level) — render its blocks.
        is RichBlock.ListItem -> Column(
            verticalArrangement = Arrangement.spacedBy(style.blockSpacing / 2),
        ) { block.blocks.forEach { RenderBlock(it, style) } }

        is RichBlock.Image -> RichImageBlock(block)

        is RichBlock.Video -> RichVideoBlock(block)

        is RichBlock.Blockquote -> Row(
            modifier = Modifier
                .clip(RoundedCornerShape(6.dp))
                .background(style.blockQuoteBackground)
                .padding(start = 12.dp, top = 8.dp, bottom = 8.dp, end = 8.dp),
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(style.blockSpacing / 2)) {
                block.blocks.forEach { RenderBlock(it, style) }
            }
        }

        is RichBlock.CodeBlock -> Text(
            text = block.text,
            style = style.textStyle.copy(fontFamily = FontFamily.Monospace),
            modifier = Modifier
                .clip(RoundedCornerShape(6.dp))
                .background(style.codeBackground)
                .padding(8.dp),
        )

        RichBlock.HorizontalRule -> HorizontalDivider(color = style.tableBorderColor)

        is RichBlock.Unknown -> Column(
            verticalArrangement = Arrangement.spacedBy(style.blockSpacing),
        ) { block.children.forEach { RenderBlock(it, style) } }
    }
}

@Composable
private fun ListRow(marker: String, style: MarkdownStyle, item: RichBlock.ListItem) {
    Row {
        Text(
            text = marker,
            style = style.textStyle,
            modifier = Modifier.width(style.listIndent),
        )
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(style.blockSpacing / 2),
        ) {
            item.blocks.forEach { RenderBlock(it, style) }
        }
    }
}

private fun headingStyle(level: Int, style: MarkdownStyle) = when (level) {
    1 -> style.h1
    2 -> style.h2
    3 -> style.h3
    4 -> style.h4
    5 -> style.h5
    else -> style.h6
}
