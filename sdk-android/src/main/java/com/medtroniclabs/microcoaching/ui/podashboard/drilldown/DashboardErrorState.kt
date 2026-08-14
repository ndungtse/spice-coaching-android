package com.medtroniclabs.microcoaching.ui.podashboard.drilldown

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.medtroniclabs.microcoaching.R
import com.medtroniclabs.microcoaching.ui.common.ErrorState
import com.medtroniclabs.microcoaching.ui.common.StatusDensity

/**
 * Full-screen fetch-failure state for the dashboard drill-downs that hit the network.
 *
 * Thin delegate to the shared [ErrorState] — the offline-suppresses-the-raw-message rule
 * this component pioneered now lives there and is used SDK-wide. Kept so the drill-down
 * call sites read unchanged; inline it when they migrate to `SectionState`.
 *
 * [isAuth] (HTTP 401) is a stale session, so a retry can't fix it — show "log out and back
 * in" guidance and drop the Retry action instead of the generic error + retry.
 */
@Composable
internal fun DashboardErrorState(
    offline: Boolean,
    message: String?,
    onRetry: () -> Unit,
    isAuth: Boolean = false,
) {
    if (isAuth && !offline) {
        ErrorState(
            message = stringResource(R.string.po_error_session_expired) + " " +
                stringResource(R.string.po_error_session_expired_hint),
            offline = false,
            onRetry = null,
            density = StatusDensity.FullScreen,
        )
    } else {
        ErrorState(
            message = message,
            offline = offline,
            onRetry = onRetry,
            density = StatusDensity.FullScreen,
        )
    }
}
