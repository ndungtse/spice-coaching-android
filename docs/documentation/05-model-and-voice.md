# 05 — Model Download & Voice

**Version:** 0.6.0-SNAPSHOT · **Date:** 2026-08-31 · **Status:** Draft

The on-device LLM (Gemma) powers the chat. It is downloaded at runtime, not bundled in the APK. This page covers download strategies, the `ModelManager` API, the download-consent UI, low-end-device behaviour, and voice/STT.

> **Note:** there is **no online LLM fallback** — inference is on-device Gemma only. On devices that cannot host the model, the chat degrades to retrieval-only (see [Low-end devices](#low-end-devices)).

---

## On-device model overview

| Aspect | Detail |
|---|---|
| Engine / format | `.task` → MediaPipe Gemma 3 1B · `.litertlm` → LiteRT-LM Gemma. The file extension selects the engine. |
| Location | `getExternalFilesDir(null)` on the device. |
| ABI | `arm64-v8a` only. |
| Size | Large — roughly **0.5–1.1 GB** depending on format/quantization. SPICE's user-facing prompt quotes "~600 MB". |
| Source | Downloaded from a provider chain (Backend → HuggingFace → Kaggle by default). |

> **Note:** exact model sizes vary by revision and quantization; treat the numbers above as approximate and confirm the active model with the coaching team.

---

## Download strategies

Set via `.modelDownloadStrategy(...)` on the Builder.

| Strategy | Behaviour | Use when |
|---|---|---|
| `ON_SDK_INIT` | Download as soon as the SDK initialises. | Onboarding flow where Wi-Fi is available upfront. |
| `ON_FIRST_USE` | Download lazily when the user first opens chat. | Default. Most field deployments. |
| `PROVIDED` | No download — the SDK reads `modelPath` directly. | Model pre-provisioned by MDM or a previous download. |
| `MANUAL` | Download only when you call `ModelManager.triggerDownload()`. | Host fully controls the consent/onboarding flow. |

**SPICE reference** — SPICE stays on the default:

```kotlin
// SpiceBaseApplication.initCoachingSdk() / LandingActivity.reinitCoachingSdkWithToken()
MicroCoachingSDK.Builder(this)
    .modelDownloadStrategy(ModelDownloadStrategy.ON_FIRST_USE)
    .modelProviders(listOf(ModelProvider.HuggingFace))
    .huggingFaceToken(BuildConfig.HF_TOKEN)
    .wifiOnlyModelDownload(false)            // consent (incl. metered data) handled in the SDK setup screen
    // …
    .build()
```

SPICE deliberately does **not** scan the model directory or pass `modelPath`/`PROVIDED`: directory-listing order could hand the SDK a stale bundle, and `ON_FIRST_USE` already no-ops when the selected model variant is present on disk.

---

## Providers & the HuggingFace token

`modelProviders` is an ordered fallback chain. The SDK tries each in turn until one succeeds.

| Provider | Notes |
|---|---|
| `Backend` | The coaching backend (if it serves the model). |
| `HuggingFace` | HuggingFace Hub. Gated repos require `huggingFaceToken`. |
| `Kaggle` | Reserved — not implemented. |

Default order is `[Backend, HuggingFace, Kaggle]`. SPICE narrows it to HuggingFace only.

```kotlin
.modelProviders(listOf(ModelProvider.HuggingFace))
.huggingFaceToken(BuildConfig.HF_TOKEN)          // placeholder: hf_your_token_here
.huggingFaceModelUrl("https://<model-host>/<model>.task")   // optional override
```

> **Note (security):** the HuggingFace token is **not** baked into the SDK `.aar` — host apps pass it at runtime. Source it from a `BuildConfig` field backed by gitignored `environment.properties`. Get a read-only token at `https://huggingface.co/settings/tokens` and request access to the gated model repo. Never commit the token.

---

## `ModelManager` API

Access via `MicroCoachingSDK.getInstance().modelManager`.

| Member | Purpose |
|---|---|
| `state: StateFlow<ModelState>` | Observe the live model lifecycle. |
| `isModelPresent(): Boolean` | Is a usable model staged on disk? |
| `findLocalModel(): File?` | The staged model file, or null. |
| `triggerDownload()` | Start a download (used with `MANUAL`, or to kick off after consent). |
| `scheduleDownloadIfNeeded()` | Queue a download honouring the configured constraints. |
| `pauseDownload()` / `resumeDownload()` / `cancelDownload()` | Control an in-flight download (partial file kept on pause). |

`ModelState` (sealed class):

| State | Fields | Meaning |
|---|---|---|
| `Idle` | — | No download started, no file present. |
| `Downloading` | `progressPercent`, `bytesDownloaded`, `totalBytes` | In progress. `progressPercent = -1` while preparing; `totalBytes = 0` means indeterminate. |
| `Paused` | `progressPercent` | User-paused; partial file kept for resume. |
| `DownloadFailed` | `reason` | Download failed. |
| `Ready` | `modelFile` | Present and verified — ready for inference. |
| `LoadFailed` | `reason` | Failed to load into the engine. |

```kotlin
lifecycleScope.launch {
    MicroCoachingSDK.getInstance().modelManager.state.collect { state ->
        when (state) {
            is ModelState.Downloading -> showProgress(state.progressPercent)
            is ModelState.Ready        -> enableChat()
            is ModelState.DownloadFailed -> showError(state.reason)
            else -> {}
        }
    }
}
```

> **Note:** the chat surfaces (`CoachingChatFragment` / `CoachingChatBottomSheet`) already render their own download/progress/error states internally. You only need to observe `ModelManager.state` if you want to drive your **own** UI (e.g. a banner outside the chat).

---

## Download consent — the SDK owns it

The download-consent experience lives **inside** the SDK: the chat's setup screen asks the user before anything downloads (the download is consent-gated, not just hardware-gated — see `localModelPrefs`), shows progress, and handles failure/retry. The host does not build a prompt, dialog, or progress banner.

```kotlin
// Host side — this is the whole flow:
CoachingChatBottomSheet.show(parentFragmentManager)
```

**SPICE reference** — earlier SPICE versions showed their own consent dialog (`showCoachingModelDownloadPrompt()`) before opening the chat. That flow was removed; `HomeScreenFragment.launchCoachingChatSheet()` is now a guard + `show()` and the SDK's setup screen owns the rest.

If you do want a custom surface *outside* the chat (e.g. an onboarding download step), use `.modelDownloadStrategy(ModelDownloadStrategy.MANUAL)` and drive `modelManager.triggerDownload()` / `state` yourself — but don't duplicate the consent inside the default `ON_FIRST_USE` flow.

Related host-readable state: `sdk.localModelEnabled` (device capable **and** user opted in) and `sdk.localModelPrefs.choice`.

---

## Low-end devices

Devices under ~3 GB RAM cannot host the Gemma model. The SDK detects this at construction and runs the chat in **retrieval-only mode** (BM25 lookup over the indexed corpus, pre-authored Bangla card bodies, no LLM round-trip).

```kotlin
if (MicroCoachingSDK.getInstance().isLowEndDevice) {
    // No model download needed — open chat directly.
}
```

Override detection with `.forceLowEndMode(true|false)` (QA only — forcing the LLM path on a low-RAM device risks an OOM kill).

---

## Foreground service & permissions for download

The model download runs as a WorkManager foreground service so the OS doesn't kill it when the app is backgrounded. The SDK declares everything it needs in its **own** manifest — these merge into your app automatically:

| Permission | Why |
|---|---|
| `INTERNET`, `ACCESS_NETWORK_STATE` | Backend calls + download. |
| `ACCESS_WIFI_STATE` | WorkManager network constraints. |
| `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_DATA_SYNC` | The download foreground service (API 34+ type). |
| `POST_NOTIFICATIONS` | Progress notification (API 33+). |
| `RECORD_AUDIO` | Chat voice input (runtime-requested when the user taps the mic). |

The SDK also merges a `dataSync` `foregroundServiceType` onto WorkManager's `SystemForegroundService` and adds a `<queries>` entry for `android.speech.RecognitionService`.

If you minify (R8) your release build, add the MediaPipe protobuf suppressions (these annotations exist only at compile time):

```proguard
# MediaPipe protobuf annotation types not present at build time (MicroCoaching SDK dependency)
-dontwarn com.google.protobuf.Internal$ProtoMethodMayReturnNull
-dontwarn com.google.protobuf.Internal$ProtoNonnullApi
-dontwarn com.google.protobuf.ProtoField
-dontwarn com.google.protobuf.ProtoPresenceBits
-dontwarn com.google.protobuf.ProtoPresenceCheckedField
```

**SPICE reference** — `Spice-SL/app/proguard-rules.pro` (the five `-dontwarn` lines). `POST_NOTIFICATIONS` is also declared directly in SPICE's app manifest since it requests it at launch.

---

## Voice / STT

Enable voice input/output in the chat with `.enableVoice(true)`.

| Capability | Engine |
|---|---|
| English (on-device + cloud) | Android platform `SpeechRecognizer`. |
| Bengali (online) | Android platform `SpeechRecognizer` (cloud). |
| Bengali (offline) | Optional `:sdk-android-sherpa` engine (sherpa-onnx). |

To add offline Bengali STT, include the sherpa artifact (see [01 — Setup](./01-setup.md)) and wire its factory:

```kotlin
import com.medtroniclabs.microcoaching.sherpa.SherpaOnnxStt

MicroCoachingSDK.Builder(this)
    .enableVoice(true)
    .offlineSttEngineFactory(SherpaOnnxStt.factory)
    .build()
```

**SPICE reference** — SPICE enables voice and wires the sherpa factory in both `initCoachingSdk()` and `reinitCoachingSdkWithToken()`. The chat requests `RECORD_AUDIO` at runtime when the user first taps the mic. For advanced control, supply your own `.voiceInputController(...)`. See [sdk-android-sherpa/README.md](../../sdk-android-sherpa/README.md) and [references/chat.md](../references/chat.md).

---

## Translation pack (EN ↔ BN)

When `language = BANGLA`, the SDK uses ML Kit on-device translation (a ~20 MB language pack downloaded on demand). Observe readiness via:

```kotlin
MicroCoachingSDK.getInstance().translationModelState   // StateFlow<TranslationModelState>
```

---

## Next steps

- [06 — Troubleshooting](./06-troubleshooting.md) — "download stuck at 0%", model errors, and the security checklist.
- [04 — Hooks & Data](./04-hooks-and-data.md) — `onModelDownloadProgress` / `onModelReady` callbacks.
