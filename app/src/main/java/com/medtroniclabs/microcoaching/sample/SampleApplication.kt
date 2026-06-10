package com.medtroniclabs.microcoaching.sample

import android.app.Application
import com.medtroniclabs.microcoaching.Language
import com.medtroniclabs.microcoaching.MicroCoachingSDK
import com.medtroniclabs.microcoaching.ModelDownloadStrategy
import com.medtroniclabs.microcoaching.ai.model.ModelProvider

/**
 * Sample application demonstrating MicroCoaching SDK initialization.
 *
 * This mirrors how SPICE's SpiceBaseApplication would initialize the SDK.
 *
 * ## Model strategy
 * Automatically adapts to the device state:
 *   - Model file already present → [ModelDownloadStrategy.PROVIDED] (instant, no download)
 *   - No model file found → [ModelDownloadStrategy.ON_FIRST_USE] (downloads on first chat open)
 *
 * To pre-place a model for testing without downloading:
 * ```bash
 * adb push your_model.task /data/local/tmp/model.task
 * adb shell run-as com.medtroniclabs.microcoaching.sample \
 *   cp /data/local/tmp/model.task files/
 * ```
 *
 * ## OTel
 * Logcat debug logging is enabled in debug builds only.
 * To also export to a real SigNoz / Grafana / Jaeger instance, set OTEL_ENDPOINT and
 * OTEL_TOKEN in local.properties (see local.properties.example).
 *
 * ## Secrets
 * Set HUGGING_FACE_TOKEN in local.properties for gated HuggingFace model downloads.
 * See local.properties.example for the full setup guide.
 */
class SampleApplication : Application() {

    override fun onCreate() {
        super.onCreate()
        initMicroCoachingSDK()
    }

    private fun initMicroCoachingSDK() {
        // Detect any existing model file — use it directly and skip the download step.
        // Falls back to ON_FIRST_USE (HuggingFace download) if no file is found.
        val modelDir = filesDir
        val existingModel = modelDir?.listFiles()
            ?.firstOrNull { it.extension == "task" || it.extension == "litertlm" }
        val downloadStrategy = if (existingModel != null) {
            ModelDownloadStrategy.PROVIDED
        } else {
            ModelDownloadStrategy.ON_FIRST_USE
        }

        // OTel: real endpoint if OTEL_ENDPOINT is set in local.properties.
        val otelEndpoint = BuildConfig.OTEL_ENDPOINT
        val otelHeaders = if (BuildConfig.OTEL_TOKEN.isNotBlank()) {
            mapOf("Authorization" to "Bearer ${BuildConfig.OTEL_TOKEN}")
        } else {
            emptyMap()
        }

        val sdk = MicroCoachingSDK.Builder(this)
            .language(Language.BANGLA)
            .enableChat(true)
            // Backend — Coaching Platform (set COACHING_BACKEND_URL in local.properties)
            .backendUrl(BuildConfig.COACHING_BACKEND_URL)
            // Telemetry
            .enableTelemetry(true)
            .otelEndpoint(otelEndpoint)
            .otelHeaders(otelHeaders)
            .otelServiceName("micro-coaching-sample")
            .enableOtelDebugLogging(BuildConfig.DEBUG)
            // Model
            .modelDownloadStrategy(downloadStrategy)
            .modelProviders(listOf(ModelProvider.HuggingFace))
            .modelPath(existingModel?.absolutePath ?: "")
            .huggingFaceToken(BuildConfig.HF_TOKEN)
            .wifiOnlyModelDownload(false)   // allow cellular during dev/emulator testing
            .build()

        // Start periodic background sync (15-min interval, network-gated)
        sdk.syncCoordinator.schedulePeriodic()
    }
}
