package com.medtroniclabs.microcoaching.ai.download

import android.util.Log
import com.medtroniclabs.microcoaching.BuildConfig
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.TimeUnit

/** Outcome of a [ResumableHttpDownloader.download]; the caller already holds the output [File]. */
internal sealed class DownloadResult {
    object Success : DownloadResult()
    data class Failure(val reason: String) : DownloadResult()
}

/**
 * Streams a URL to a file with HTTP `Range` resume, throttled progress, and a size-floor
 * completeness check. Shared by `ModelDownloadWorker` and `SttModelDownloadWorker`.
 *
 * Owns only the network→disk stream (including 416 handling and completeness verification),
 * reporting progress through [onProgress]. Each worker keeps its own
 * `Notification`/`setForeground`/`setProgress` plumbing, which is `CoroutineWorker`-bound.
 */
internal object ResumableHttpDownloader {

    private const val BUFFER_SIZE = 8 * 1024  // 8 KB
    private const val PROGRESS_EMIT_INTERVAL_MS = 500L

    /**
     * Streams [url] to [outputFile], resuming from existing bytes if the server supports Range.
     * Reports progress (0–99) via [onProgress]. Verifies the result against [minValidBytes] and
     * deletes an incomplete/too-small file so the next attempt starts fresh. [logTag] is used for
     * this download's logcat lines; [headers] carries any auth (empty for public URLs).
     *
     * @param validate optional structural check on the finished file — null when the bytes are
     *   usable, else a short reason. Needed because a chunked response leaves no `Content-Length`
     *   to compare against, and a container whose directory sits at the end of the file clears
     *   any byte threshold while still being unopenable. A rejected file is deleted here, so
     *   callers' fallback and retry paths need no extra branches. Null skips the check, as the
     *   STT `tar.bz2` requires.
     */
    suspend fun download(
        url: String,
        outputFile: File,
        minValidBytes: Long,
        headers: Map<String, String> = emptyMap(),
        logTag: String = "ResumableHttpDownloader",
        validate: ((File) -> String?)? = null,
        onProgress: suspend (percent: Int, bytesDownloaded: Long, totalBytes: Long) -> Unit,
    ): DownloadResult = runCatching {
        val client = OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(300, TimeUnit.SECONDS)
            .build()

        // Resume from partial file
        val existingBytes = if (outputFile.exists()) outputFile.length() else 0L
        val requestBuilder = Request.Builder().url(url)
        headers.forEach { (k, v) -> requestBuilder.header(k, v) }
        if (existingBytes > 0L) {
            requestBuilder.header("Range", "bytes=$existingBytes-")
        }

        val response = client.newCall(requestBuilder.build()).execute()

        // Always close the response to avoid OkHttp connection leaks on early returns.
        response.use { resp ->
            val isPartialContent = resp.code == 206
            Log.d(logTag, "streamDownload → HTTP ${resp.code} ${resp.message} | url=$url")

            if (resp.code == 416) {
                // Range not satisfiable — the server rejected our resume offset. A local file
                // at or above the floor is probably the complete download, but it still has to
                // pass [validate] to count as one. Below the floor it is a corrupt partial
                // either way — delete it so the next attempt starts from zero.
                val localSize = outputFile.length()
                return@runCatching if (localSize >= minValidBytes) {
                    val defect = validate?.invoke(outputFile)
                    if (defect == null) {
                        Log.i(logTag, "HTTP 416 — local file is ${localSize / 1_048_576} MB and valid, treating as already complete")
                        DownloadResult.Success
                    } else {
                        outputFile.delete()
                        Log.w(logTag, "HTTP 416 — local file is ${localSize / 1_048_576} MB but $defect — deleted, retry to start fresh")
                        DownloadResult.Failure("Existing file rejected: $defect — file deleted, retry required")
                    }
                } else {
                    outputFile.delete()
                    Log.w(logTag, "HTTP 416 — partial file deleted (${localSize / 1_048_576} MB), retry to start fresh")
                    DownloadResult.Failure("HTTP 416: partial file removed — retry to restart download")
                }
            }

            if (!resp.isSuccessful && !isPartialContent) {
                if (BuildConfig.DEBUG) {
                    val errorBody = resp.body?.string()?.take(500) ?: "<no body>"
                    Log.e(logTag, "streamDownload failed | HTTP ${resp.code} | body=$errorBody | headers=${resp.headers}")
                } else {
                    Log.e(logTag, "streamDownload failed | HTTP ${resp.code}: ${resp.message}")
                }
                return@runCatching DownloadResult.Failure("HTTP ${resp.code}: ${resp.message}")
            }

            val body = resp.body
                ?: return@runCatching DownloadResult.Failure("Empty response body")

            val contentLength = body.contentLength()
            val totalBytes = if (contentLength > 0L) contentLength + existingBytes else 0L
            val appendToFile = existingBytes > 0L && isPartialContent

            if (existingBytes > 0L) {
                Log.i(logTag, "Resuming download from byte $existingBytes (${existingBytes / 1_048_576} MB already present)")
            }

            body.byteStream().use { input ->
                FileOutputStream(outputFile, appendToFile).use { output ->
                    val buffer = ByteArray(BUFFER_SIZE)
                    var bytesRead: Int
                    var totalRead = existingBytes

                    // Throttle progress emission: every percent change, or every
                    // PROGRESS_EMIT_INTERVAL_MS, whichever comes first. Without throttling
                    // each 8 KB iteration would spam setForeground and saturate the
                    // notification system.
                    var lastEmitMs = 0L
                    var lastEmittedPercent = -1

                    // First emit happens once we already have a stream open so the
                    // notification flips from the initial "0 MB" to a real number quickly.
                    onProgress(
                        if (totalBytes > 0L) {
                            ((totalRead * 100L) / totalBytes).toInt().coerceIn(0, 99)
                        } else 0,
                        totalRead,
                        totalBytes,
                    )
                    lastEmitMs = System.currentTimeMillis()

                    while (input.read(buffer).also { bytesRead = it } != -1) {
                        output.write(buffer, 0, bytesRead)
                        totalRead += bytesRead

                        val now = System.currentTimeMillis()
                        val percent = if (totalBytes > 0L) {
                            ((totalRead * 100L) / totalBytes).toInt().coerceIn(0, 99)
                        } else {
                            -1
                        }
                        val percentChanged = percent != -1 && percent != lastEmittedPercent
                        val intervalElapsed = (now - lastEmitMs) >= PROGRESS_EMIT_INTERVAL_MS
                        if (percentChanged || intervalElapsed) {
                            lastEmitMs = now
                            lastEmittedPercent = percent
                            onProgress(percent.coerceAtLeast(0), totalRead, totalBytes)
                        }
                    }
                    output.flush()
                }
            }

            // Completeness, by three tests in descending order of authority. The ordering
            // exists so that a locally-configured expected size never rejects a file: such a
            // constant goes stale as soon as the model is republished.
            val finalSize = outputFile.length()

            // 1. The server's Content-Length. When the server states a length, short is short.
            if (totalBytes > 0L && finalSize < totalBytes) {
                outputFile.delete()
                return@runCatching DownloadResult.Failure(
                    "Incomplete download: received $finalSize of $totalBytes bytes — file deleted"
                )
            }

            // 2. The file's structure. Carries the decision when the server gave no length
            //    (HuggingFace often uses chunked transfer): a missing tail makes the archive
            //    unopenable, while any legitimate size opens fine.
            val defect = validate?.invoke(outputFile)

            // 3. A size floor, only where neither of the above can help: no Content-Length
            //    and no validator. Narrow by design — it derives from an expected size, so a
            //    republished model could turn it into a false rejection.
            if (totalBytes == 0L && validate == null && finalSize < minValidBytes) {
                outputFile.delete()
                return@runCatching DownloadResult.Failure(
                    "Download too small ($finalSize bytes < $minValidBytes minimum) — file deleted, retry required"
                )
            }

            if (defect != null) {
                outputFile.delete()
                Log.e(
                    logTag,
                    "Downloaded $finalSize bytes but the file is unusable: $defect — deleted, retry required",
                )
                return@runCatching DownloadResult.Failure(
                    "Downloaded file is unusable: $defect — file deleted, retry required"
                )
            }

            DownloadResult.Success
        }
    }.getOrElse { cause ->
        Log.e(logTag, "streamDownload error for $url: ${cause.message}")
        DownloadResult.Failure(cause.message ?: "Unknown error")
    }
}
