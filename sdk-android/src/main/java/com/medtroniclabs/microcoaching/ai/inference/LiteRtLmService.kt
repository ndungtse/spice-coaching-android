package com.medtroniclabs.microcoaching.ai.inference

import android.content.Context
import android.util.Log
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.Content
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File

/**
 * LLM inference engine using LiteRT-LM for Gemma `.litertlm` models.
 *
 * Uses the Engine → Conversation pattern from the LiteRT-LM SDK:
 *   - [Engine] is initialized once per model load (~10s cold start)
 *   - Each inference creates a single-turn [Conversation] that is closed after the response
 *
 * Device requirements:
 *   - Recommended: 6+ GB RAM, Snapdragon 8 Gen 2+ (GPU backend)
 *   - Falls back to CPU backend if GPU is unavailable
 *
 * Thread safety: [mutex] ensures only one inference runs at a time.
 */
class LiteRtLmService(private val context: Context) : LLMService {

    private val _isModelLoaded = MutableStateFlow(false)
    override val isModelLoaded: StateFlow<Boolean> = _isModelLoaded.asStateFlow()

    private var engine: Engine? = null
    private val mutex = Mutex()

    override suspend fun loadModel(configuration: LLMConfiguration) {
        mutex.withLock {
            withContext(Dispatchers.IO) {
                val modelFile = File(configuration.modelPath)
                if (!modelFile.exists()) throw LLMError.ModelNotFound(configuration.modelPath)

                runCatching {
                    val backend = when (configuration.preferredBackend) {
                        InferenceBackend.GPU -> Backend.GPU()
                        InferenceBackend.CPU -> Backend.CPU()
                    }
                    val config = EngineConfig(
                        modelPath = configuration.modelPath,
                        backend = backend,
                        cacheDir = context.cacheDir.absolutePath,
                    )
                    engine?.close()
                    val newEngine = Engine(config)
                    newEngine.initialize()
                    engine = newEngine
                    _isModelLoaded.value = true
                    Log.i(TAG, "LiteRT-LM model loaded: ${modelFile.name}")
                }.onFailure { cause ->
                    _isModelLoaded.value = false
                    throw LLMError.ModelLoadFailed(cause.message ?: "unknown error", cause)
                }
            }
        }
    }

    override suspend fun generateResponse(prompt: String): Result<String> {
        val eng = engine ?: return Result.failure(LLMError.ModelNotLoaded)
        if (!mutex.tryLock()) return Result.failure(LLMError.InferenceBusy)
        return try {
            runCatching {
                withContext(Dispatchers.IO) {
                    eng.createConversation().use { conversation ->
                        conversation.sendMessage(prompt).contents.contents
                            .filterIsInstance<Content.Text>()
                            .joinToString("") { it.text }
                    }
                }
            }.mapFailure { cause ->
                LLMError.InferenceFailed(cause.message ?: "inference failed", cause)
            }
        } finally {
            mutex.unlock()
        }
    }

    /**
     * Streams the response token-by-token via [Engine.createConversation] +
     * [sendMessageAsync]. Each emitted token is extracted from [Content.Text].
     */
    override fun generateResponseStream(prompt: String): Flow<String> = flow {
        val eng = engine ?: throw LLMError.ModelNotLoaded
        if (!mutex.tryLock()) throw LLMError.InferenceBusy
        try {
            eng.createConversation().use { conversation ->
                conversation.sendMessageAsync(prompt).collect { message ->
                    val token = message.contents.contents
                        .filterIsInstance<Content.Text>()
                        .joinToString("") { it.text }
                    if (token.isNotEmpty()) emit(token)
                }
            }
        } finally {
            mutex.unlock()
        }
    }.flowOn(Dispatchers.IO)

    override fun unloadModel() {
        engine?.close()
        engine = null
        _isModelLoaded.value = false
        Log.i(TAG, "LiteRT-LM model unloaded")
    }

    companion object {
        private const val TAG = "LiteRtLmService"
        const val MODEL_EXTENSION = ".litertlm"
    }
}

private fun <T> Result<T>.mapFailure(transform: (Throwable) -> Throwable): Result<T> =
    fold(onSuccess = { Result.success(it) }, onFailure = { Result.failure(transform(it)) })
