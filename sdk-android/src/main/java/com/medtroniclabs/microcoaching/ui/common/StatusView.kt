package com.medtroniclabs.microcoaching.ui.common

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.medtroniclabs.microcoaching.R
import com.medtroniclabs.microcoaching.ui.theme.MutedText

/**
 * How much vertical room a status message claims. The only genuinely orthogonal axis —
 * tone/action are fixed by which wrapper you call, so illegal combinations (a muted
 * "error" with a retry) aren't representable.
 */
enum class StatusDensity {
    /** Centred in the full viewport. For a screen or a whole tab body. */
    FullScreen,

    /** One section slot inside a scrolling page. The default for per-section states. */
    Section,

    /** Compact card, for a notice sitting above real content. */
    Inline,
}

/** Tone of a [NoticeBanner] — a notice is informational, never a failure screen. */
enum class NoticeTone { Muted, Warning }

/** Optional call-to-action on an [EmptyState]. */
data class StatusAction(val label: String, val onClick: () -> Unit)

/**
 * A load failure, with an optional retry.
 *
 * [offline] suppresses [message] in favour of generic offline copy — the raw text in that
 * case is always an unhelpful `Unable to resolve host …`, and an offline device is muted,
 * not red: it isn't a fault. Prefer the [CoachingError] overload; this `String` overload
 * exists for call sites that still carry a pre-resolved message.
 */
@Composable
fun ErrorState(
    message: String?,
    offline: Boolean = false,
    onRetry: (() -> Unit)? = null,
    density: StatusDensity = StatusDensity.FullScreen,
    modifier: Modifier = Modifier,
) {
    StatusCore(
        text = if (offline) {
            stringResource(R.string.common_error_offline)
        } else {
            message?.takeIf { it.isNotBlank() } ?: stringResource(R.string.common_error_generic)
        },
        textColor = if (offline) MutedText else MaterialTheme.colorScheme.error,
        icon = if (offline) Icons.Filled.CloudOff else Icons.Filled.ErrorOutline,
        iconTint = if (offline) MutedText else MaterialTheme.colorScheme.error,
        action = onRetry?.let { StatusAction(stringResource(R.string.common_retry), it) },
        density = density,
        modifier = modifier,
    )
}

/**
 * A load failure described by the [CoachingError] taxonomy — resolves the localized string
 * and the offline tone itself, so no call site has to repeat that decision.
 */
@Composable
fun ErrorState(
    error: CoachingError,
    onRetry: (() -> Unit)? = null,
    density: StatusDensity = StatusDensity.Section,
    modifier: Modifier = Modifier,
) {
    StatusCore(
        text = stringResource(error.stringRes),
        textColor = if (error.isOffline) MutedText else MaterialTheme.colorScheme.error,
        icon = if (error.isOffline) Icons.Filled.CloudOff else Icons.Filled.ErrorOutline,
        iconTint = if (error.isOffline) MutedText else MaterialTheme.colorScheme.error,
        action = onRetry?.let { StatusAction(stringResource(R.string.common_retry), it) },
        density = density,
        modifier = modifier,
    )
}

/**
 * "Nothing here yet" — never red, never an error. Emptiness is a normal outcome: a CHW with
 * no assigned modules has nothing wrong with their device or the backend.
 */
@Composable
fun EmptyState(
    text: String,
    density: StatusDensity = StatusDensity.Section,
    icon: ImageVector? = null,
    action: StatusAction? = null,
    modifier: Modifier = Modifier,
) {
    StatusCore(
        text = text,
        textColor = MutedText,
        icon = icon,
        iconTint = MutedText,
        action = action,
        density = density,
        modifier = modifier,
    )
}

/**
 * Non-blocking notice rendered *above* real content — "showing saved data", "some content
 * couldn't be updated". Never replaces the content it annotates.
 */
@Composable
fun NoticeBanner(
    text: String,
    tone: NoticeTone = NoticeTone.Muted,
    hint: String? = null,
    modifier: Modifier = Modifier,
) {
    val accent = if (tone == NoticeTone.Warning) MaterialTheme.colorScheme.error else MutedText
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(accent.copy(alpha = 0.08f))
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.bodySmall,
            color = accent,
            fontWeight = FontWeight.SemiBold,
        )
        if (hint != null) {
            Text(text = hint, style = MaterialTheme.typography.bodySmall, color = MutedText)
        }
    }
}

/**
 * The single implementation the four wrappers delegate to. Private so tone and layout can
 * only be combined in the ways the wrappers sanction.
 */
@Composable
private fun StatusCore(
    text: String,
    textColor: Color,
    icon: ImageVector?,
    iconTint: Color,
    action: StatusAction?,
    density: StatusDensity,
    modifier: Modifier,
) {
    val fullScreen = density == StatusDensity.FullScreen
    val contentPadding = when (density) {
        StatusDensity.FullScreen -> PaddingValues(32.dp)
        StatusDensity.Section -> PaddingValues(horizontal = 16.dp, vertical = 24.dp)
        StatusDensity.Inline -> PaddingValues(16.dp)
    }
    Column(
        modifier = modifier
            .then(if (fullScreen) Modifier.fillMaxSize() else Modifier.fillMaxWidth())
            .padding(contentPadding),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = if (fullScreen) Arrangement.Center else Arrangement.Top,
    ) {
        if (icon != null) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = iconTint,
                modifier = Modifier.size(if (fullScreen) 48.dp else 32.dp),
            )
            Spacer(Modifier.height(12.dp))
        }
        Text(
            text = text,
            style = if (fullScreen) {
                MaterialTheme.typography.bodyLarge
            } else {
                MaterialTheme.typography.bodyMedium
            },
            color = textColor,
            textAlign = TextAlign.Center,
        )
        if (action != null) {
            Spacer(Modifier.height(16.dp))
            Button(onClick = action.onClick, shape = RoundedCornerShape(12.dp)) {
                Text(text = action.label, fontWeight = FontWeight.SemiBold)
            }
        }
    }
}
