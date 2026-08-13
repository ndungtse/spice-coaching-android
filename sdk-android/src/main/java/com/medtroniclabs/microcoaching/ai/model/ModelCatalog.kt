package com.medtroniclabs.microcoaching.ai.model

/**
 * On-device engine a [ModelVariant] runs on.
 *
 * Only [MEDIAPIPE] is runnable today — it's the single inference engine bundled in the
 * SDK (`libllm_inference_engine_jni.so`, see `docs/apk-size-analysis.md`). [LITERT_LM]
 * variants are listed for description only and can't load until the LiteRT-LM runtime is
 * re-added. [LLAMA_CPP] is not planned.
 */
enum class ModelRuntime { MEDIAPIPE, LITERT_LM, LLAMA_CPP }

/**
 * One downloadable on-device model. The catalog is the source of truth for a model's URL,
 * on-disk filename, runtime and RAM class; for its size it is only a fallback, since the
 * serving host knows that better than a constant does.
 *
 * @property id              Stable key used by [MicroCoachingConfig.selectedModelId].
 * @property fileName        On-disk name — drives file resolution, so variants can
 *                           coexist and resolution stays deterministic (matched by
 *                           exact name, not "first `.task` on disk").
 * @property sizeInBytes     Display fallback, never a gate — the size shown before any
 *                           server answer arrives. A constant here goes stale when the model
 *                           is republished, so nothing rejects a download by comparing
 *                           against it; `Content-Length` is authoritative and structure
 *                           decides completeness without one. After a download the observed
 *                           `Content-Length` supersedes it ([ModelSizeProbe.recordObservedSize]).
 * @property minDeviceMemoryGb RAM class for this variant. Stored but NOT yet enforced
 *                           below the global 3 GB gate
 *                           ([com.medtroniclabs.microcoaching.domain.system.DeviceCapability]).
 * @property maxTokens/temperature/topK  Optional per-model sampling overrides;
 *                           when null the [MicroCoachingConfig] defaults apply.
 */
data class ModelVariant(
    val id: String,
    val displayName: String,
    val fileName: String,
    val downloadUrl: String,
    val sizeInBytes: Long,
    val runtime: ModelRuntime,
    val minDeviceMemoryGb: Int,
    val requiresAccessToken: Boolean,
    val params: String,
    val maxTokens: Int? = null,
    val temperature: Float? = null,
    val topK: Int? = null,
)

/**
 * Compile-time allowlist of on-device models. Pure constants — adds ~0 to the APK
 * (models still download at runtime; nothing is bundled).
 *
 * Pick the active model with `MicroCoachingSDK.Builder.selectedModel(id)`; the SDK
 * threads the resolved [ModelVariant] through download ([ModelDownloadWorker]),
 * file resolution ([ModelManager.findLocalModel] /
 * [com.medtroniclabs.microcoaching.ai.inference.InferenceRouter]), and load.
 */
object ModelCatalog {

    /**
     * Default model when the host doesn't call `selectedModel(...)`.
     *
     * Set to the **Gemma 3 270M (q8)** `.task` — smaller, conversational, runs on
     * the MediaPipe engine already shipped. The global ≥ 3 GB RAM gate still
     * applies, so this is what loads on capable devices; reaching ~2 GB devices
     * is future work.
     */
    const val DEFAULT_ID = "gemma3-270m-it-q8-task"
    // const val DEFAULT_ID = "gemma3-1b-it-int4-task"

