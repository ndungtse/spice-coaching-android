package com.medtroniclabs.microcoaching.ai.model

import android.app.Notification
import android.content.Context
import com.medtroniclabs.microcoaching.R
import com.medtroniclabs.microcoaching.ai.download.DownloadNotifications

/**
 * Builds the foreground-service notification surfaced by [ModelDownloadWorker]
 * while the on-device coaching model downloads. Localised via the SDK's
 * configured [com.medtroniclabs.microcoaching.Language] when available; falls
 * back to the device locale if the SDK has not been initialised yet (e.g. a
 * cold-started worker after process death).
 *
 * The channel/notification plumbing is shared with the STT notifier via
 * [DownloadNotifications]; only this download's channel id + strings differ.
 */
internal object ModelDownloadNotifier {

    /** System notification channel id. Kept distinct from the host app's channels. */
    const val CHANNEL_ID = "coaching_model_download"

    /** Foreground-service notification id. Unique within the SDK. */
    const val NOTIFICATION_ID = 47100

    fun ensureChannel(context: Context) = DownloadNotifications.ensureChannel(
        context,
        channelId = CHANNEL_ID,
        channelNameRes = R.string.notification_model_download_channel_name,
        channelDescRes = R.string.notification_model_download_channel_desc,
    )

    fun buildNotification(
        context: Context,
        progress: Int,
        bytesDownloaded: Long,
        totalBytes: Long,
    ): Notification = DownloadNotifications.build(
        context = context,
        channelId = CHANNEL_ID,
        titleRes = R.string.notification_model_download_title,
        channelNameRes = R.string.notification_model_download_channel_name,
        channelDescRes = R.string.notification_model_download_channel_desc,
        progress = progress,
        bytesDownloaded = bytesDownloaded,
        totalBytes = totalBytes,
    )
}
