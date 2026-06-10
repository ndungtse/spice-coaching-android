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
 *     .huggingFaceToken(yourHuggingFaceToken)
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
     * Downloads from HuggingFace Hub.
     * Default model: Gemma3-1B-IT INT4 in LiteRT format (~1.1 GB).
     *
     * Gated model — requires [com.medtroniclabs.microcoaching.MicroCoachingConfig.huggingFaceToken].
     * Get a token at https://huggingface.co/settings/tokens, then request access to the model repo.
     *
     * Override the URL via [com.medtroniclabs.microcoaching.MicroCoachingConfig.huggingFaceModelUrl]
     * if you want to target a different model file or revision.
     */
    object HuggingFace : ModelProvider()

    /** Serialise to a WorkManager input data string. */
    internal fun toKey(): String = when (this) {
        Backend -> KEY_BACKEND
        HuggingFace -> KEY_HUGGING_FACE
    }

    companion object {
        /**
         * LiteRT-LM download URL — Gemma3-1B-IT INT4 in LiteRT format (~1.1 GB).
         * Requires LiteRT-LM SDK; best on devices with Snapdragon 8 Gen 2+ GPU.
         */
        const val HF_LITERTLM_MODEL_URL =
            "https://huggingface.co/litert-community/Gemma3-1B-IT/resolve/main/gemma3-1b-it-int4.litertlm"

        /**
         * Alternative URL — same model in MediaPipe `.task` format (~800 MB).
         * Broader device compatibility (any arm64 API 24+) via MediaPipe.
         * Pass via `MicroCoachingSDK.Builder.huggingFaceModelUrl()` to use this instead.
         */
        const val HF_TASK_MODEL_URL =
            "https://huggingface.co/litert-community/Gemma3-1B-IT/resolve/main/gemma3-1b-it-int4.task"

        /** Default MediaPipe download URL. */
        const val DEFAULT_HF_MODEL_URL = HF_TASK_MODEL_URL

        /** Filename for the `.task` (MediaPipe) model variant. */
        const val HF_TASK_MODEL_FILENAME = "gemma3-1b-it-int4.task"

        /** Filename for the `.litertlm` model variant. */
        const val HF_LITERTLM_MODEL_FILENAME = "gemma3-1b-it-int4.litertlm"

        /** Filename for the default model on device. */
        const val DEFAULT_HF_MODEL_FILENAME = HF_TASK_MODEL_FILENAME

        /** Default provider order — used when the host app does not configure [com.medtroniclabs.microcoaching.MicroCoachingConfig.modelProviders]. */
        val DEFAULT_ORDER: List<ModelProvider> = listOf(Backend, HuggingFace)

        // WorkManager serialisation keys (internal)
        internal const val KEY_BACKEND = "backend"
        internal const val KEY_HUGGING_FACE = "hugging_face"

        /** Deserialise from a WorkManager input data string. */
        internal fun fromKey(key: String): ModelProvider? = when (key) {
            KEY_BACKEND -> Backend
            KEY_HUGGING_FACE -> HuggingFace
            else -> null
        }
    }
}
