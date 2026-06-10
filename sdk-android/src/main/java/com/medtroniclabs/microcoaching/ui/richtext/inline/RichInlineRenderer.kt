package com.medtroniclabs.microcoaching.ui.richtext.inline

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
import com.medtroniclabs.microcoaching.content.richtext.RichInline
import com.medtroniclabs.microcoaching.ui.markdown.MarkdownStyle

/**
 * Builds an [AnnotatedString] from a run of TipTap [RichInline]s, applying the
 * same span/link styling vocabulary as the markdown inline renderer
 * ([com.medtroniclabs.microcoaching.ui.markdown.inline.renderInline]) so rich and
 * markdown bodies look identical. Theming is pulled from the shared [MarkdownStyle].
 */
internal fun renderRichInline(
    inlines: List<RichInline>,
    style: MarkdownStyle,
): AnnotatedString = buildAnnotatedString {
    inlines.forEach { run -> appendRun(run, style) }
}

private fun AnnotatedString.Builder.appendRun(run: RichInline, style: MarkdownStyle) {
    val span = SpanStyle(
        fontWeight = if (run.bold) FontWeight.Bold else null,
        fontStyle = if (run.italic) FontStyle.Italic else null,
        fontFamily = if (run.code) FontFamily.Monospace else null,
        background = if (run.code) style.codeBackground else androidx.compose.ui.graphics.Color.Unspecified,
        textDecoration = decorationFor(run),
    )

    val href = run.href
    if (!href.isNullOrBlank()) {
        val linkStyle = TextLinkStyles(
            span.copy(
                color = style.linkColor,
                textDecoration = TextDecoration.Underline,
            ),
        )
        withLink(LinkAnnotation.Url(href, linkStyle)) { append(run.text) }
    } else {
        withStyle(span) { append(run.text) }
    }
}

private fun decorationFor(run: RichInline): TextDecoration? = when {
    run.strike && run.underline -> TextDecoration.combine(
        listOf(TextDecoration.LineThrough, TextDecoration.Underline),
    )
    run.strike -> TextDecoration.LineThrough
    run.underline -> TextDecoration.Underline
    else -> null
}
