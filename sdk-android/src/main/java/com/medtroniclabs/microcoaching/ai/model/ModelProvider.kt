package com.medtroniclabs.microcoaching.ai.model

/**
 * Model download source. [ModelManager] tries each provider in
 * [com.medtroniclabs.microcoaching.MicroCoachingConfig.modelProviders] order,
 * falling back to the next on failure.
 *
 * Configure via the SDK Builder:
 * ```kotlin
 * MicroCoachingSDK.Builder(this)
 *     .modelProviders(listOf(ModelProvider.HuggingFace, ModelProvider.Backend))
 *     .huggingFaceToken(BuildConfig.HF_TOKEN)
 *     .build()
 * ```
 */
sealed class ModelProvider {

    /**
     * Downloads from the MicroCoaching FastAPI backend.
     * Endpoint: `{backendUrl}/api/v1/models/gemma/download`
     * Auth: SPICE JWT via `Authorization: Bearer {authToken}`
     *
     * Requires [com.medtroniclabs.microcoaching.MicroCoachingConfig.backendUrl].
     */
    object Backend : ModelProvider()

    /**
     * Downloads the selected [ModelVariant] from HuggingFace Hub (the variant's
     * `downloadUrl`, or [com.medtroniclabs.microcoaching.MicroCoachingConfig.huggingFaceModelUrl]
     * when set).
     *
     * A token is only needed for a gated repo ([ModelVariant.requiresAccessToken]); the
     * worker attaches the `Authorization` header only when one is supplied via
     * [com.medtroniclabs.microcoaching.MicroCoachingConfig.huggingFaceToken]. The SDK bakes
     * in no default token. The catalog default is a non-gated repo, so it downloads
     * anonymously; the gated 1B variant needs a token.
     */
    object HuggingFace : ModelProvider()

    /**
     * Downloads from Kaggle Datasets.
     *
     * **Not yet implemented.** Included as a placeholder — the SDK logs a warning and skips
     * to the next provider. Kaggle API integration will be added in a future release.
     */
    object Kaggle : ModelProvider()

    /** Serialise to a WorkManager input data string. */
    internal fun toKey(): String = when (this) {
        Backend -> KEY_BACKEND
        HuggingFace -> KEY_HUGGING_FACE
        Kaggle -> KEY_KAGGLE
    }

    companion object {
        /** Download URL for the gated Gemma3-1B-IT INT4 `.task` variant in [ModelCatalog]. */
        const val HF_TASK_MODEL_URL =
            "https://huggingface.co/litert-community/Gemma3-1B-IT/resolve/main/gemma3-1b-it-int4.task"

        // Legacy 1B aliases — kept for source compatibility; [ModelCatalog] is the
        // source of truth for a variant's URL and filename.
        const val DEFAULT_HF_MODEL_URL = HF_TASK_MODEL_URL
        const val HF_TASK_MODEL_FILENAME = "gemma3-1b-it-int4.task"
        const val DEFAULT_HF_MODEL_FILENAME = HF_TASK_MODEL_FILENAME


        /**
         * Empty by design. The SDK does not bake in a HuggingFace token — host apps
         * that need one supply it explicitly via
         * [com.medtroniclabs.microcoaching.MicroCoachingConfig.huggingFaceToken].
         * Kept as a constant so existing call sites resolve to the empty default
         * without a `null` check.
         */
        const val DEFAULT_HF_TOKEN: String = ""

        /** Default provider order — used when the host app does not configure [com.medtroniclabs.microcoaching.MicroCoachingConfig.modelProviders]. */
        val DEFAULT_ORDER: List<ModelProvider> = listOf(Backend, HuggingFace, Kaggle)

        // WorkManager serialisation keys (internal)
        internal const val KEY_BACKEND = "backend"
        internal const val KEY_HUGGING_FACE = "hugging_face"
        internal const val KEY_KAGGLE = "kaggle"

        /** Deserialise from a WorkManager input data string. */
        internal fun fromKey(key: String): ModelProvider? = when (key) {
            KEY_BACKEND -> Backend
            KEY_HUGGING_FACE -> HuggingFace
            KEY_KAGGLE -> Kaggle
            else -> null
        }
    }
}
