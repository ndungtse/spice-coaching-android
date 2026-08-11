package com.medtroniclabs.microcoaching.ui.learn.modules.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.medtroniclabs.microcoaching.R

/**
 * Content-domain tag (Med-I617) shown on Learning Library & Practice Zone cards.
 *
 * Renders exactly one of Clinical / Digital / Operational — the taxonomy carried
 * on [com.medtroniclabs.microcoaching.ui.learn.LearnModule.contentDomain]
 * (backed by `module.content_domain`). A null/absent value defaults to **Clinical**
 * per the documented default, so every card always shows a tag. An unrecognised
 * non-null value renders nothing (forward-compatible: an unknown future domain is
 * omitted rather than mislabelled).
 *
 * Mirrors the small rounded-pill idiom of `StatusChip` in `ModuleCard.kt`, one
 * distinct soft colour per domain.
 */
@Composable
fun ContentDomainTag(contentDomain: String?, modifier: Modifier = Modifier) {
    val style = contentDomainStyle(contentDomain) ?: return
    Text(
        text = stringResource(style.labelRes),
        style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.SemiBold),
        color = style.foreground,
        modifier = modifier
            .background(color = style.background, shape = RoundedCornerShape(6.dp))
            .padding(horizontal = 8.dp, vertical = 3.dp),
    )
}

private data class ContentDomainStyle(
    val labelRes: Int,
    val background: Color,
    val foreground: Color,
)

/**
 * Resolves the pill label + colours for a raw `content_domain` value. Null → the
 * Clinical default; an unknown non-null value → null (tag omitted).
 */
private fun contentDomainStyle(contentDomain: String?): ContentDomainStyle? =
    when (contentDomain?.trim()?.lowercase() ?: "clinical") {
        "clinical" -> ContentDomainStyle(
            R.string.content_domain_clinical,
            background = Color(0xFFE3F3FA), // soft blue
            foreground = Color(0xFF004B87),
        )
        "digital" -> ContentDomainStyle(
            R.string.content_domain_digital,
            background = Color(0xFFEDE7FB), // soft violet
            foreground = Color(0xFF4A2A9C),
        )
        "operational" -> ContentDomainStyle(
            R.string.content_domain_operational,
            background = Color(0xFFFBEEE3), // soft peach
            foreground = Color(0xFF8A4B12),
        )
        else -> null
    }
