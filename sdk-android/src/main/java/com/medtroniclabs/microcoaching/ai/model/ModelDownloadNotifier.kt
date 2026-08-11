package com.medtroniclabs.microcoaching.ai.model

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import androidx.core.app.NotificationCompat
import com.medtroniclabs.microcoaching.MicroCoachingSDK
import com.medtroniclabs.microcoaching.R
import com.medtroniclabs.microcoaching.ui.SdkLocaleHelper

/**
 * Builds the foreground-service notification surfaced by [ModelDownloadWorker]
 * while the on-device coaching model downloads. Localised via the SDK's
 * configured [com.medtroniclabs.microcoaching.Language] when available; falls
 * back to the device locale if the SDK has not been initialised yet (e.g. a
 * cold-started worker after process death).
 */
internal object ModelDownloadNotifier {

    /** System notification channel id. Kept distinct from the host app's channels. */
    const val CHANNEL_ID = "coaching_model_download"

    /** Foreground-service notification id. Unique within the SDK. */
    const val NOTIFICATION_ID = 47100

    private const val BYTES_PER_MB: Long = 1_048_576L

    fun ensureChannel(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        val localized = localizedContext(context)
        val channel = NotificationChannel(
            CHANNEL_ID,
            localized.getString(R.string.notification_model_download_channel_name),
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = localized.getString(R.string.notification_model_download_channel_desc)
            setShowBadge(false)
        }
        manager.createNotificationChannel(channel)
    }

    fun buildNotification(
        context: Context,
        progress: Int,
        bytesDownloaded: Long,
        totalBytes: Long,
    ): Notification {
        ensureChannel(context)
        val localized = localizedContext(context)
        val safePercent = progress.coerceIn(0, 100)
        val downloadedMb = (bytesDownloaded / BYTES_PER_MB).coerceAtLeast(0L).toInt()
        val totalMb = (totalBytes / BYTES_PER_MB).coerceAtLeast(0L).toInt()
        val isIndeterminate = totalBytes <= 0L

        val body = if (isIndeterminate) {
            localized.getString(
                R.string.notification_model_download_progress_indeterminate,
                downloadedMb,
            )
        } else {
            localized.getString(
                R.string.notification_model_download_progress,
                downloadedMb,
                totalMb,
                safePercent,
            )
        }

        return NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle(localized.getString(R.string.notification_model_download_title))
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
