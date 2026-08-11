package com.medtroniclabs.microcoaching.sherpa

import android.content.Context
import com.medtroniclabs.microcoaching.ai.voice.OfflineSttEngine
import java.io.File

/**
 * Public entrypoint for the sherpa-onnx Bengali offline STT engine.
 *
 * Wire it into the SDK at build time:
 * ```kotlin
 * MicroCoachingSDK.Builder(this)
 *     .enableVoice(true)
 *     .offlineSttEngineFactory(SherpaOnnxStt.factory)
 *     .build()
 * ```
 *
 * The factory is invoked lazily by
 * [ChatVoiceInputController][com.medtroniclabs.microcoaching.ai.voice.ChatVoiceInputController]
 * the first time the user dictates Bengali while offline AND the
 * [SttModelManager][com.medtroniclabs.microcoaching.ai.voice.stt.SttModelManager]
 * reports the model is on disk. Until then, no sherpa-onnx classes are touched
 * — the orchestrator routes Bengali through the platform `SpeechRecognizer`.
 */
object SherpaOnnxStt {

    /**
     * Factory consumed by `MicroCoachingSDK.Builder.offlineSttEngineFactory(...)`.
     * Receives the application [Context] and the model directory resolved by
     * [SttModelManager.bengaliModelDir][com.medtroniclabs.microcoaching.ai.voice.stt.SttModelManager.bengaliModelDir].
     */
    val factory: (Context, File) -> OfflineSttEngine = { context, modelDir ->
        SherpaBengaliEngine(context.applicationContext, modelDir)
    }
}
