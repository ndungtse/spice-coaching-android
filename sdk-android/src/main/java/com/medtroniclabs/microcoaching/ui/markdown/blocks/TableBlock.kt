package com.medtroniclabs.microcoaching.ui.markdown.blocks

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.medtroniclabs.microcoaching.ui.markdown.MarkdownStyle
import com.medtroniclabs.microcoaching.ui.markdown.MarkdownTreeBuilder
import com.medtroniclabs.microcoaching.ui.markdown.inline.renderInline
import com.medtroniclabs.microcoaching.ui.markdown.literalText
import org.intellij.markdown.MarkdownElementTypes
import org.intellij.markdown.ast.ASTNode
import org.intellij.markdown.flavours.gfm.GFMElementTypes
import org.intellij.markdown.flavours.gfm.GFMTokenTypes

/**
 * Renders a GFM table as a bordered grid: tinted header row, divided body rows,
 * equal-width columns via [Modifier.weight]. Column alignment (`:---`, `---:`,
 * `:---:`) is parsed from the table's divider line. Cell content goes through
 * the inline renderer so inline formatting inside cells still works.
 */
@Composable
internal fun TableBlock(
    node: ASTNode,
    source: CharSequence,
    style: MarkdownStyle,
    modifier: Modifier = Modifier,
) {
    val alignments = parseColumnAlignments(node.literalText(source))
    val headerCells = node.children
        .firstOrNull { it.type == GFMElementTypes.HEADER }
        ?.let { extractCells(it) }
        .orEmpty()
    val bodyRows = node.children
        .filter { it.type == GFMElementTypes.ROW }
        .map { extractCells(it) }

    val columnCount = maxOf(headerCells.size, bodyRows.maxOfOrNull { it.size } ?: 0)
    if (columnCount == 0) return

    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .border(1.dp, style.tableBorderColor, RoundedCornerShape(8.dp)),
    ) {
        TableRow(
            cells = headerCells.padTo(columnCount),
            source = source,
            style = style,
            alignments = alignments,
            isHeader = true,
            background = style.tableHeaderBackground,
        )
        bodyRows.forEachIndexed { index, row ->
            HorizontalDivider(thickness = 1.dp, color = style.tableBorderColor)
            TableRow(
                cells = row.padTo(columnCount),
                source = source,
                style = style,
                alignments = alignments,
                isHeader = false,
                background = if (index % 2 == 1) style.tableHeaderBackground.copy(alpha = 0.2f)
                else Color.Transparent,
            )
        }
    }
}

@Composable
private fun TableRow(
    cells: List<ASTNode?>,
    source: CharSequence,
    style: MarkdownStyle,
    alignments: List<TextAlign>,
    isHeader: Boolean,
    background: Color,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(IntrinsicSize.Min)
            .background(background),
    ) {
        cells.forEachIndexed { index, cell ->
            if (index > 0) {
                Box(
                    Modifier
                        .width(1.dp)
                        .fillMaxHeight()
                        .background(style.tableBorderColor),
                )
            }
            TableCell(
                cell = cell,
                source = source,
                style = style,
                alignment = alignments.getOrElse(index) { TextAlign.Start },
                isHeader = isHeader,
                modifier = Modifier.weight(1f),
            )
        }
    }
}

@Composable
private fun TableCell(
    cell: ASTNode?,
    source: CharSequence,
    style: MarkdownStyle,
    alignment: TextAlign,
    isHeader: Boolean,
    modifier: Modifier = Modifier,
) {
    val textStyle = if (isHeader) {
        style.textStyle.copy(fontWeight = FontWeight.SemiBold, color = style.headingColor)
    } else style.textStyle
    Box(modifier = modifier.padding(style.tableCellPadding)) {
        Text(
            text = if (cell == null) AnnotatedString("") else cellAnnotated(cell, source, style),
            style = textStyle,
            textAlign = alignment,
        )
    }
}

/**
 * GFM cells are emitted as leaf `CELL` tokens by the JetBrains parser, which
 * means inline formatting inside cells is NOT pre-parsed. We re-parse the cell
 * text through a fresh inline pass so `**bold**` etc. still render inside cells.
 */
private fun cellAnnotated(
    cell: ASTNode,
    source: CharSequence,
    style: MarkdownStyle,
): AnnotatedString {
    val raw = cell.literalText(source).trim()
    if (raw.isEmpty()) return AnnotatedString("")
    // Some flavours pre-parse inline content into cell children — prefer that when
    // present so we don't re-run the parser unnecessarily.
    if (cell.children.isNotEmpty()) return renderInline(cell, source, style)
    val parsed = MarkdownTreeBuilder.parse(raw)
    val paragraph = parsed.root.children.firstOrNull { it.type == MarkdownElementTypes.PARAGRAPH }
    return if (paragraph != null) renderInline(paragraph, parsed.source, style)
    else AnnotatedString(raw)
}

private fun extractCells(row: ASTNode): List<ASTNode> =
    row.children.filter { it.type == GFMTokenTypes.CELL }

private fun parseColumnAlignments(tableText: String): List<TextAlign> {
    val divider = tableText.lineSequence()
        .firstOrNull { line ->
            val trimmed = line.trim()
            trimmed.contains('-') && trimmed.all { it == '|' || it == ':' || it == '-' || it.isWhitespace() }
        } ?: return emptyList()
    return divider.split('|')
        .map { it.trim() }
        .filter { it.isNotEmpty() }
        .map { segment ->
            val leftColon = segment.startsWith(':')
            val rightColon = segment.endsWith(':')
            when {
                leftColon && rightColon -> TextAlign.Center
                rightColon -> TextAlign.End
                else -> TextAlign.Start
            }
        }
}

private fun <T> List<T>.padTo(size: Int): List<T?> =
    if (this.size >= size) this
    else this + List(size - this.size) { null }
