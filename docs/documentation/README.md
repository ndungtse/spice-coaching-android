# MicroCoaching Android SDK — SPICE Integration Guide

**Version:** 0.6.0-SNAPSHOT · **Date:** 2026-08-31 · **Status:** Draft

The MicroCoaching SDK embeds on-device AI coaching for Community Health Workers (CHWs) directly inside the SPICE clinical app — chat, micro-learning, morning coaching cards, and coaching telemetry, with an offline-first on-device LLM. This guide walks the SPICE Android team through integrating the SDK into an app that does **not** yet reference it.

Every topic is explained generically (works for any host app) and then shown with the **exact placement used in the real `spice-2.0-android` integration**, so you can see where each piece goes.

> **Note:** all examples use placeholders for secrets (`hf_your_token_here`, `https://<your-coaching-backend>/`). Real values live in gitignored `environment.properties` / `local.properties` — obtain them from the team. Never commit a real token.

---

## What this guide covers

| Doc | Topic |
|---|---|
| [00 — Quick Start](./00-quick-start.md) | **Start here.** The whole integration in one file: gradle → init → token refresh → CHW id → UI entry points → recommended extras → what NOT to wire. |
| [01 — Build Setup & Dependency Wiring](./01-setup.md) | Publish to Maven Local, `mavenLocal()`, the two SDK dependencies, Compose/Kotlin plugins, `gradle.properties`, `BuildConfig` config, version matrix. |
| [02 — Initialization & Configuration](./02-initialization.md) | The `MicroCoachingSDK.Builder`, the full `MicroCoachingConfig` reference, init in `Application.onCreate()`, post-login rebuild, telemetry, language. |
| [03 — UI Embedding](./03-ui-embedding.md) | Chat fragment, chat bottom sheet, the full coaching flow activity, home-screen Compose components (FAB, cards). |
| [04 — Workflow Hooks & Data](./04-hooks-and-data.md) | Lifecycle hooks (`onAssessmentSubmitted`, `onReferralSubmitted`, …), the assessment/referral data bridges, push/pull data interfaces, CHW context, today's visits. |
| [05 — Model Download & Voice](./05-model-and-voice.md) | On-device model lifecycle, download strategies & providers, `ModelManager`, the download-consent UI, low-end devices, STT/voice. |
| [06 — Troubleshooting & Verification](./06-troubleshooting.md) | Build/runtime errors, how to verify the integration, security checklist, FAQ. |
| [07 — Theming & Brand Customization](./07-theming.md) | Restyle SDK screens with your own colours and fonts: the 35 tokens, what is derived from `primary` for free, and the two traps that make theming appear not to work. |

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
| Required artifact | `sdk-android:0.6.0-SNAPSHOT` |
| Optional artifact (offline BN STT) | `sdk-android-sherpa:0.4.0-SNAPSHOT` |
| Consumption | Maven Local (`publishToMavenLocal` → `mavenLocal()`) |
| `minSdk` | 23 |
| `compileSdk` | 36 |
| ABI | `arm64-v8a` only |
| Kotlin | 2.1.20 |
| AGP | SDK 9.1.0 · SPICE app 8.13.0 |
| DI | none (the SDK is DI-free) |

---

## 5-minute quick start

The four required pieces — the full walkthrough (token refresh, theming, hooks, what NOT to wire) is **[00 — Quick Start](./00-quick-start.md)**.

```bash
# 1. Publish the SDK to Maven Local (from this repo)
./gradlew :sdk-android:publishToMavenLocal
```

```kotlin
// 2. settings.gradle.kts — mavenLocal() first
dependencyResolutionManagement {
    repositories { mavenLocal(); google(); mavenCentral() }
}

// 2. app/build.gradle.kts — add the dependency
implementation("com.medtroniclabs.microcoaching:sdk-android:0.6.0-SNAPSHOT")
```

```kotlin
// 3. Build the SDK in Application.onCreate()
MicroCoachingSDK.Builder(this)
    .language(Language.BANGLA)
    .backendUrl(BuildConfig.COACHING_BACKEND_URL)
    .authToken(getAuthTokenOrEmpty())
    .theme(MyBrandCoachingColors)   // CoachingColors.Spice.copy(primary = …) — see 07
    .huggingFaceToken(BuildConfig.HF_TOKEN)
    .build()

// After the user logs in:
MicroCoachingSDK.getInstance().updateAuthToken(jwt)
```

```kotlin
// 4. Identify the CHW (required — all hooks no-op without it), then embed a surface
MicroCoachingSDK.getInstance().onHomeScreenShown(chwId)
CoachingChatBottomSheet.show(supportFragmentManager)
```

