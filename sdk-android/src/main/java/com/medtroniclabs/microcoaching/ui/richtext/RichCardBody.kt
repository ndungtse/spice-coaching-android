package com.medtroniclabs.microcoaching.ui.richtext

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import com.medtroniclabs.microcoaching.content.richtext.parseRichBody
import com.medtroniclabs.microcoaching.ui.markdown.MarkdownDefaults
import com.medtroniclabs.microcoaching.ui.markdown.MarkdownStyle
import com.medtroniclabs.microcoaching.ui.markdown.MarkdownText

/**
 * Single entry point for rendering a card body that may be **either** a
 * TipTap/ProseMirror JSON array **or** a legacy markdown string.
 *
 * Drop-in replacement for [MarkdownText] at card call sites: if [raw] parses as a
 * TipTap block array it renders via [RichText] (images, video, bold, links); a
 * markdown string falls through to the existing [MarkdownText] renderer. Both
 * share [MarkdownStyle] so the output is visually consistent either way.
 */
@Composable
fun RichCardBody(
    raw: String,
    modifier: Modifier = Modifier,
    style: MarkdownStyle = MarkdownDefaults.style(),
) {
    if (raw.isBlank()) return
    val blocks = remember(raw) { parseRichBody(raw) }
    if (blocks != null) {
        RichText(blocks = blocks, modifier = modifier, style = style)
    } else {
        MarkdownText(content = raw, modifier = modifier, style = style)
    }
}
