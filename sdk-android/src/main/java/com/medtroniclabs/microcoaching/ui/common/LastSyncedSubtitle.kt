package com.medtroniclabs.microcoaching.ui.common

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import com.medtroniclabs.microcoaching.R
import com.medtroniclabs.microcoaching.sync.SyncPrefs
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Live "Last synced …" subtitle for a coaching/modules screen header.
 *
 * Reflects the last successful INBOUND sync — when the CHW's coaching content was
 * last pulled from the backend (outbound is telemetry going the other way and
 * isn't shown here). Backed by [SyncPrefs.observeLastInboundSyncAt] as a Flow, so
 * the label refreshes the moment a sync lands (e.g. after a pull-to-refresh)
 * without the screen being reopened.
 *
 * Returns the localized "Not synced yet" string until the first inbound sync.
 * Shared by the role-aware headers ([com.medtroniclabs.microcoaching.ui.coaching.SKCoachingScreen],
 * [com.medtroniclabs.microcoaching.ui.coaching.POCoachingScreen]) so both agree on
 * the wording and format.
 */
@Composable
fun rememberLastSyncedSubtitle(): String {
    val context = LocalContext.current
    val syncPrefs = remember(context) { SyncPrefs(context) }
    val lastSyncedAt by remember(syncPrefs) { syncPrefs.observeLastInboundSyncAt() }
        .collectAsState(initial = syncPrefs.lastInboundSyncAt)
    return if (lastSyncedAt <= 0L) {
        stringResource(R.string.modules_last_synced_never)
    } else {
        stringResource(R.string.modules_last_synced, com.medtroniclabs.microcoaching.util.shortDateTimeLabel(lastSyncedAt))
    }
}