Full detail: [00 — Quick Start](./00-quick-start.md) → [01 — Setup](./01-setup.md) → [02 — Initialization](./02-initialization.md) → [03 — UI Embedding](./03-ui-embedding.md).

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
| `Builder(context)` / `.build()` | Create or rebuild the singleton (a rebuild resets every omitted option — see [02](./02-initialization.md)). |
| `.language(Language)` | `BANGLA` (default) / `ENGLISH`. |
| `.backendUrl(String)` · `.authToken(String)` | Coaching backend + SPICE JWT. Blank `backendUrl` disables sync entirely. |
| `.persona(CoachingPersona)` | `SK` (default) / `PO`. |
| `.enableVoice(Boolean)` | Voice input in chat (the mic). |
| `.enableChat / enableLearnModule / enableApplyModule / enableMeasureModule(Boolean)` | **Reserved — currently no effect.** |
| `.huggingFaceToken(String)` · `.modelDownloadStrategy(…)` · `.modelProviders(…)` | Model download. |
| `.offlineSttEngineFactory(SherpaOnnxStt.factory)` | Optional offline BN STT. |
| `.enableTelemetry(Boolean)` · `.otelEndpoint(String)` · `.otelHeaders(Map)` | OpenTelemetry. |
| `.dataCallback(MicroCoachingDataCallback)` | Push-pattern data. |
| `.theme(CoachingColors)` · `.typography(Typography)` | Brand colours and type scale for SDK screens. See [07](./07-theming.md). |

Full table: [02 — Builder methods](./02-initialization.md#builder-methods).

### Workflow hooks & runtime control (on `MicroCoachingSDK.getInstance()`)

| Call | When |
|---|---|
| `onHomeScreenShown(chwId)` | Home shown. **Required** — every other hook no-ops until this runs. |
| `onTodaysVisitsUpdated(visits)` | Today's schedule loaded/changed (PII-free projection). |
| `onAssessmentSubmitted(encounterId, patientId, assessmentData)` | Assessment saved (workflow signals). |
| `onReferralSubmitted(encounterId, patientId, referralData)` | Referral committed (referral-correctness evaluation). |
| `onConnectivityRestored()` | Network restored. |
| `updateAuthToken(jwt)` | After login / token refresh — preferred over a rebuild. |
| `setLanguage(Language)` · `setPersona(…)` · `checkHealth()` · `shutdown()` | Runtime control. |
| `clearDashboardCache()` · `flushTelemetryNow()` · `triggerFullInboundSync()` | Logout cleanup / manual smoke tests. |

Full table: [04 — Workflow hooks](./04-hooks-and-data.md#workflow-hooks-overview).

### UI surfaces

| Surface | Entry point |
|---|---|
| `CoachingChatFragment` | `newInstance(patientId, systemContext)` |
| `CoachingChatBottomSheet` | `show(fragmentManager)` |
| `CoachingFlowActivity` | `launch()` / `launchLearn()` / `launchLearnModule()` |
| `CoachingGridTile` · `ChatFab` · `MorningCard` · `LearnCard` | Compose components rendered in a `ComposeView` |
| Theming | `Builder.theme(CoachingColors)` — see [07](./07-theming.md) |

Full detail: [03 — UI Embedding](./03-ui-embedding.md).

---

## Where this is documented for the platform audience

The `coaching-platform` gitbook carries a condensed, host-neutral version of this guide for platform
and backend readers. These files are the source of truth; the gitbook pages summarise them and link
back here. **When the host-facing API changes, update both sides.**

| Gitbook page | Mirrors |
|---|---|
| `getting-started/connect-the-android-sdk.md` | [00](./00-quick-start.md) · [01](./01-setup.md) · [02](./02-initialization.md) |
| `device-integration/spice-host-app.md` (Host app contract) | [04](./04-hooks-and-data.md), plus [00 §9](./00-quick-start.md#9-what-not-to-wire) |
| `device-integration/sdk-surfaces.md` | [03](./03-ui-embedding.md) · [05](./05-model-and-voice.md) · [07](./07-theming.md) |
| `device-integration/pii-boundary.md` | the `onAssessmentSubmitted` / `onReferralSubmitted` key contracts in [04](./04-hooks-and-data.md) |

## Related docs

- [docs/ARCHITECTURE.md](../ARCHITECTURE.md) — architecture, components, data model, workflows, and Maven publishing.
- Gap-detection rule model — the `GAP_DETECTION_SDK.md` this used to link to no longer exists.
  The rule schema and operators now live in the platform gitbook under
  `device-integration/detection-rules.md`.
- [references/chat.md](../references/chat.md) — how the chat works under the hood (pipeline, retrieval, guardrails, voice).
- [sdk-android-sherpa/README.md](../../sdk-android-sherpa/README.md) — offline STT module.