    val ALLOWLIST: List<ModelVariant> = listOf(
        ModelVariant(
            id = "gemma3-270m-it-q8-task",
            displayName = "Gemma 3 270M-IT (q8, MediaPipe)",
            fileName = "gemma3-270m-it-q8.task",
            downloadUrl = "https://huggingface.co/litert-community/gemma-3-270m-it/resolve/main/gemma3-270m-it-q8.task",
            sizeInBytes = 303_950_933L,
            runtime = ModelRuntime.MEDIAPIPE,
            minDeviceMemoryGb = 2,
            requiresAccessToken = true,           // repo answers anonymous requests with GatedRepo
            params = "270M",
        ),
        ModelVariant(
            id = "gemma3-270m-it-q4-task",
            displayName = "Gemma 3 270M-IT (q4, MediaPipe)",
            fileName = "gemma3-270m-it-q4_0-web.task",
            downloadUrl = "https://huggingface.co/litert-community/gemma-3-270m-it/resolve/main/gemma3-270m-it-q4_0-web.task",
            sizeInBytes = 249_233_408L,
            runtime = ModelRuntime.MEDIAPIPE,
            minDeviceMemoryGb = 2,
            requiresAccessToken = true,
            params = "270M",
        ),
        ModelVariant(
            // Listed for completeness; NOT runnable until the LiteRT-LM runtime
            // is re-added. Selecting it today fails loud at load.
            id = "gemma3-270m-it-q8-litertlm",
            displayName = "Gemma 3 270M-IT (q8, LiteRT-LM — not yet runnable)",
            fileName = "gemma3-270m-it-q8.litertlm",
            downloadUrl = "https://huggingface.co/litert-community/gemma-3-270m-it/resolve/main/gemma3-270m-it-q8.litertlm",
            sizeInBytes = 304_005_120L,
            runtime = ModelRuntime.LITERT_LM,
            minDeviceMemoryGb = 2,
            requiresAccessToken = true,
            params = "270M",
        ),
        ModelVariant(
            id = "gemma3-1b-it-int4-task",
            displayName = "Gemma 3 1B-IT (INT4, MediaPipe)",
            fileName = "gemma3-1b-it-int4.task",
            downloadUrl = ModelProvider.HF_TASK_MODEL_URL,
            sizeInBytes = 554_661_243L,
            runtime = ModelRuntime.MEDIAPIPE,
            minDeviceMemoryGb = 3,
            requiresAccessToken = true,           // litert-community/Gemma3-1B-IT is gated
            params = "1B",
        ),
    )

    /**
     * Fraction of [ModelVariant.sizeInBytes] below which a file is obviously not a model —
     * an HTTP error body, a pointer file, a download that barely started.
     *
     * Last resort: consulted only when there is neither a server `Content-Length` nor a
     * structural validator for the format, because anything derived from an expected size
     * can reject a legitimately-resized model. It is also no completeness test on its own,
     * since a zip's central directory lives at the end of the file.
     */
    const val SIZE_FLOOR_FRACTION = 0.85

    fun byId(id: String): ModelVariant? = ALLOWLIST.firstOrNull { it.id == id }

    fun byFileName(name: String): ModelVariant? = ALLOWLIST.firstOrNull { it.fileName == name }

    /** The default variant. Non-null — [DEFAULT_ID] is always present in [ALLOWLIST]. */
    fun default(): ModelVariant = byId(DEFAULT_ID)
        ?: error("DEFAULT_ID '$DEFAULT_ID' missing from ModelCatalog.ALLOWLIST")

    /** Resolve [id] to a variant, falling back to [default] for an unknown id. */
    fun resolve(id: String): ModelVariant = byId(id) ?: default()

    /** True when the variant's runtime is bundled and can actually load today. */
    fun isRunnable(variant: ModelVariant): Boolean = variant.runtime == ModelRuntime.MEDIAPIPE

    /**
     * True when the variant downloads a `.task` zip bundle, so
     * [ModelFileIntegrity.validateTaskBundle] applies. A `.litertlm` is a different
     * container and must not be judged by zip rules.
     */
    fun isTaskBundle(variant: ModelVariant): Boolean = variant.fileName.endsWith(".task")

    /** Per-variant minimum-valid-size floor in bytes. */
    fun minValidSizeBytes(variant: ModelVariant): Long =
        (variant.sizeInBytes * SIZE_FLOOR_FRACTION).toLong()
}
