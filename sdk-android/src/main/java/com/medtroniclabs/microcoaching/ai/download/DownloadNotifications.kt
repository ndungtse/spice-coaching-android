package com.medtroniclabs.microcoaching.ai.download

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import androidx.annotation.StringRes
import androidx.core.app.NotificationCompat
import com.medtroniclabs.microcoaching.MicroCoachingSDK
import com.medtroniclabs.microcoaching.R
import com.medtroniclabs.microcoaching.ui.SdkLocaleHelper

/**
 * Shared foreground-service download-notification plumbing, used by both the Gemma
 * model notifier and the STT model notifier.
 *
 * Channel id / notification id / title + channel strings vary per download and are passed
 * in; the MB-and-percent body format is shared (both downloads deliberately use the same
 * `notification_model_download_progress*` strings so the UI feels consistent). STT's
 * "extracting" state is expressed via [extractingBodyRes].
 *
 * Localised via the SDK's configured language when available; falls back to the device
 * locale if the SDK has not been initialised yet (e.g. a cold-started worker after
 * process death).
 */
internal object DownloadNotifications {

    private const val BYTES_PER_MB: Long = 1_048_576L

    fun ensureChannel(
        context: Context,
        channelId: String,
        @StringRes channelNameRes: Int,
        @StringRes channelDescRes: Int,
    ) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        val localized = localizedContext(context)
        val channel = NotificationChannel(
            channelId,
            localized.getString(channelNameRes),
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = localized.getString(channelDescRes)
            setShowBadge(false)
        }
        manager.createNotificationChannel(channel)
    }

    fun build(
        context: Context,
        channelId: String,
        @StringRes titleRes: Int,
        @StringRes channelNameRes: Int,
        @StringRes channelDescRes: Int,
        progress: Int,
        bytesDownloaded: Long,
        totalBytes: Long,
        extracting: Boolean = false,
        @StringRes extractingBodyRes: Int? = null,
    ): Notification {
        ensureChannel(context, channelId, channelNameRes, channelDescRes)
        val localized = localizedContext(context)
        val safePercent = progress.coerceIn(0, 100)
        val downloadedMb = (bytesDownloaded / BYTES_PER_MB).coerceAtLeast(0L).toInt()
        val totalMb = (totalBytes / BYTES_PER_MB).coerceAtLeast(0L).toInt()
        val isIndeterminate = extracting || totalBytes <= 0L

        val body = when {
            extracting && extractingBodyRes != null -> localized.getString(extractingBodyRes)
            totalBytes <= 0L -> localized.getString(
                R.string.notification_model_download_progress_indeterminate,
                downloadedMb,
            )
            else -> localized.getString(
                R.string.notification_model_download_progress,
                downloadedMb,
                totalMb,
                safePercent,
            )
        }

        return NotificationCompat.Builder(context, channelId)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle(localized.getString(titleRes))
            .setContentText(body)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setProgress(100, safePercent, isIndeterminate)
            .build()
    }

    private fun localizedContext(base: Context): Context = try {
        SdkLocaleHelper.wrap(base, MicroCoachingSDK.getInstance().language)
    } catch (t: Throwable) {
        base
    }
}
