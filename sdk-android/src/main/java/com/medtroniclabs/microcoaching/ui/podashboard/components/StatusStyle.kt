package com.medtroniclabs.microcoaching.ui.podashboard.components

import androidx.compose.ui.graphics.Color
import com.medtroniclabs.microcoaching.ui.podashboard.SkStatus

// Shared PO-dashboard palette (status colours, muted text, progress track).
internal val StatusGreenBg = Color(0xFFD1FAE5)
internal val StatusGreen = Color(0xFF059669)
internal val StatusOrangeBg = Color(0xFFFFEDD5)
internal val StatusOrange = Color(0xFFEA580C)
internal val StatusRedBg = Color(0xFFFEE2E2)
internal val StatusRed = Color(0xFFDC2626)
internal val MutedText = com.medtroniclabs.microcoaching.ui.theme.MutedText
internal val ProgressTrack = Color(0xFFE5E7EB)

internal fun statusBg(status: SkStatus): Color = when (status) {
    SkStatus.ACTIVE -> StatusGreenBg
    SkStatus.NEEDS_ATTENTION -> StatusOrangeBg
    SkStatus.INACTIVE -> StatusRedBg
}

internal fun statusFg(status: SkStatus): Color = when (status) {
    SkStatus.ACTIVE -> StatusGreen
    SkStatus.NEEDS_ATTENTION -> StatusOrange
    SkStatus.INACTIVE -> StatusRed
}
