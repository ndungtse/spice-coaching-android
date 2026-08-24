package com.medtroniclabs.microcoaching.ai.inference

import android.content.Context
import android.util.Log
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.Content
import com.google.ai.edge.litertlm.Conversation
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import com.google.ai.edge.litertlm.ExperimentalApi
import com.google.ai.edge.litertlm.Message
import com.google.ai.edge.litertlm.MessageCallback
import com.google.ai.edge.litertlm.SamplerConfig
import com.google.ai.edge.litertlm.ThinkingConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

/**
 * On-device LLM inference via LiteRT-LM, the SDK's only bundled engine. Loads any
 * `.litertlm` bundle from [com.medtroniclabs.microcoaching.ai.model.ModelCatalog].
 *
 * [Engine] is created once per [loadModel] — `initialize()` is slow enough to need
 * [Dispatchers.IO] — and each request opens a single-turn conversation and closes it,
 * which is what keeps turns independent of one another.
 *
 * **The engine renders the model's chat template**, so callers pass plain text: turn
 * markers written into a prompt would be templated a second time and arrive as literal
 * characters. The engine also decodes special tokens away, so no end-of-turn marker
 * appears in the output — a stream that ends is a turn that finished, and only the
 * caller's own stream cap can cut an answer short.
 *
 * Thread safety: a [Mutex] serialises inference; concurrent requests get
 * [LLMError.InferenceBusy].
 */
class LiteRtLmService(private val context: Context) : LLMService {

    private val _isModelLoaded = MutableStateFlow(false)
    override val isModelLoaded: StateFlow<Boolean> = _isModelLoaded.asStateFlow()

    private var engine: Engine? = null
    private var loadedConfig: LLMConfiguration? = null
    private val inferenceMutex = Mutex()

    /**
     * Whether a reasoning model may emit its `<think>…</think>` span. Off: for grounded
     * extraction it costs an order of magnitude more tokens for no gain in accuracy.
     *
     * A field rather than a constant so a probe build can flip it. The chat layer strips
     * any `<think>` span regardless, since a bundle's template may ignore this.
     */
    var enableThinking: Boolean = false

    override suspend fun loadModel(configuration: LLMConfiguration) {
        inferenceMutex.withLock {
            withContext(Dispatchers.IO) {
                val modelFile = File(configuration.modelPath)
                if (!modelFile.exists()) throw LLMError.ModelNotFound(configuration.modelPath)

                runCatching {
                    val backend = when (configuration.preferredBackend) {
                        InferenceBackend.GPU -> Backend.GPU()
                        InferenceBackend.CPU -> Backend.CPU()
                    }
                    val engineConfig = EngineConfig(
                        modelPath = configuration.modelPath,
                        backend = backend,
                        // Total context: input plus output, as
                        // MicroCoachingConfig.maxInferenceTokens means it.
                        maxNumTokens = configuration.maxTokens,
                        cacheDir = context.cacheDir.absolutePath,
                    )
                    engine?.close()
                    engine = null
                    val newEngine = Engine(engineConfig)
                    newEngine.initialize()
                    engine = newEngine
                    loadedConfig = configuration
                    _isModelLoaded.value = true
                    Log.i(TAG, "LiteRT-LM model loaded: ${modelFile.name} (${modelFile.length()} bytes)")
                }.onFailure { cause ->
                    _isModelLoaded.value = false
                    // The native cause is the only description of why the engine refused the
                    // file, and the UI shows a plain sentence instead, so log it or lose it.
                    Log.e(
                        TAG,
                        "LiteRT-LM load failed for ${modelFile.name} (${modelFile.length()} bytes): ${cause.message}",
                        cause,
                    )
                    throw LLMError.ModelLoadFailed(cause.message ?: "unknown error", cause)
                }
            }
        }
    }

    override suspend fun generateResponse(prompt: String): Result<String> {
        val eng = engine ?: return Result.failure(LLMError.ModelNotLoaded)
        if (!inferenceMutex.tryLock()) return Result.failure(LLMError.InferenceBusy)
        return try {
            runCatching {
                withContext(Dispatchers.IO) {
                    eng.createConversation(conversationConfig()).use { conversation ->
                        conversation.sendMessage(prompt).text()
                    }
                }
            }.mapFailure { cause ->
                LLMError.InferenceFailed(cause.message ?: "generation failed", cause)
            }
        } finally {
            inferenceMutex.unlock()
        }
    }

