package com.medtroniclabs.microcoaching.ui.podashboard.drilldown

import androidx.compose.runtime.Composable
import com.medtroniclabs.microcoaching.ui.common.ErrorState
import com.medtroniclabs.microcoaching.ui.common.StatusDensity

/**
 * Full-screen fetch-failure state for the dashboard drill-downs that hit the network.
 *
 * Thin delegate to the shared [ErrorState] — the offline-suppresses-the-raw-message rule
 * this component pioneered now lives there and is used SDK-wide. Kept so the seven
 * drill-down call sites read unchanged; inline it when they migrate to `SectionState`.
 */
@Composable
internal fun DashboardErrorState(
    offline: Boolean,
    message: String?,
    onRetry: () -> Unit,
) {
    ErrorState(
        message = message,
        offline = offline,
        onRetry = onRetry,
        density = StatusDensity.FullScreen,
    )
}
