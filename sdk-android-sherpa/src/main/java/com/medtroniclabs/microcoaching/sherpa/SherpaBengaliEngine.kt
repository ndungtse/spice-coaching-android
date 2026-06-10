package com.medtroniclabs.microcoaching.sherpa

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.core.content.ContextCompat
import com.k2fsa.sherpa.onnx.EndpointConfig
import com.k2fsa.sherpa.onnx.EndpointRule
import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.OnlineModelConfig
import com.k2fsa.sherpa.onnx.OnlineRecognizer
import com.k2fsa.sherpa.onnx.OnlineRecognizerConfig
import com.k2fsa.sherpa.onnx.OnlineStream
import com.k2fsa.sherpa.onnx.OnlineTransducerModelConfig
import com.medtroniclabs.microcoaching.MicroCoachingSDK
import com.medtroniclabs.microcoaching.R
import com.medtroniclabs.microcoaching.ai.voice.OfflineSttEngine
import com.medtroniclabs.microcoaching.ai.voice.VoiceInputController
import com.medtroniclabs.microcoaching.ui.SdkLocaleHelper
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File
import java.util.concurrent.atomic.AtomicReference

/**
 * Offline Bengali STT via sherpa-onnx streaming Zipformer
 * (model: `sherpa-onnx-streaming-zipformer-bn-vosk-2026-02-09`).
 *
 * Lifecycle:
 *   1. [SttModelManager][com.medtroniclabs.microcoaching.ai.voice.stt.SttModelManager]
 *      has downloaded the four required files (`encoder.onnx`, `decoder.onnx`,
 *      `joiner.onnx`, `tokens.txt`) into [modelDir].
 *   2. [ChatVoiceInputController][com.medtroniclabs.microcoaching.ai.voice.ChatVoiceInputController]
 *      constructs us via the factory hook (`Builder.offlineSttEngineFactory`).
 *   3. On first [startListening] we lazily build the [OnlineRecognizer] —
 *      construction is expensive (loads ~90 MB of ONNX weights), so we cache
 *      it for subsequent sessions.
 *   4. Audio capture runs on [Dispatchers.IO]; callbacks marshal to main.
 *
 * The recognizer's built-in endpointing (rule1/rule2/rule3) drives the
 * `onResult` callback when sherpa detects the user has stopped speaking.
 */
