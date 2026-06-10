# sdk-android-sherpa

Optional module that adds **offline Bengali speech-to-text** to the
MicroCoaching SDK via [sherpa-onnx](https://github.com/k2-fsa/sherpa-onnx).

Including this module costs ~34 MB of native libraries in the host APK
(`libonnxruntime.so` alone is 25 MB). Hosts that only need English STT
or online Bengali don't need this — the platform `SpeechRecognizer` in
`:sdk-android` covers both for free.

---

## When does sherpa actually run?

`:sdk-android`'s `ChatVoiceInputController` routes mic taps to the platform
engine first. Sherpa only takes over when **all three** are true:

1. The CHW is dictating in Bengali (`Language.BANGLA`).
2. The device is offline (`MicroCoachingSDK.isNetworkAvailable() == false`).
3. The Bengali model files are on disk (managed by `SttModelManager`).

In every other case — English, online Bengali, or "offline + model not yet
downloaded" — the platform `SpeechRecognizer` handles transcription. Online
Bengali specifically works because Google's cloud recognizer supports `bn-BD`
even though no on-device pack exists on stock Android.

The badge above the chat input tells the user which engine ran:
**On-device** (green), **Server** (orange), or **Offline voice model** (blue).

---

## Setup

### 1. Add the dependency to your host app

```kotlin
// app/build.gradle.kts
dependencies {
    implementation("com.medtroniclabs.microcoaching:sdk-android:0.3.8-SNAPSHOT")
    implementation("com.medtroniclabs.microcoaching:sdk-android-sherpa:0.3.7-SNAPSHOT")
}
```

Both must be at the same version. The sherpa module already depends on
`:sdk-android` transitively, but list it explicitly so version pinning is
unambiguous.

### 2. Wire the factory in `MicroCoachingSDK.Builder`

```kotlin
import com.medtroniclabs.microcoaching.sherpa.SherpaOnnxStt

MicroCoachingSDK.Builder(this)
    .enableVoice(true)
    .offlineSttEngineFactory(SherpaOnnxStt.factory)   // ← this line
    // … rest of your config
    .build()
```

That's the whole integration. No further code changes. The SDK only touches
sherpa-onnx classes the first time the routing decision picks the offline
path — until then `OnlineRecognizer` is never loaded.

### 3. (Optional) Trigger the model download UI

The CHW will be prompted to download the Bengali voice model the first time
they tap the mic while offline. They can also opt in proactively from the
chat's "Download AI Model" surface, which now shows both models as cards:

```text
┌─────────────────────────────────────┐
│ ✨  AI Model            [Download]  │
│    Required · ~600 MB               │
├─────────────────────────────────────┤
│ 🎙️  Bengali Voice Model [Download]  │
│    Optional · ~90 MB                │
└─────────────────────────────────────┘
        [ Download both ]
```

The download itself is a foreground-service WorkManager job
(`SttModelDownloadWorker` in `:sdk-android`) — it survives backgrounding and
shows a system notification with MB/percent progress, the same UX as the
Gemma model download.

---

## Why is this a separate module?

Two reasons:

### A. AGP refuses local-`.aar` dependencies in Android library modules

sherpa-onnx Android isn't published to Maven Central or JitPack — it ships
as a `.aar` attached to the
[GitHub releases](https://github.com/k2-fsa/sherpa-onnx/releases). The
obvious approach — `implementation(files("libs/sherpa-onnx-1.13.2.aar"))`
from `:sdk-android` — fails at `bundleDebugAar` with:

> Direct local .aar file dependencies are not supported when building an
> AAR. The resulting AAR would be broken because the classes and Android
> resources from any local .aar file dependencies would not be packaged in
> the resulting AAR.

`compileOnly(files(*.aar))`, `flatDir` repositories, and a few other tricks
all hit the same restriction.

This module sidesteps it by **exploding the `.aar` at build time** rather
than depending on it as a unit. Two Gradle tasks in `build.gradle.kts`:

- **`downloadSherpaAar`** — pulls `sherpa-onnx-1.13.2.aar` from the GitHub
  release into `libs/`. Idempotent — skips when the file already exists.
- **`unpackSherpaAar`** — unzips the `.aar`:
  - `classes.jar` → `libs/sherpa-onnx-classes.jar` — consumed via
    `implementation(files(*.jar))`, which AGP allows (the restriction is
    specifically for local `.aar`, not local `.jar`).
  - `jni/arm64-v8a/*.so` → `src/main/jniLibs/arm64-v8a/` — packaged into the
    module's own AAR natively, like any other Android library's JNI libs.

After explosion there's no "local `.aar` dependency" anywhere — just a jar
plus native libs, both of which AGP handles cleanly. `bundleDebugAar`,
`assembleRelease`, and `publishToMavenLocal` all pass.

Both `unpackSherpaAar` and `downloadSherpaAar` are chained to `preBuild` via
`afterEvaluate`, so a fresh checkout just runs `./gradlew :sdk-android-sherpa:assembleDebug`
and the bytes are fetched and unpacked on the first build (~30 MB download,
one-time). The `.aar`, extracted `classes.jar`, and `src/main/jniLibs/`
contents are all `.gitignore`d — reproducible from the release URL.

### B. It's an optional dependency

The native libraries that ship inside sherpa-onnx are ~34 MB compressed.
Most SDK adopters (English-only, or English + online Bengali) don't need
them. Splitting offline Bengali into its own module means those adopters
skip the 34 MB entirely — they just don't list `:sdk-android-sherpa` in
their `dependencies` block.

Hosts that DO want offline Bengali pay the bytes only by opting in. The
`Builder.offlineSttEngineFactory(...)` hook in `:sdk-android` is set up so
no sherpa-onnx class is referenced from the core SDK at all — the
`SherpaBengaliEngine` lives entirely inside this module.

If sherpa-onnx ever lands on Maven Central, the explode trick becomes
unnecessary and we can simplify to a normal Maven dependency. The
optional-dependency split is still worth keeping for the bytes-on-disk
reason.

---

## What's inside

| File | Purpose |
|---|---|
| [`build.gradle.kts`](build.gradle.kts) | Defines `downloadSherpaAar` + `unpackSherpaAar` tasks, declares the exploded jar + jniLibs deps, publishes to Maven as `com.medtroniclabs.microcoaching:sdk-android-sherpa:<version>` |
| [`src/main/java/.../SherpaBengaliEngine.kt`](src/main/java/com/medtroniclabs/microcoaching/sherpa/SherpaBengaliEngine.kt) | `OfflineSttEngine` implementation: lazy `OnlineRecognizer` construction, `AudioRecord(VOICE_RECOGNITION, 16 kHz, mono, PCM-16)`, 100 ms sample chunks pumped into `OnlineStream`, partial polling, endpoint detection (three rules: 2.4 s silence, 1.2 s after speech, 20 s hard cap) |
| [`src/main/java/.../SherpaOnnxStt.kt`](src/main/java/com/medtroniclabs/microcoaching/sherpa/SherpaOnnxStt.kt) | Public `factory: (Context, File) -> OfflineSttEngine` consumed by `MicroCoachingSDK.Builder.offlineSttEngineFactory(...)` |
| [`consumer-rules.pro`](consumer-rules.pro), [`proguard-rules.pro`](proguard-rules.pro) | Keep `com.k2fsa.sherpa.onnx.**` and `com.medtroniclabs.microcoaching.sherpa.**` so the JNI bridge can locate Kotlin classes by name |

---

## The Bengali voice model

The default model is
[`sherpa-onnx-streaming-zipformer-bn-vosk-2026-02-09`](https://github.com/k2-fsa/sherpa-onnx/releases/tag/asr-models)
— a streaming Zipformer transducer trained by Alphacephei / k2-fsa.

| Property | Value |
|---|---|
| Compressed download | ~98 MB (`.tar.bz2`) |
| On-disk after extract | ~92 MB (`encoder.onnx` 87 MB + `decoder.onnx` 2 MB + `joiner.onnx` 1 MB + `tokens.txt`) |
| Real-time factor on Snapdragon 6-series | ~0.25 (≈ 4× faster than real-time) |
| First-token latency | ~200 ms |
| Peak RAM | ~150–250 MB (don't co-load with Gemma) |
| License | Apache 2.0 |
| Source URL | [`docs/v3/chat/sherpa.md`](../docs/v3/chat/sherpa.md) |

`SttModelManager` in `:sdk-android` owns the download lifecycle:
WorkManager + foreground service notification + HTTP `Range`-resume +
`.tar.bz2` extraction via Apache Commons Compress. Once the four expected
files (`encoder.onnx`, `decoder.onnx`, `joiner.onnx`, `tokens.txt`) are on
disk under `getExternalFilesDir(null)/stt/bn/`, sherpa-onnx is hot.

---

## Build commands

```bash
# One-time (or whenever you bump the version pin): fetch + unpack the .aar
./gradlew :sdk-android-sherpa:unpackSherpaAar

# Normal build (downloads + unpacks automatically on first run)
./gradlew :sdk-android-sherpa:assembleDebug

# Publish to mavenLocal for SPICE / other host consumers
./gradlew :sdk-android-sherpa:publishToMavenLocal
```

To pull a newer sherpa-onnx release: bump `sherpaOnnxVersion` in
[`build.gradle.kts`](build.gradle.kts), delete `libs/sherpa-onnx-*.aar`,
and rebuild. The `unpackSherpaAar` task detects the version change via
file timestamps and re-extracts.

---

## Permissions

`RECORD_AUDIO` is declared in `:sdk-android`'s manifest and auto-merges
into the host. The chat surface in `:sdk-android` requests it at the
first mic tap via `ActivityResultContracts.RequestPermission`. This module
inherits both — no extra manifest entries needed.

---

## Troubleshooting

**Build fails with "Direct local .aar file dependencies are not supported".**
You added `implementation(files(*.aar))` somewhere. The point of this
module is to avoid that — see the "Why is this a separate module" section
above.

**`OnlineRecognizer` constructor throws `UnsatisfiedLinkError`.**
The `arm64-v8a/*.so` files didn't make it into the AAR. Confirm
`sdk-android-sherpa/src/main/jniLibs/arm64-v8a/` is populated
(four `.so` files totalling ~34 MB). If empty, run
`./gradlew :sdk-android-sherpa:unpackSherpaAar` manually.

**Mic taps still route through the platform recognizer for offline
Bengali.** Confirm `Builder.offlineSttEngineFactory(SherpaOnnxStt.factory)`
is in your Builder chain AND `SttModelManager.state.value is Ready` for the
Bengali model. The orchestrator only switches when both are true.

**The badge stays on "Server" while offline.** That means Android's
recognizer reported success despite no network — the OEM ROM may have a
cached transcription path. The orchestrator only flips to sherpa when the
platform engine returns one of `ERROR_NETWORK`, `ERROR_NETWORK_TIMEOUT`,
`ERROR_SERVER`, or `ERROR_LANGUAGE_NOT_SUPPORTED`. Check the device's
network state and platform STT behaviour separately.