    /**
     * Streams the answer through the engine's **callback** API, bridged to a Flow here.
     *
     * Do not replace this with the library's own `sendMessageAsync(prompt): Flow<Message>`,
     * however much shorter it is. That bridge was compiled against a kotlinx-coroutines
     * whose `SendChannel.close$default` synthetic sits on the interface rather than in
     * `DefaultImpls`, so its `onDone` resolves nothing and throws `NoSuchMethodError` on a
     * native callback thread, past any `catch` here — the process goes with it. The
     * callback overloads touch no coroutines inside the library, which is what makes this
     * path immune; [LiteRtLmCoroutinesCompatibilityTest] fails when that stops being true.
     *
     * The lock and the conversation are each released exactly once, and cancellation stops
     * the native decode loop *before* the conversation is closed under it.
     */
    override fun generateResponseStream(prompt: String): Flow<String> = callbackFlow {
        val eng = engine ?: run { close(LLMError.ModelNotLoaded); return@callbackFlow }

        if (!inferenceMutex.tryLock()) {
            close(LLMError.InferenceBusy)
            return@callbackFlow
        }

        // The lock and the conversation are each released exactly once, whichever of
        // onDone / onError / collector-cancellation gets there first.
        val lockReleased = AtomicBoolean(false)
        val conversationClosed = AtomicBoolean(false)
        val generationFinished = AtomicBoolean(false)
        fun releaseLock() {
            if (lockReleased.compareAndSet(false, true)) inferenceMutex.unlock()
        }

        val conversation = runCatching { eng.createConversation(conversationConfig()) }
            .getOrElse { cause ->
                releaseLock()
                close(LLMError.InferenceFailed(cause.message ?: "conversation creation failed", cause))
                return@callbackFlow
            }

        Log.d(TAG, "templated prompt: ${templatedPrompt(conversation, prompt)}")

        var emissions = 0
        var chars = 0

        runCatching {
            conversation.sendMessageAsync(
                prompt,
                thinkingConfig = thinkingConfig(),
                callback = object : MessageCallback {
                    override fun onMessage(message: Message) {
                        val token = message.text()
                        if (token.isEmpty()) return
                        // Each callback carries a delta, which the caller appends. The first
                        // token and the running count are logged so that the opposite
                        // convention — each callback repeating the whole answer — shows up in
                        // the log instead of as duplicated text in the answer.
                        if (emissions == 0) Log.d(TAG, "first emission (${token.length} chars): $token")
                        emissions++
                        chars += token.length
                        trySend(token)
                    }

                    override fun onDone() {
                        Log.i(TAG, "stream ended: emissions=$emissions chars=$chars")
                        generationFinished.set(true)
                        releaseLock()
                        channel.close()
                    }

                    override fun onError(throwable: Throwable) {
                        Log.e(TAG, "generation failed after $emissions emissions: ${throwable.message}", throwable)
                        generationFinished.set(true)
                        releaseLock()
                        channel.close(
                            LLMError.InferenceFailed(throwable.message ?: "generation failed", throwable),
                        )
                    }
                },
            )
        }.onFailure { cause ->
            if (conversationClosed.compareAndSet(false, true)) runCatching { conversation.close() }
            releaseLock()
            close(LLMError.InferenceFailed(cause.message ?: "generation failed", cause))
            return@callbackFlow
        }

        awaitClose {
            // The collector can walk away mid-generation (the stream cap, the user closing
            // the sheet), so stop the native decode loop BEFORE closing the conversation:
            // closing one mid-decode is the ordering the engine does not promise. Cancel only
            // while it is still running — a finished generation must not be poked again.
            if (conversationClosed.compareAndSet(false, true)) {
                if (!generationFinished.get()) runCatching { conversation.cancelProcess() }
                runCatching { conversation.close() }
            }
            releaseLock()
        }
    }.flowOn(Dispatchers.IO)

    override fun unloadModel() {
        engine?.close()
        engine = null
        loadedConfig = null
        _isModelLoaded.value = false
        Log.i(TAG, "LiteRT-LM model unloaded")
    }

    /**
     * Per-request settings, rebuilt each time so they reflect the config in force now.
     *
     * No `systemInstruction`: the chat layer builds one self-contained
     * context/question/instruction prompt, and moving half of it into the system slot
     * would be a different prompt from the one the model was evaluated on.
     */
    private fun conversationConfig(): ConversationConfig {
        val config = loadedConfig
        return ConversationConfig(
            samplerConfig = SamplerConfig(
                topK = config?.topK ?: 40,
                topP = (config?.topP ?: 0.95f).toDouble(),
                temperature = (config?.temperature ?: 0.3f).toDouble(),
            ),
            thinkingConfig = thinkingConfig(),
        )
    }

    /**
     * Applied to the conversation *and* to every send, deliberately. An omitted per-message
     * value reaches JNI as a literal null with nothing substituted from the conversation, so
     * whether the conversation-level setting still governs is internal to the engine.
     * Passing it in both places settles it either way.
     */
    private fun thinkingConfig() = ThinkingConfig(enableThinking = enableThinking)

    /**
     * What the model actually receives once the chat template has wrapped [prompt], or null
     * when the engine will not say. Templating is the engine's job, so this is the only view
     * of the real input — and the only way to confirm no turn markers were applied twice.
     *
     * Uses an `@ExperimentalApi` method, which is acceptable while the sole caller is a
     * debug log: if it disappears, the fix is deleting a line.
     */
    @OptIn(ExperimentalApi::class)
    private fun templatedPrompt(conversation: Conversation, prompt: String): String? =
        runCatching { conversation.renderMessageIntoString(Message.user(prompt)) }.getOrNull()

    /** The text of a [Message], concatenating its text parts and ignoring any non-text content. */
    private fun Message.text(): String =
        contents.contents.filterIsInstance<Content.Text>().joinToString("") { it.text }

    companion object {
        private const val TAG = "LiteRtLmService"
        const val MODEL_EXTENSION = ".litertlm"
    }
}

// Extension to map Throwable inside Result
private fun <T> Result<T>.mapFailure(transform: (Throwable) -> Throwable): Result<T> =
    fold(onSuccess = { Result.success(it) }, onFailure = { Result.failure(transform(it)) })
