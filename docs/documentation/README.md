# MicroCoaching Android SDK — SPICE Integration Guide

**Version:** 0.3.8-SNAPSHOT · **Date:** 2026-06-03 · **Status:** Draft

The MicroCoaching SDK embeds on-device AI coaching for Community Health Workers (CHWs) directly inside the SPICE clinical app — chat, micro-learning, morning coaching cards, and coaching telemetry, with an offline-first on-device LLM. This guide walks the SPICE Android team through integrating the SDK into an app that does **not** yet reference it.

Every topic is explained generically (works for any host app) and then shown with the **exact placement used in the real `spice-2.0-android` integration**, so you can see where each piece goes.

> **Note:** all examples use placeholders for secrets (`hf_your_token_here`, `https://<your-coaching-backend>/`). Real values live in gitignored `environment.properties` / `local.properties` — obtain them from the team. Never commit a real token.

---

## What this guide covers

| Doc | Topic |
|---|---|
| [01 — Build Setup & Dependency Wiring](./01-setup.md) | Publish to Maven Local, `mavenLocal()`, the two SDK dependencies, Compose/Kotlin plugins, `gradle.properties`, `BuildConfig` config, version matrix. |
| [02 — Initialization & Configuration](./02-initialization.md) | The `MicroCoachingSDK.Builder`, the full `MicroCoachingConfig` reference, init in `Application.onCreate()`, post-login rebuild, telemetry, language. |
| [03 — UI Embedding](./03-ui-embedding.md) | Chat fragment, chat bottom sheet, the full coaching flow activity, home-screen Compose components (FAB, cards), theming. |
| [04 — Workflow Hooks & Data](./04-hooks-and-data.md) | Lifecycle hooks (`onAssessmentSubmitted`, …), the assessment-data bridge, push/pull data interfaces, CHW context, gap detection. |
| [05 — Model Download & Voice](./05-model-and-voice.md) | On-device model lifecycle, download strategies & providers, `ModelManager`, the download-consent UI, low-end devices, STT/voice. |
| [06 — Troubleshooting & Verification](./06-troubleshooting.md) | Build/runtime errors, how to verify the integration, security checklist, FAQ. |

---

## Audience & prerequisites

For SPICE Android engineers. You should have:

