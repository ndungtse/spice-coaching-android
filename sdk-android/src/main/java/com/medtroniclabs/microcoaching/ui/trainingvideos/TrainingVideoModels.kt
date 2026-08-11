package com.medtroniclabs.microcoaching.ui.trainingvideos

/**
 * One assigned training video shown in the Training sub-tab. Backed by the
 * `assigned_video` Room table (see
 * [com.medtroniclabs.microcoaching.data.db.entity.AssignedVideoEntity]).
 *
 * @param id canonical video id (== `source_document_id`) — resolves the stream,
 *   persists progress, and keys an offline download.
 * @param title display label.
 * @param category optional grouping label; null for backend-assigned videos
 *   (the meta line then shows only the duration).
 * @param durationMs total length in ms; [durationMin] rounds it up for display.
 * @param thumbnailUrl presigned thumbnail URL, or null (cards fall back to the
 *   gradient + play-icon placeholder).
 * @param progressFraction 0..1 watched fraction — drives the YouTube-style bar.
 * @param completed true once watched to the end (bar shown full).
 * @param lastPositionMs last playback position in ms — the resume anchor.
 * @param download current offline-download state for this video.
 */
data class TrainingVideo(
    val id: String,
    val title: String,
    val category: String? = null,
    val durationMs: Long = 0,
    val thumbnailUrl: String? = null,
    val progressFraction: Float = 0f,
    val completed: Boolean = false,
    val lastPositionMs: Long = 0,
    val download: VideoDownloadState = VideoDownloadState.NotDownloaded,
) {
    /** Whole-minute duration for the meta line (rounds up; 0 when unknown). */
    val durationMin: Int get() = if (durationMs <= 0L) 0 else ((durationMs + 59_999L) / 60_000L).toInt()
}

/** Offline-download state of a training video, driving the card/player affordance. */
sealed interface VideoDownloadState {
    /** Not downloaded — show a download action. */
    data object NotDownloaded : VideoDownloadState

    /** Downloading; [percent] is null while the total size is unknown. */
    data class Downloading(val percent: Int?) : VideoDownloadState

    /** Pinned on disk — plays offline; show a "remove download" action. */
    data object Downloaded : VideoDownloadState
}
