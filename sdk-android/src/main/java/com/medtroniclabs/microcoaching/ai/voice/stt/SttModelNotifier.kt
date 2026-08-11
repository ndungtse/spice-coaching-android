package com.medtroniclabs.microcoaching.ai.voice.stt

import android.app.Notification
import android.content.Context
import com.medtroniclabs.microcoaching.R
import com.medtroniclabs.microcoaching.ai.download.DownloadNotifications

/**
 * Foreground-service notification builder for [SttModelDownloadWorker].
 *
 * Distinct from the Gemma [com.medtroniclabs.microcoaching.ai.model.ModelDownloadNotifier]
 * — different channel id (`coaching_stt_download`), different title, but the same
 * MB / percent body format so the UI feels consistent. Shared plumbing lives in
 * [DownloadNotifications]; STT adds an "extracting" body state.
 */
internal object SttModelNotifier {

    /** System notification channel id. Distinct from the Gemma channel. */
    const val CHANNEL_ID = "coaching_stt_download"

    /** Foreground-service notification id. Unique within the SDK. */
    const val NOTIFICATION_ID = 47200

    fun ensureChannel(context: Context) = DownloadNotifications.ensureChannel(
        context,
        channelId = CHANNEL_ID,
        channelNameRes = R.string.notification_stt_download_channel_name,
        channelDescRes = R.string.notification_stt_download_channel_desc,
    )

    fun buildNotification(
        context: Context,
        progress: Int,
        bytesDownloaded: Long,
        totalBytes: Long,
        extracting: Boolean = false,
    ): Notification = DownloadNotifications.build(
        context = context,
        channelId = CHANNEL_ID,
        titleRes = R.string.notification_stt_download_title,
        channelNameRes = R.string.notification_stt_download_channel_name,
        channelDescRes = R.string.notification_stt_download_channel_desc,
        progress = progress,
        bytesDownloaded = bytesDownloaded,
        totalBytes = totalBytes,
        extracting = extracting,
        extractingBodyRes = R.string.notification_stt_download_extracting,
    )
}
