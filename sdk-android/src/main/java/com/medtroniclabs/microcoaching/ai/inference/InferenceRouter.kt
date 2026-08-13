package com.medtroniclabs.microcoaching.ai.inference

import android.util.Log
import com.medtroniclabs.microcoaching.MicroCoachingConfig
import com.medtroniclabs.microcoaching.ModelDownloadStrategy
import com.medtroniclabs.microcoaching.ai.model.ModelCatalog
import kotlinx.coroutines.flow.StateFlow
import java.io.File

/**
 * Selects the appropriate [LLMService] implementation at runtime based on the model
 * file extension and device capability.
 *
 * Routing rules:
 *   - `.task`  → [GemmaService] (MediaPipe Gemma, any catalog variant)
 *   - No model → [isModelAvailable] = false; chat shows "download required" state
 *
 * This is the single point of truth for which LLM is active.
 *
 * Owned by [SharedInferenceRouter]: ChatViewModels acquire/release the single
 * process-wide instance rather than constructing their own — two routers
 * loading the same `.task` crashes MediaPipe natively, and an embedded chat
 * fragment plus the chat bottom sheet can be alive at the same time. The
 * engine stays loaded while any surface holds a reference and unloads when
 * the last one releases.
 */
class InferenceRouter(private val config: MicroCoachingConfig) {

    private val gemmaService = GemmaService(config.context)

    /** The currently active service, or null if no model is available. */
    var activeService: LLMService? = null
        private set

    /** Returns true when a model file is present and loaded. */
    val isModelAvailable: Boolean get() = activeService?.isModelLoaded?.value == true

    /**
     * Why the last [initializeIfModelPresent] failed, or null if it succeeded or
     * never ran. The load failure is otherwise only logged, leaving the setup
     * screen unable to tell the user why entering chat bounced them back.
     */
    @Volatile
    var lastLoadError: String? = null
        private set

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

        // Runtime guard — only the MediaPipe engine is bundled. A non-MediaPipe
        // variant (e.g. a `.litertlm`) can't load until the LiteRT-LM runtime is
        // re-added; fail loud rather than silently no-op.
        val variant = config.selectedModelVariant()
        if (!ModelCatalog.isRunnable(variant)) {
            Log.e(
                TAG,
                "Selected model '${variant.id}' runtime=${variant.runtime} is not bundled — " +
                    "no engine to load it (LiteRT-LM runtime not bundled). Chat stays in download/unavailable state.",
            )
            lastLoadError = "Selected model '${variant.id}' needs the ${variant.runtime} runtime, which isn't bundled."
            return null
        }

        val modelFile = resolveModelFile() ?: run {
            Log.i(TAG, "No model file found — chat will show 'download required' state")
            lastLoadError = null   // Not an error — nothing has been downloaded yet.
            return null
        }

        // Name, path and exact length of the file about to be handed to the engine, against
        // the expected size. On a load failure this separates "wrong file" from "short file"
        // from "right file, engine problem".
        Log.i(
            TAG,
            "Resolved model: ${modelFile.name} (${modelFile.length()} bytes, " +
                "expected ${variant.sizeInBytes}) at ${modelFile.absolutePath}",
        )

        val service = serviceForFile(modelFile) ?: run {
            Log.w(TAG, "Unrecognised model file extension: ${modelFile.extension}")
            lastLoadError = "No bundled engine can load '${modelFile.name}'."
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
            // Per-variant sampling overrides win; otherwise the global config
            // defaults apply. Smaller models can carry their own tuned values
            // in the catalog without touching the SDK config.
            val llmConfig = LLMConfiguration(
                modelPath = modelFile.absolutePath,
                maxTokens = variant.maxTokens ?: config.maxInferenceTokens,
                temperature = variant.temperature ?: config.inferenceTemperature,
                topK = variant.topK ?: 40,
            )
            service.loadModel(llmConfig)
            activeService = service
            lastLoadError = null
            Log.i(TAG, "Inference engine ready: ${service::class.simpleName} — ${modelFile.name}")
        }.onFailure { cause ->
            Log.e(
                TAG,
                "Failed to load ${modelFile.name} (${modelFile.length()} bytes, " +
                    "expected ${variant.sizeInBytes}): ${cause.message}",
                cause,
            )
            lastLoadError = cause.message ?: cause::class.simpleName
            activeService = null
        }