| Requirement | Value |
|---|---|
| JDK | 17 or newer (Android Studio's bundled JDK works) |
| Android Studio | Ladybug or newer (AGP 8.13 compatible) |
| Android SDK Platform | API 36 |
| Test device / emulator | `arm64-v8a` |
| SDK repo clone | `micro-coaching-android-sdk` (to publish to Maven Local) |

---

## At a glance

Authoritative version facts (verified against source). These supersede any older numbers elsewhere in the repo.

| Item | Value |
|---|---|
| groupId | `com.medtroniclabs.microcoaching` |
| Required artifact | `sdk-android:0.3.8-SNAPSHOT` |
| Optional artifact (offline BN STT) | `sdk-android-sherpa:0.3.7-SNAPSHOT` |
| Consumption | Maven Local (`publishToMavenLocal` → `mavenLocal()`) |
| `minSdk` | 23 |
| `compileSdk` | 36 |
| ABI | `arm64-v8a` only |
| Kotlin | 2.1.20 |
| AGP | SDK 9.1.0 · SPICE app 8.13.0 |
| DI | none (the SDK is DI-free) |

---

## 5-minute quick start

```bash
# 1. Publish the SDK to Maven Local (from the micro-coaching-android-sdk repo)
./gradlew :sdk-android:publishToMavenLocal
```

```kotlin
// 2. settings.gradle.kts — mavenLocal() first
dependencyResolutionManagement {
    repositories { mavenLocal(); google(); mavenCentral() }
}

// 2. app/build.gradle.kts — add the dependency
implementation("com.medtroniclabs.microcoaching:sdk-android:0.3.8-SNAPSHOT")
```

```kotlin
// 3. Build the SDK in Application.onCreate()
MicroCoachingSDK.Builder(this)
    .language(Language.BANGLA)
    .backendUrl(BuildConfig.COACHING_BACKEND_URL)
    .authToken(getAuthTokenOrEmpty())
    .enableChat(true)
    .huggingFaceToken(BuildConfig.HF_TOKEN)
    .build()
```

```kotlin
// 4. Embed the chat anywhere
supportFragmentManager.beginTransaction()
    .replace(R.id.coaching_container, CoachingChatFragment.newInstance())
    .commit()
```

Full detail: [01 — Setup](./01-setup.md) → [02 — Initialization](./02-initialization.md) → [03 — UI Embedding](./03-ui-embedding.md).

---

## Architecture overview

```
┌─────────────────────────── SPICE app ───────────────────────────┐
│                                                                  │
│  Application.onCreate()        Activities / Fragments            │
│        │                              │                          │
│        │ Builder().build()            │ newInstance() / show()   │
│        ▼                              ▼   onAssessmentSubmitted() │
│  ┌──────────────────────  MicroCoachingSDK (singleton)  ───────┐ │
│  │  Builder + MicroCoachingConfig                              │ │
│  │  ├─ UI surfaces  (chat fragment / bottom sheet / flow / FAB)│ │
│  │  ├─ Workflow hooks (onHomeScreenShown, onAssessment…, …)    │ │
│  │  ├─ ModelManager  (download / state)                        │ │
│  │  ├─ SyncCoordinator (periodic sync)                         │ │
│  │  ├─ TelemetryManager (OTel spans)                           │ │
│  │  └─ microcoaching.db (Room — separate from SPICE's DB)      │ │
│  └────────────────────────────┬───────────────────────────────┘ │
└───────────────────────────────┼─────────────────────────────────┘
                                 │
        ┌────────────────────────┼─────────────────────────┐
        ▼                        ▼                          ▼
  HuggingFace               Coaching backend            OTLP endpoint
  (model download)          (sync / content)            (optional telemetry)

  Optional sidecar: :sdk-android-sherpa  → offline Bengali speech-to-text
```

---

## API quick reference

### Builder essentials

| Method | Purpose |
|---|---|
| `Builder(context)` / `.build()` | Create or rebuild the singleton. |
| `.language(Language)` | `BANGLA` (default) / `ENGLISH`. |
| `.backendUrl(String)` · `.authToken(String)` | Coaching backend + SPICE JWT. |
| `.enableChat / enableLearnModule / enableApplyModule / enableVoice(Boolean)` | Feature flags. |
| `.huggingFaceToken(String)` · `.modelDownloadStrategy(…)` · `.modelProviders(…)` | Model download. |
| `.offlineSttEngineFactory(SherpaOnnxStt.factory)` | Optional offline BN STT. |
| `.enableTelemetry(Boolean)` · `.otelEndpoint(String)` · `.otelHeaders(Map)` | OpenTelemetry. |
| `.dataCallback(MicroCoachingDataCallback)` | Push-pattern data. |

Full table: [02 — Builder methods](./02-initialization.md#builder-methods).

### Workflow hooks (on `MicroCoachingSDK.getInstance()`)

| Hook | Call when |
|---|---|
| `onHomeScreenShown(chwId)` | Home shown. |
| `onPatientSelected(patientId)` | Patient opened. |
| `onAssessmentSubmitted(encounterId, patientId, assessmentData)` | Assessment saved. |
| `onConnectivityRestored()` | Network restored. |
| `setLanguage(Language)` · `checkHealth()` · `shutdown()` | Runtime control. |

Full table: [04 — Workflow hooks](./04-hooks-and-data.md#workflow-hooks-overview).

### UI surfaces

| Surface | Entry point |
|---|---|
| `CoachingChatFragment` | `newInstance(patientId, systemContext)` |
| `CoachingChatBottomSheet` | `show(fragmentManager)` |
| `CoachingFlowActivity` | `launch()` / `launchLearn()` / `launchLearnModule()` |
| `ChatFab` · `MorningCard` · `LearnCard` | Compose components rendered in a `ComposeView` |

Full detail: [03 — UI Embedding](./03-ui-embedding.md).

---

## Related docs

- [docs/SDK.md](../SDK.md) — architecture, components, and Maven publishing internals.
- [docs/UseCases_v2.md](../UseCases_v2.md) — use cases (UC-1/2/3) and the CHW journey.
- [docs/gaps/GAP_DETECTION_SDK.md](../gaps/GAP_DETECTION_SDK.md) — gap-detection rule model.
- [references/chat.md](../references/chat.md) — how the chat works under the hood (pipeline, retrieval, guardrails, voice).
- [sdk-android-sherpa/README.md](../../sdk-android-sherpa/README.md) — offline STT module.

> **Note:** `docs/SDK_SPICE_SETUP.md` is an earlier setup note and is **superseded** by this guide (it predates the Maven-Local consumption model and the current API). Prefer the docs here.
