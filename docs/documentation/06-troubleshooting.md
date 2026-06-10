# 06 — Troubleshooting & Verification

**Version:** 0.3.8-SNAPSHOT · **Date:** 2026-06-03 · **Status:** Draft

Common integration failures, how to verify a working integration, a security checklist, and an FAQ.

---

## Build & dependency issues

| Symptom | Cause | Fix |
|---|---|---|
| `Could not resolve com.medtroniclabs.microcoaching:sdk-android:0.3.8-SNAPSHOT` | Artifacts not in Maven Local. | Run `./gradlew :sdk-android:publishToMavenLocal` in the SDK repo (and `:sdk-android-sherpa:publishToMavenLocal` if used). See [01 — Step 1](./01-setup.md#step-1--build--publish-the-sdk-to-maven-local). |
| Resolves `sdk-android` but not `sdk-android-sherpa` | Only one artifact published, or wrong version. | Versions differ on purpose: `sdk-android` is `0.3.8-SNAPSHOT`, `sdk-android-sherpa` is `0.3.7-SNAPSHOT`. Publish both. |
| `mavenLocal()` ignored / artifact still not found | `mavenLocal()` missing or after other repos. | Add `mavenLocal()` as the **first** entry under `dependencyResolutionManagement.repositories`. See [01 — Step 2](./01-setup.md#step-2--add-mavenlocal-to-the-repositories). |
| KAPT crash: `NullPointerException` on a synthetic node | Kotlin 2.1.x K2-based KAPT (KAPT4). | Add `kapt.use.k2=false` to `gradle.properties`. See [01 — Step 5](./01-setup.md#step-5--gradleproperties). |
| Manifest merger: `uses-sdk:minSdkVersion 23 cannot be smaller than … 24` (MediaPipe) | A future SDK bump, or your own override conflict. | The SDK already declares `tools:overrideLibrary="com.google.mediapipe.tasks.genai"`. If needed, mirror it in your app manifest (SPICE does, defensively). |
| R8/release: warnings about `com.google.protobuf.*` annotation types | MediaPipe references compile-only protobuf annotations. | Add the five `-dontwarn` lines from [05 — Foreground service & permissions](./05-model-and-voice.md#foreground-service--permissions-for-download). |
| Compose compile error / `kotlinCompilerExtensionVersion` warnings | Compose compiler not wired the Kotlin-2.x way. | Apply `org.jetbrains.kotlin.plugin.compose`; remove any manual `composeOptions { kotlinCompilerExtensionVersion = ... }`. See [01 — Step 4](./01-setup.md#step-4--plugins-kotlin--compose). |
| `Duplicate class` / `INSTALL_FAILED_NO_MATCHING_ABIS` | Non-arm64 device or split mismatch. | The SDK ships `arm64-v8a` only. Use an arm64 device/emulator. |

---

## Runtime issues

| Symptom | Cause | Fix |
|---|---|---|
| `IllegalStateException: MicroCoachingSDK is not initialized` | `getInstance()` called before `build()`, or before login on a fresh install. | Build in `Application.onCreate()`; guard every callsite with `if (!MicroCoachingSDK.isInitialized()) return`. See [02](./02-initialization.md#the-singleton-model). |
| Coaching API returns HTTP 401 | SDK built with an empty/expired `authToken` (pre-login). | Rebuild the SDK with the JWT after login. See [02 — Re-initializing after login](./02-initialization.md#re-initializing-after-login-jwt). |
| Model download never progresses (stuck at 0% / `-1`) | Missing/invalid HuggingFace token, or no network. | Verify `BuildConfig.HF_TOKEN` is set and has access to the gated repo; check `ModelManager.state` for `DownloadFailed(reason)`. See [05 — Providers](./05-model-and-voice.md#providers--the-huggingface-token). |
| Chat opens but answers without the LLM | Low-RAM device → retrieval-only mode (expected). | Confirm with `isLowEndDevice`. Use `.forceLowEndMode(false)` only on capable hardware. |
| Download foreground-service crash: `foregroundServiceType … not a subset` | `SystemForegroundService` not patched with `dataSync`. | The SDK merges this via its manifest. Don't override `SystemForegroundService` with `tools:node="replace"` in your app. |
| Drawer "CHW Assistant" never appears | SDK still has no token, or the menu item wasn't revealed. | Reveal the (`visible=false`) item after login, once the SDK is rebuilt with the JWT. See [03](./03-ui-embedding.md#embedding-the-chat-as-a-fragment). |
| Bengali voice input does nothing / opens settings | Offline Bengali pack not installed; platform recogniser is cloud-only for BN. | Add `:sdk-android-sherpa` + `offlineSttEngineFactory(SherpaOnnxStt.factory)`. See [05 — Voice](./05-model-and-voice.md#voice--stt). |

---

## Verifying the integration

1. **Build:** `./gradlew :app:assembleDevDebug` completes without resolve/merge errors.
2. **Health log** right after init:
   ```kotlin
   if (BuildConfig.DEBUG) {
       Timber.i("MicroCoachingSDK health: %s", MicroCoachingSDK.getInstance().checkHealth())
   }
   // SdkHealthReport(isModelPresent=false, modelFileSizeBytes=0, modelStateName=Idle, morningCardCount=…)
   ```
3. **Logcat** while exercising the feature:
   ```bash
   adb logcat -s MicroCoachingSDK ModelManager
   ```
   You should see `onAssessmentSubmitted — encounterId='' assessmentKeys=[…]` after submitting an assessment, and `ModelState` transitions during a download.
4. **Manual smoke test:** log in → "CHW Assistant" drawer item appears → tap it → chat opens (or download prompt if no model) → home screen shows the chat FAB and (when a module is due) the morning card.

---

## Security & secrets checklist

> **Note:** organization policy is **never include PII / secrets**. Apply this before every commit and before sharing logs.

- [ ] No real HuggingFace token in tracked files — use `hf_your_token_here` placeholders; real value lives in gitignored `environment.properties` / `local.properties`.
- [ ] No keystore passwords, DB encryption keys, salts, or test credentials in docs, code comments, or commits.
- [ ] No real backend hostnames in docs — use `https://<your-coaching-backend>/`.
- [ ] If a token was ever committed (current or historical), treat it as compromised — **rotate/revoke it** and scrub it from tracked files.
- [ ] Patient identifiers stay out of logs. The SDK hashes patient IDs (SHA-256) before any span/backend write and logs assessment **keys only**, not values — keep host-side logging to the same standard.
- [ ] Telemetry spans carry no prompt text, response text, or patient-identifiable data.

---

## FAQ

**Do I have to use Maven Local, or can I use a composite build?**
Maven Local is what SPICE uses and the supported default. A composite build (`includeBuild` + `dependencySubstitution`) also works and avoids re-publishing on every SDK change. See [01 — Alternative consumption models](./01-setup.md#alternative-consumption-models).

**Which model format does the SDK use — `.task` or `.litertlm`?**
Either; the file extension selects the engine (`.task` → MediaPipe, `.litertlm` → LiteRT-LM). You normally don't choose — the configured `huggingFaceModelUrl` / provider determines it.

**Do I need `sdk-android-sherpa`?**
Only for **offline** Bengali speech-to-text. English (on-device + cloud) and online Bengali work with the platform recogniser alone.

**Is OpenTelemetry required?**
No. It's off by default (`enableTelemetry = false`). Turn it on only when you have an OTLP endpoint.

**Does the SDK require Hilt or any DI framework?**
No — the SDK is DI-free by design. You may optionally expose `CoachingDataRepository` through your own Hilt graph. See [04 — Pull pattern](./04-hooks-and-data.md#receiving-data--pull-pattern).

**Why is the chat answering without the AI on some phones?**
Devices under ~3 GB RAM run retrieval-only mode by design (no model download). See [05 — Low-end devices](./05-model-and-voice.md#low-end-devices).

---

## Getting help / reference docs

- Architecture, components, and Maven publishing internals: [docs/SDK.md](../SDK.md)
- Gap detection model & tests: [docs/gaps/GAP_DETECTION_SDK.md](../gaps/GAP_DETECTION_SDK.md), [docs/gaps/GAPS_TEST.md](../gaps/GAPS_TEST.md)
- How the chat works under the hood: [references/chat.md](../references/chat.md)
- Use cases & CHW journey: [docs/UseCases_v2.md](../UseCases_v2.md)
- Offline STT module: [sdk-android-sherpa/README.md](../../sdk-android-sherpa/README.md)
