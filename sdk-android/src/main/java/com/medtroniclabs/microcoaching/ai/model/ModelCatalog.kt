package com.medtroniclabs.microcoaching.ai.model

/**
 * On-device engine a [ModelVariant] runs on.
 *
 * Only [LITERT_LM] is bundled, so only it is runnable — every model in [ModelCatalog] is
 * published in that format, which is what makes a single native runtime enough. Selecting
 * a variant on any other engine fails loudly at load; [MEDIAPIPE] and [LLAMA_CPP] remain
 * only so that a stored runtime value still compiles.
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
 * @property maxTokens/temperature/topK/topP  Optional per-model sampling overrides;
 *                           when null the [MicroCoachingConfig] defaults apply. `topP`
 *                           `topP` is read by the LiteRT-LM engine only.
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
    val topP: Float? = null,
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
     * Qwen3-0.6B was the strongest of the candidates evaluated against the CHW question
     * set. The Gemma entries below it are the fallbacks, on the same engine, so changing
     * this constant is the whole of a rollback: variants have distinct filenames and
     * coexist on disk. The global RAM gate still applies on top.
     */
    const val DEFAULT_ID = "qwen3-0-6b-mixed-int4-litertlm"
    // Rollback targets, in order of preference — same engine, so switching one of these
    // in costs a re-download and nothing else:
    // const val DEFAULT_ID = "gemma3-1b-it-int4-litertlm"
    // const val DEFAULT_ID = "gemma3-270m-it-q8-litertlm"

    val ALLOWLIST: List<ModelVariant> = listOf(
        // ── Gemma — the rollback path ────────────────────────────────────────────
        // Both repos are gated, so falling back to Gemma needs `huggingFaceToken(...)`.
        ModelVariant(
            id = "gemma3-270m-it-q8-litertlm",
            displayName = "Gemma 3 270M-IT (q8, LiteRT-LM)",
            fileName = "gemma3-270m-it-q8.litertlm",
            downloadUrl = "https://huggingface.co/litert-community/gemma-3-270m-it/resolve/main/gemma3-270m-it-q8.litertlm",
            sizeInBytes = 304_005_120L,
            runtime = ModelRuntime.LITERT_LM,
            minDeviceMemoryGb = 2,
            requiresAccessToken = true,
            params = "270M",
        ),
        ModelVariant(
            id = "gemma3-1b-it-int4-litertlm",
            displayName = "Gemma 3 1B-IT (INT4, LiteRT-LM)",
            fileName = "gemma3-1b-it-int4.litertlm",
            downloadUrl = "https://huggingface.co/litert-community/Gemma3-1B-IT/resolve/main/gemma3-1b-it-int4.litertlm",
            // Unconfirmed — the repo is gated, so no anonymous request can read the real
            // length. Tolerable because the served `Content-Length` is recorded during the
            // download ([ModelSizeProbe.recordObservedSize]) and outranks this wherever it
            // decides anything, the completeness check included; it only shapes the size
            // shown beforehand.
            sizeInBytes = 584_661_243L,
            runtime = ModelRuntime.LITERT_LM,
            minDeviceMemoryGb = 3,
            requiresAccessToken = true,
            params = "1B",
        ),
        ModelVariant(
            id = "qwen3-0-6b-mixed-int4-litertlm",
            displayName = "Qwen3 0.6B (mixed INT4, LiteRT-LM)",
            fileName = "qwen3_0_6b_mixed_int4.litertlm",
            downloadUrl = "https://huggingface.co/litert-community/Qwen3-0.6B/resolve/main/qwen3_0_6b_mixed_int4.litertlm",
            sizeInBytes = 497_664_000L,
            runtime = ModelRuntime.LITERT_LM,
            // Held at the 1B class until resident cost is measured on device rather
            // than guessed downward.
            minDeviceMemoryGb = 3,
            requiresAccessToken = false,          // apache-2.0, ungated: anonymous requests get the bytes
            params = "0.6B",
            // The values the candidate comparison ran at, plus Qwen3's own recommendation
            // for non-thinking mode — not the config defaults.
            temperature = 0.2f,
            topK = 20,
            topP = 0.8f,
        ),
        ModelVariant(
            // Experimental: upstream has flagged this build as unsettled. Prefer the
            // mixed-INT4 entry until it is known to load cleanly.
            id = "qwen3-0-6b-litertlm",
            displayName = "Qwen3 0.6B (LiteRT-LM — experimental)",
            fileName = "Qwen3-0.6B.litertlm",
            downloadUrl = "https://huggingface.co/litert-community/Qwen3-0.6B/resolve/main/Qwen3-0.6B.litertlm",
            sizeInBytes = 614_236_160L,
            runtime = ModelRuntime.LITERT_LM,
            minDeviceMemoryGb = 3,
            requiresAccessToken = false,
            params = "0.6B",
            temperature = 0.2f,
            topK = 20,
            topP = 0.8f,
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
    fun isRunnable(variant: ModelVariant): Boolean = variant.runtime == ModelRuntime.LITERT_LM

    /**
     * True when the variant downloads a `.litertlm` container, so
     * [ModelFileIntegrity.validateLiteRtLmBundle] applies. Every entry in [ALLOWLIST]
     * qualifies; the check stays because a container with no validator must never be
     * adopted as though it had passed one.
     */
    fun isLiteRtLmBundle(variant: ModelVariant): Boolean = variant.fileName.endsWith(".litertlm")

    /** Per-variant minimum-valid-size floor in bytes. */
    fun minValidSizeBytes(variant: ModelVariant): Long =
        (variant.sizeInBytes * SIZE_FLOOR_FRACTION).toLong()
}