class SherpaBengaliEngine(
    private val appContext: Context,
    override val modelDir: File,
) : OfflineSttEngine {

    private val mainHandler = Handler(Looper.getMainLooper())
    private val recognizerLock = Any()

    @Volatile
    private var recognizer: OnlineRecognizer? = null

    @Volatile
    private var captureScope: CoroutineScope? = null

    override fun isAvailable(): Boolean {
        if (!modelDir.isDirectory) return false
        return REQUIRED_FILES.all { File(modelDir, it).exists() }
    }

    override fun startListening(listener: VoiceInputController.TranscriptionListener) {
        if (!isAvailable()) {
            listener.onError(localized(R.string.chat_voice_error_engine))
            return
        }
        if (!hasRecordPermission()) {
            listener.onError(localized(R.string.chat_voice_permission_denied))
            return
        }
        // Cancel any in-flight session before starting a new one.
        captureScope?.cancel()
        captureScope = null

        val rec = runCatching { ensureRecognizer() }.getOrElse {
            Log.e(TAG, "OnlineRecognizer init failed", it)
            listener.onError(localized(R.string.chat_voice_error_engine))
            return
        }

        val record = runCatching { buildAudioRecord() }.getOrNull()
        if (record == null || record.state != AudioRecord.STATE_INITIALIZED) {
            record?.release()
            listener.onError(localized(R.string.chat_voice_error_engine))
            return
        }

        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        captureScope = scope
        scope.launch {
            val stream = rec.createStream()
            val readBuf = ShortArray(SAMPLE_CHUNK_SIZE)
            var lastPartial = ""
            var done = false
            try {
                record.startRecording()
                while (isActive && !done) {
                    val read = record.read(readBuf, 0, readBuf.size)
                    if (read <= 0) continue
                    val samples = FloatArray(read) { i -> readBuf[i] / 32768f }
                    synchronized(recognizerLock) {
                        stream.acceptWaveform(samples, SAMPLE_RATE)
                        while (rec.isReady(stream)) rec.decode(stream)

                        val text = rec.getResult(stream).text.trim()
                        if (text != lastPartial && text.isNotBlank()) {
                            lastPartial = text
                            mainHandler.post { listener.onPartial(text) }
                        }

                        if (rec.isEndpoint(stream)) {
                            val finalText = rec.getResult(stream).text.trim()
                            rec.reset(stream)
                            if (finalText.isNotBlank()) {
                                done = true
                                mainHandler.post { listener.onResult(finalText) }
                            } else {
                                // Empty endpoint — keep listening (false trigger).
                                lastPartial = ""
                            }
                        }
                    }
                }
                // If the loop exited via cancel() without an endpoint, finalize
                // whatever partial we have so the caller's listener doesn't hang.
                if (!done) {
                    val finalText = synchronized(recognizerLock) {
                        val t = rec.getResult(stream).text.trim()
                        rec.reset(stream)
                        t
                    }
                    mainHandler.post {
                        if (finalText.isNotBlank()) listener.onResult(finalText)
                        else listener.onResult("")
                    }
                }
            } catch (t: Throwable) {
                Log.e(TAG, "capture loop crashed", t)
                mainHandler.post { listener.onError(localized(R.string.chat_voice_error_generic)) }
            } finally {
                runCatching { record.stop() }
                runCatching { record.release() }
                runCatching { stream.release() }
            }
        }
    }

    override fun stopListening() {
        captureScope?.cancel()
        captureScope = null
    }

    /** Release the loaded recognizer. Called when the host tears down the SDK. */
    fun release() {
        captureScope?.cancel()
        captureScope = null
        synchronized(recognizerLock) {
            recognizer?.release()
            recognizer = null
        }
    }

    // ── Internal ──────────────────────────────────────────────────────────────

    private fun ensureRecognizer(): OnlineRecognizer {
        recognizer?.let { return it }
        return synchronized(recognizerLock) {
            recognizer ?: buildRecognizer().also { recognizer = it }
        }
    }

    private fun buildRecognizer(): OnlineRecognizer {
        val config = OnlineRecognizerConfig().apply {
            featConfig = FeatureConfig().apply {
                sampleRate = SAMPLE_RATE
                featureDim = 80
                dither = 0f
            }
            modelConfig = OnlineModelConfig().apply {
                transducer = OnlineTransducerModelConfig(
                    encoder = File(modelDir, "encoder.onnx").absolutePath,
                    decoder = File(modelDir, "decoder.onnx").absolutePath,
                    joiner = File(modelDir, "joiner.onnx").absolutePath,
                )
                tokens = File(modelDir, "tokens.txt").absolutePath
                numThreads = 2
                provider = "cpu"
                modelType = "zipformer2"
                debug = false
            }
            endpointConfig = EndpointConfig(
                rule1 = EndpointRule(false, 2.4f, 0f),
                rule2 = EndpointRule(true, 1.2f, 0f),
                rule3 = EndpointRule(false, 0f, 20f),
            )
            enableEndpoint = true
            decodingMethod = "greedy_search"
            maxActivePaths = 4
        }
        // Filesystem paths are absolute so the AssetManager argument is unused.
        return OnlineRecognizer(assetManager = null, config = config)
    }

    private fun buildAudioRecord(): AudioRecord {
        val minBufferSize = AudioRecord.getMinBufferSize(
            SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
        )
        val bufferSize = (minBufferSize.coerceAtLeast(SAMPLE_CHUNK_SIZE * 2)) * 4
        return AudioRecord(
            MediaRecorder.AudioSource.VOICE_RECOGNITION,
            SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
            bufferSize,
        )
    }

    private fun hasRecordPermission(): Boolean =
        ContextCompat.checkSelfPermission(appContext, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED

    private fun localized(resId: Int): String = runCatching {
        val language = MicroCoachingSDK.getInstance().language
        SdkLocaleHelper.wrap(appContext, language).getString(resId)
    }.getOrDefault(appContext.getString(resId))

    /** Holder for the last result token so we can surface it on partial polls. */
    private fun OnlineRecognizer.lastPartialOf(stream: OnlineStream): String =
        getResult(stream).text.trim()

    companion object {
        private const val TAG = "SherpaBengaliEngine"
        private const val SAMPLE_RATE = 16_000

        /** 100 ms at 16 kHz mono = 1600 samples. */
        private const val SAMPLE_CHUNK_SIZE = 1600

        private val REQUIRED_FILES = listOf(
            "encoder.onnx",
            "decoder.onnx",
            "joiner.onnx",
            "tokens.txt",
        )
    }
}
