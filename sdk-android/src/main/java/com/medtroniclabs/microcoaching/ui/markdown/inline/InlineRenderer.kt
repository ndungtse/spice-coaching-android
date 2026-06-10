package com.medtroniclabs.microcoaching.ui.markdown.inline

import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withLink
import androidx.compose.ui.text.withStyle
import com.medtroniclabs.microcoaching.ui.markdown.MarkdownStyle
import com.medtroniclabs.microcoaching.ui.markdown.literalText
import org.intellij.markdown.MarkdownElementTypes
import org.intellij.markdown.MarkdownTokenTypes
import org.intellij.markdown.ast.ASTNode
import org.intellij.markdown.ast.findChildOfType
import org.intellij.markdown.flavours.gfm.GFMElementTypes
import org.intellij.markdown.flavours.gfm.GFMTokenTypes

/**
 * Builds an [AnnotatedString] for inline content under [container] (paragraph, heading,
 * list item, table cell). Walks the AST emitting text + styled spans for emphasis,
 * strong, code spans, links and breaks. Block-level child nodes are ignored — only the
 * inline children of [container] are emitted.
 */
internal fun renderInline(
    container: ASTNode,
    source: CharSequence,
    style: MarkdownStyle,
): AnnotatedString = buildAnnotatedString {
    container.children.forEach { child -> appendNode(child, source, style) }
}

private fun AnnotatedString.Builder.appendNode(
    node: ASTNode,
    source: CharSequence,
    style: MarkdownStyle,
) {
    when (node.type) {
        // ── Plain text & whitespace ───────────────────────────────────────────
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
        MarkdownTokenTypes.BAD_CHARACTER,
        -> append(node.literalText(source))

        // Strict CommonMark collapses a single `\n` to a space, but the backend
        // payload authors content one sentence per line and expects each break to
        // be visible. Treat soft breaks the same as hard breaks for rendering.
        // (markdownToSpokenText still collapses EOL to a space so TTS flows.)
        MarkdownTokenTypes.EOL -> append('\n')
        MarkdownTokenTypes.HARD_LINE_BREAK -> append('\n')

        // ── Inline styling ────────────────────────────────────────────────────
        MarkdownElementTypes.EMPH -> withStyle(SpanStyle(fontStyle = FontStyle.Italic)) {
            appendInlineChildren(node, source, style)
        }
        MarkdownElementTypes.STRONG -> withStyle(SpanStyle(fontWeight = FontWeight.Bold)) {
            appendInlineChildren(node, source, style)
        }
        GFMElementTypes.STRIKETHROUGH -> withStyle(SpanStyle(textDecoration = TextDecoration.LineThrough)) {
            appendInlineChildren(node, source, style)
        }
        MarkdownElementTypes.CODE_SPAN -> withStyle(
            SpanStyle(
                fontFamily = FontFamily.Monospace,
                background = style.codeBackground,
            ),
        ) {
            appendInlineChildren(node, source, style)
        }

        // ── Links ─────────────────────────────────────────────────────────────
        MarkdownElementTypes.INLINE_LINK,
        MarkdownElementTypes.FULL_REFERENCE_LINK,
        MarkdownElementTypes.SHORT_REFERENCE_LINK,
        -> {
            val linkTextNode = node.findChildOfType(MarkdownElementTypes.LINK_TEXT)
            val destNode = node.findChildOfType(MarkdownElementTypes.LINK_DESTINATION)
            val url = destNode?.literalText(source)?.trim()?.ifBlank { null }
            val linkStyle = TextLinkStyles(
                SpanStyle(
                    color = style.linkColor,
                    textDecoration = TextDecoration.Underline,
                ),
            )
            if (url != null) {
                withLink(LinkAnnotation.Url(url, linkStyle)) {
                    if (linkTextNode != null) appendInlineChildren(linkTextNode, source, style)
                    else append(url)
                }
            } else {
                if (linkTextNode != null) appendInlineChildren(linkTextNode, source, style)
                else append(node.literalText(source))
            }
        }
        MarkdownElementTypes.AUTOLINK -> {
            val raw = node.literalText(source).trim('<', '>')
            withLink(
                LinkAnnotation.Url(
                    raw,
                    TextLinkStyles(
                        SpanStyle(
                            color = style.linkColor,
                            textDecoration = TextDecoration.Underline,
                        ),
                    ),
                ),
            ) { append(raw) }
        }
        // Image: no inline image rendering yet — show alt text in italics so the
        // reader still gets some context.
        MarkdownElementTypes.IMAGE -> {
            val alt = node.findChildOfType(MarkdownElementTypes.LINK_TEXT)
                ?.literalText(source).orEmpty()
            if (alt.isNotBlank()) {
                withStyle(SpanStyle(fontStyle = FontStyle.Italic)) { append(alt) }
            }
        }

        // ── Markers we deliberately drop ──────────────────────────────────────
        // EMPH token = `*`/`_`, BACKTICK = `` ` ``, ATX_HEADER = `#`s, etc.
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
        -> Unit

        // Anything else: render its visible children so we never lose user text.
        else -> appendInlineChildren(node, source, style)
    }
}

private fun AnnotatedString.Builder.appendInlineChildren(
    node: ASTNode,
    source: CharSequence,
    style: MarkdownStyle,
) {
    node.children.forEach { child -> appendNode(child, source, style) }
}
