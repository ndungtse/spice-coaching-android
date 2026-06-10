package com.medtroniclabs.microcoaching.ai.inference

import android.util.Log
import com.medtroniclabs.microcoaching.MicroCoachingConfig
import com.medtroniclabs.microcoaching.ModelDownloadStrategy
import kotlinx.coroutines.flow.StateFlow
import java.io.File

/**
 * Selects the appropriate [LLMService] implementation at runtime based on the model
 * file extension and device capability.
 *
 * Routing rules:
 *   - `.task`     → [GemmaService] (MediaPipe Gemma 3; works on all field devices)
 *   - `.litertlm` → [LiteRtLmService] (LiteRT-LM Gemma 4; requires 6+ GB RAM)
 *   - No model    → [isModelAvailable] = false; chat shows "download required" state
 *
 * This is the single point of truth for which LLM is active.
 *
 * Owned per [ChatViewModel]: one router is created in the VM and released in
 * `onCleared`. Inference is chat-only today, and [CoachingChatBottomSheet.show]
 * guarantees a single live chat sheet at a time, so exactly one router (and one
 * loaded engine) is resident while chat is open. If a second LLM consumer is
 * ever added (e.g. edge-coaching generation), promote this to an SDK-owned
 * instance with refcounted release rather than re-introducing per-consumer
 * routers — two routers loading the same `.task` crashes MediaPipe natively.
 */
class InferenceRouter(private val config: MicroCoachingConfig) {

    private val gemmaService = GemmaService(config.context)
    private val liteRtLmService = LiteRtLmService(config.context)

    /** The currently active service, or null if no model is available. */
    var activeService: LLMService? = null
        private set

    /** Returns true when a model file is present and loaded. */
    val isModelAvailable: Boolean get() = activeService?.isModelLoaded?.value == true

    /**
     * Detect the model file and initialize the appropriate engine.
     * Call this once at app start (or on-first-use, depending on download strategy).
     *
     * **Idempotent.** Subsequent calls when an engine is already loaded return
     * the existing [activeService] without re-invoking `loadModel`. Loading the
     * same Gemma `.task` twice on the same instance crashes MediaPipe's native
     * inference engine (`libllm_inference_engine_jni.so`), so the guard is
     * defense-in-depth on top of the call-site dedup in
     * [ChatViewModel.observeModelState].
     *
     * @return The loaded [LLMService], or null if no model file is found.
     */
    suspend fun initializeIfModelPresent(): LLMService? {
        activeService?.let { existing ->
            if (existing.isModelLoaded.value) {
                Log.d(TAG, "initializeIfModelPresent: already loaded — returning existing service")
                return existing
            }
        }

        val modelFile = resolveModelFile() ?: run {
            Log.i(TAG, "No model file found — chat will show 'download required' state")
            return null
        }

        val service = serviceForFile(modelFile) ?: run {
            Log.w(TAG, "Unrecognised model file extension: ${modelFile.extension}")
            return null
        }

        runCatching {
            // Final check in case a concurrent caller raced past the top guard
            // and loaded into this same service between checks.
            if (service.isModelLoaded.value) {
                activeService = service
                Log.d(TAG, "initializeIfModelPresent: service loaded by concurrent caller — adopting")
                return@runCatching
            }
            val llmConfig = LLMConfiguration(
                modelPath = modelFile.absolutePath,
                maxTokens = config.maxInferenceTokens,
                temperature = config.inferenceTemperature,
            )
            service.loadModel(llmConfig)
            activeService = service
            Log.i(TAG, "Inference engine ready: ${service::class.simpleName} — ${modelFile.name}")
        }.onFailure { cause ->
            Log.e(TAG, "Failed to load model ${modelFile.name}: ${cause.message}")
            activeService = null
        }

        return activeService
    }

    /**
     * Find the model file on the device.
     *
     * Priority order:
     *   1. [MicroCoachingConfig.modelPath] if explicitly set (PROVIDED strategy)
     *   2. First `.task` file in app-internal files dir
     *   3. First `.litertlm` file in app-internal files dir
     */
    private fun resolveModelFile(): File? {
        if (config.modelPath.isNotBlank()) {
            val explicit = File(config.modelPath)
            if (explicit.exists()) return explicit
            Log.w(TAG, "Configured modelPath does not exist: ${config.modelPath}")
        }

        val internalDir = config.context.filesDir ?: return null

        // Prefer Gemma 3 (.task) for compatibility with field devices
        return internalDir.listFiles()
            ?.firstOrNull { it.extension == "task" }
            ?: internalDir.listFiles()?.firstOrNull { it.extension == "litertlm" }
    }

    private fun serviceForFile(file: File): LLMService? = when {
        file.name.endsWith(GemmaService.MODEL_EXTENSION) -> gemmaService
        file.name.endsWith(LiteRtLmService.MODEL_EXTENSION) -> liteRtLmService
        else -> null
    }

    /** Release resources for both engines. */
    fun release() {
        gemmaService.unloadModel()
        liteRtLmService.unloadModel()
        activeService = null
    }

    companion object {
        private const val TAG = "InferenceRouter"
    }
}
