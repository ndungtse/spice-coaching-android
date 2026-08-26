package com.medtroniclabs.microcoaching.ui.theme

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance

/**
 * Composites [color] over [surface] at [fraction] in sRGB space, returning an **opaque**
 * colour.
 *
 * Deliberately not `androidx.compose.ui.graphics.lerp`, which interpolates perceptually:
 * at fraction 0.70 over white, `lerp` returns #556CD7 while this returns #665BD2. Only
 * sRGB compositing reproduces what drawing `color.copy(alpha = fraction)` over [surface]
 * would look like, which is the whole point — the SDK needs the *look* of alpha without
 * the translucency, so chat bubbles and headers do not let scrolling content bleed
 * through.
 */
internal fun blendOver(surface: Color, color: Color, fraction: Float): Color {
    val f = fraction.coerceIn(0f, 1f)
    return Color(
        red = color.red * f + surface.red * (1 - f),
        green = color.green * f + surface.green * (1 - f),
        blue = color.blue * f + surface.blue * (1 - f),
    )
}

/**
 * The unfilled portion of a progress indicator: the bar's own [fill] at low alpha.
 *
 * Genuinely translucent rather than blended, because tracks sit on cards of varying
 * tint. Derived from the fill rather than from a fixed neutral so that a purple, green
 * or red bar keeps a track of the same hue — `ModuleTile.kt` already set
 * `trackColor = SpiceBlueContainer` by hand, which is this rule applied once.
 */
internal fun trackFor(fill: Color): Color = fill.copy(alpha = 0.15f)

/** WCAG 2.1 contrast ratio. 4.5 is the AA floor for normal text, 7.0 is AAA. */
internal fun Color.contrastAgainst(other: Color): Float {
    val a = luminance()
    val b = other.luminance()
    return (maxOf(a, b) + 0.05f) / (minOf(a, b) + 0.05f)
}

/**
 * Picks whichever of [light] or [dark] reads better on [background].
 *
 * Exists because derived colours follow the host's `primary`. A host with a pale brand
 * colour would otherwise get white-on-pale chat bubbles and white-on-pale badge labels,
 * which is a contrast failure the SDK can avoid rather than push onto integrators.
 */
internal fun onColorFor(background: Color, light: Color, dark: Color): Color =
    if (background.contrastAgainst(light) >= background.contrastAgainst(dark)) light else dark
