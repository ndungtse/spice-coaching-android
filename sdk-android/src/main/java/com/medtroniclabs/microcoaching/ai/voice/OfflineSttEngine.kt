package com.medtroniclabs.microcoaching.ai.voice

import java.io.File

/**
 * Tag interface for a [VoiceInputController] backed by an on-device model
 * directory (rather than the platform `SpeechRecognizer`). The orchestrator
 * uses this to pick the right engine for offline Bengali.
 *
 * The concrete sherpa-onnx impl (`SherpaBengaliEngine`) lands in the next
 * change set — see `docs/v3/chat/sherpa.md` for the integration plan. Hosts
 * may also supply their own impl by passing a factory to
 * [com.medtroniclabs.microcoaching.MicroCoachingSDK.offlineSttEngineFactory].
 */
interface OfflineSttEngine : VoiceInputController {
    /** The directory containing the unpacked model files. */
    val modelDir: File
}