        return activeService
    }

    /** Find the model file on the device; see the companion overload for the rule. */
    private fun resolveModelFile(): File? = resolveModelFile(
        configuredModelPath = config.modelPath,
        externalDir = config.context.getExternalFilesDir(null),
        expectedFileName = config.selectedModelVariant().fileName,
        canLoad = ::canLoad,
    )

    private fun serviceForFile(file: File): LLMService? =
        if (canLoad(file)) {
            gemmaService
        } else {
            Log.e(TAG, "No engine for '${file.name}' — only MediaPipe `.task` is bundled (LiteRT-LM not bundled)")
            null
        }

    /**
     * True when a model file is present on disk **and** a bundled engine can load
     * it. This is a permanent property of the current configuration, not a
     * transient state: it's false when the selected variant's runtime isn't
     * bundled, or when the resolved file's extension has no engine (e.g. a leftover
     * `.litertlm` that `modelPath` points at). Callers use it to distinguish an
     * un-retryable configuration from a transient load failure, so they can show an
     * honest error instead of looping on "Go to chat". Does no loading — just
     * resolution + a runtime/extension check.
     */
    fun canRunResolvedModel(): Boolean {
        if (!ModelCatalog.isRunnable(config.selectedModelVariant())) return false
        val file = resolveModelFile() ?: return false
        return serviceForFile(file) != null
    }

    /** Release the inference engine. */
    fun release() {
        gemmaService.unloadModel()
        activeService = null
    }

    companion object {
        private const val TAG = "InferenceRouter"

        /**
         * Is there a bundled engine that can load [file]? Only MediaPipe `.task`
         * is bundled. Side-effect-free so [resolveModelFile] can probe a candidate
         * it may be about to skip without logging an error for it.
         */
        internal fun canLoad(file: File): Boolean =
            file.name.endsWith(GemmaService.MODEL_EXTENSION)

        /**
         * Pick the model file to load. Pure — takes the filesystem facts rather
         * than reading config, so the priority rule is testable without a Context.
         *
         * Priority:
         *   1. [configuredModelPath] when it exists, [canLoad] accepts it, **and** its
         *      filename is exactly [expectedFileName].
         *   2. [expectedFileName] inside [externalDir] — matched by exact name, not
         *      "first `.task` on disk", so coexisting variants stay deterministic.
         *
         * The two checks on (1) guard different failures:
         *  - **Loadability**, for a host that scans its model dir and passes something no
         *    bundled engine can load (a leftover `.litertlm`). Preferring it would strand
         *    chat on the setup screen with a loadable file sitting beside it.
         *  - **Filename**, for a host that passes some *other* `.task`. `listFiles()` is
         *    unordered, so a scan can return a leftover from an earlier default model while
         *    [ModelManager] reports the selected variant as ready — the engine and the UI
         *    then describe different files. Matching the catalog is too weak a test here,
         *    since a stale variant is in the catalog too; only the selected name will do.
         */
        internal fun resolveModelFile(
            configuredModelPath: String,
            externalDir: File?,
            expectedFileName: String,
            canLoad: (File) -> Boolean = Companion::canLoad,
        ): File? {
            if (configuredModelPath.isNotBlank()) {
                val explicit = File(configuredModelPath)
                when {
                    !explicit.exists() ->
                        Log.w(TAG, "Configured modelPath does not exist: $configuredModelPath")
                    !canLoad(explicit) ->
                        Log.w(
                            TAG,
                            "Configured modelPath '${explicit.name}' has no bundled engine — " +
                                "ignoring it and falling back to the selected variant's file",
                        )
                    explicit.name != expectedFileName ->
                        Log.w(
                            TAG,
                            "Configured modelPath '${explicit.name}' is not the selected variant " +
                                "('$expectedFileName') — ignoring it so the engine and ModelManager " +
                                "cannot disagree about which file is the model",
                        )
                    else -> return explicit
                }
            }
            return externalDir?.listFiles()?.firstOrNull { it.name == expectedFileName }
        }
    }
}
