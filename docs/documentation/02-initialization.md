# 02 — Initialization & Configuration

**Version:** 0.6.0-SNAPSHOT · **Date:** 2026-08-31 · **Status:** Draft

How to construct, configure, and re-build the `MicroCoachingSDK` singleton, plus the full `MicroCoachingConfig` reference.

> **Note:** secrets in examples are placeholders. Pass real tokens from `BuildConfig` fields sourced from gitignored `environment.properties` (see [01 — Setup](./01-setup.md#step-6--configuration-values-buildconfig--environmentproperties)).

---

## The singleton model

The SDK is a process-wide singleton. You build it once with a fluent `Builder`, then call `MicroCoachingSDK.getInstance()` from anywhere.

```
Application.onCreate()
    │
    ├─ MicroCoachingSDK.Builder(context)…​.build()   // empty/early auth token
    │        └─ creates the singleton
    │
 (user logs in, SPICE issues a JWT)
    │
    └─ MicroCoachingSDK.getInstance().updateAuthToken(jwt)   // preferred: config untouched
       — or —
       MicroCoachingSDK.Builder(context)…​.authToken(jwt).build()   // full rebuild
             └─ shuts down the previous instance, replaces it
```

| Call | Returns | Notes |
|---|---|---|
| `MicroCoachingSDK.Builder(context)` | `Builder` | Start configuring. Pass an `Application`/`applicationContext`. |
| `.build()` | `MicroCoachingSDK` | Creates (or **replaces**) the singleton. Calling `build()` again shuts down the prior instance first — this is how SPICE rebuilds with a fresh token. |
| `MicroCoachingSDK.getInstance()` | `MicroCoachingSDK` | Throws if not initialised. |
| `MicroCoachingSDK.isInitialized()` | `Boolean` | Guard every access that may run before init. |

> **Note:** always guard cross-cutting calls with `if (!MicroCoachingSDK.isInitialized()) return`. SPICE does this in every coaching callsite, because some screens can be reached before `Application.onCreate()` finishes the first build (or before login on a fresh install).

---

## Minimal initialization

Every option has a sensible default — the only required argument is the context.

```kotlin
MicroCoachingSDK.Builder(this).build()
```

This gives you Bangla UI, chat, model downloaded on first use, telemetry off — but **no sync and no network monitor**, because `backendUrl` is blank. In practice you always supply at least a backend URL and an auth token.

---

## Full configuration reference

All fields live in `MicroCoachingConfig`, constructed only via the `Builder`. Defaults below are the data-class defaults.

### Identity & backend

| Field | Type | Default | Meaning |
|---|---|---|---|
| `language` | `Language` | `BANGLA` | Primary coaching language. |
| `tenantId` | `String` | `""` | Multi-tenant isolation id forwarded to the backend. |
| `backendUrl` | `String` | `""` | Base URL of the coaching backend. |
| `authToken` | `String` | `""` | Forwarded **verbatim** as the `Authorization` header — include the `Bearer ` prefix yourself if your backend expects one. Refresh at runtime with `updateAuthToken()`. |
| `connectionTimeoutSeconds` | `Int` | `30` | HTTP connect timeout. |
| `readTimeoutSeconds` | `Int` | `60` | HTTP read timeout. |

### Telemetry (OpenTelemetry)

| Field | Type | Default | Meaning |
|---|---|---|---|
| `enableTelemetry` | `Boolean` | `false` | Master switch for OTLP span export. |
| `otelEndpoint` | `String` | `""` | OTLP/HTTP endpoint (SigNoz, Tempo, Jaeger, …). |
| `otelServiceName` | `String` | `"micro-coaching-android"` | `service.name` on exported spans. |
| `otelHeaders` | `Map<String,String>` | `{}` | Headers per export (e.g. access token). |
| `otelSamplingRate` | `Double` | `1.0` | 0.0–1.0. Lower for high-volume prod. |
| `otelBatchExportIntervalMs` | `Long` | `5000` | Batch flush interval. |
| `otelMaxBatchSize` | `Int` | `512` | Max spans per batch. |
| `enableOtelDebugLogging` | `Boolean` | `false` | Also print spans to Logcat (debug only). |

### LLM / inference & model download

| Field | Type | Default | Meaning |
|---|---|---|---|
| `modelPath` | `String` | `""` | Absolute path to a pre-provisioned model (with `PROVIDED`). |
| `modelDownloadStrategy` | `ModelDownloadStrategy` | `ON_FIRST_USE` | When the model downloads. See [05](./05-model-and-voice.md). |
| `wifiOnlyModelDownload` | `Boolean` | `false` | Restrict download to unmetered networks. |
| `modelProviders` | `List<ModelProvider>` | `[Backend, HuggingFace, Kaggle]` | Ordered download fallback chain. |
| `huggingFaceToken` | `String` | `""` | Token for gated HF model repos. |
| `huggingFaceModelUrl` | `String` | default `.task` URL | Override model revision/format. |
| `maxInferenceTokens` | `Int` | `1536` | **Total** (input + output) token window per LLM turn, not an output cap. |
| `inferenceTemperature` | `Float` | `0.3` | Sampling temperature. |
| `chatScopeStrictness` | `ChatScopeStrictness` | `ExtendedClinical` | Out-of-scope refusal strictness. `Strict` refuses anything retrieval missed; `ExtendedClinical` (default) lets the LLM answer in-scope clinical questions. |
| `forceLowEndMode` | `Boolean?` | `null` | Override automatic low-RAM detection (auto-probes < 3 GB). |

### Feature flags

| Field | Type | Default | Meaning |
|---|---|---|---|
| `enableChat` | `Boolean` | `true` | **Reserved — currently no effect** (no read sites in the SDK). |
| `enableVoice` | `Boolean` | `false` | Voice input in chat — **functional**: gates the mic button. |
| `enableLearnModule` | `Boolean` | `false` | **Reserved — currently no effect.** |
| `enableApplyModule` | `Boolean` | `false` | **Reserved — currently no effect.** |
| `enableMeasureModule` | `Boolean` | `false` | **Reserved — currently no effect.** |
| `enableGapDetection` | `Boolean` | `true` | **Internal — not host-settable.** Gap detection is always on; referral-correctness rules run inside `onReferralSubmitted`. See [04](./04-hooks-and-data.md#gap-detection). |

> **Note:** the four reserved flags still have Builder setters for source compatibility, but setting them changes nothing today. Do not rely on them to turn features on or off, and do not promise their future semantics.

### Behaviour thresholds, UI, data, testing

| Field | Type | Default | Meaning |
|---|---|---|---|
| `quizPassThreshold` | `Int` | `70` | Quiz pass mark (percent). |
| `escalationFailureCount` | `Int` | `3` | Failed attempts that escalate to supervisor. |
| `escalationWindowDays` | `Int` | `30` | Window for `escalationFailureCount`. |
| `periodicRefreshDays` | `Int` | `90` | Days before a passed module re-surfaces. |
| `triggerOccurrenceThreshold` | `Int` | `2` | Gap occurrences before a trigger fires. |
| `triggerWindowDays` | `Int` | `14` | Window for `triggerOccurrenceThreshold`. |
| `themeColors` | `CoachingColors` | `CoachingColors.Spice` | Colour tokens for SDK screens. See [07](./07-theming.md). |
| `themeTypography` | `Typography` | `coachingTypography()` | Type scale for SDK screens. See [07](./07-theming.md). |
| `uiTheme` | `CoachingUiTheme` | `SYSTEM` | **Deprecated** — never read by the SDK. Use `themeColors`. |
| `dataCallback` | `MicroCoachingDataCallback?` | `null` | Push-pattern data callback. See [04](./04-hooks-and-data.md). |
| `forcedMode` | `CoachingMode?` | `null` | Force `ONLINE`/`EDGE`/`CACHED` (dev/test). |

> **Note:** the six behaviour thresholds and `enableGapDetection` are **not Builder-settable today** — the Builder does not expose setters that flow them into `build()`, so they always use the defaults above. Runtime values are overridden per-module by the backend's `config_threshold` sync resource. Treat the table as informational, not as knobs you set at init.

> **Note:** `uiTheme` is **deprecated** — it was never read by the SDK, and dark mode is unsupported because the bottom-sheet fragments and three manifest activities force light window themes. To restyle SDK screens use `themeColors` / `themeTypography` — see [07 — Theming](./07-theming.md).

> **Important:** `build()` replaces the **entire** config rather than merging, so any option a later `build()` omits silently reverts to its default. SPICE rebuilds the SDK after login (see [Refreshing the auth token](#refreshing-the-auth-token-after-login) below), so options like `.theme()` must be set at **every** `build()` site or they are discarded once the user logs in. Prefer `updateAuthToken()` to avoid the problem entirely.

---

## Builder methods

Every config field has a matching fluent setter (returns `this`). Plus these extras:

| Method | Purpose |
|---|---|
| `.persona(CoachingPersona)` | The user's coaching persona (`SK` / `PO` / `UNKNOWN`, default `SK`). Changeable at runtime with `setPersona()`. |
| `.forceMode(CoachingMode)` | Sets `forcedMode`. Dev/test override — see note below. |
| `.voiceInputController(VoiceInputController)` | Supply a custom voice-input controller. |
| `.offlineSttEngineFactory((Context, File) -> OfflineSttEngine)` | Wire the optional offline STT engine — pass `SherpaOnnxStt.factory` from `:sdk-android-sherpa`. See [05](./05-model-and-voice.md). |
| `.theme(CoachingColors)` | Brand colours for SDK screens. Sets `themeColors`. See [07](./07-theming.md). |
| `.typography(Typography)` | Type scale, e.g. `coachingTypography(fontFamily = MyBrandFont)`. Sets `themeTypography`. See [07](./07-theming.md). |

Setters available: `language`, `tenantId`, `persona`, `backendUrl`, `authToken`, `connectionTimeout`, `readTimeout`, `enableTelemetry`, `otelEndpoint`, `otelServiceName`, `otelHeaders`, `otelSamplingRate`, `otelBatchExportIntervalMs`, `otelMaxBatchSize`, `enableOtelDebugLogging`, `modelPath`, `modelDownloadStrategy`, `wifiOnlyModelDownload`, `modelProviders`, `huggingFaceToken`, `huggingFaceModelUrl`, `maxInferenceTokens`, `inferenceTemperature`, `chatScopeStrictness`, `forceLowEndMode`, `enableChat`, `enableVoice`, `enableLearnModule`, `enableApplyModule`, `enableMeasureModule`, `dataCallback`, plus the five above. (`uiTheme` still has a setter but is deprecated — see [07](./07-theming.md).)

---

## Initialization in `Application.onCreate()`

Build the SDK after your preferences/secure storage is ready, on the Application thread, so the singleton exists before any Activity (including the splash) touches it.

```kotlin
class MyApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        initPreference()          // your secure-storage init first
        initCoachingSdk()
    }

    private fun initCoachingSdk() {
        MicroCoachingSDK.Builder(this)
            .language(Language.BANGLA)
            .backendUrl(BuildConfig.COACHING_BACKEND_URL)
            .authToken(getAuthTokenOrEmpty())     // may be empty before login
            .persona(resolveCoachingPersona())
            .theme(MyBrandCoachingColors)         // see 07 — must be repeated at every build() site
            .enableTelemetry(BuildConfig.ENABLE_COACHING_TELEMETRY)
            .huggingFaceToken(BuildConfig.HF_TOKEN)
            .build()
    }
}
```

**SPICE reference** — `SpiceBaseApplication.initCoachingSdk()`. The real builder additionally:

- stays on `ModelDownloadStrategy.ON_FIRST_USE` and deliberately does **not** scan the model directory or pass `modelPath` — directory-listing order could hand the SDK a stale bundle, and `ON_FIRST_USE` already no-ops when the selected variant is present;
- enables voice and wires the offline STT factory: `.enableVoice(true).offlineSttEngineFactory(SherpaOnnxStt.factory)`;
- restricts providers to HuggingFace: `.modelProviders(listOf(ModelProvider.HuggingFace))`;
- passes the brand palette and persona: `.theme(UhisCoachingColors)` / `.persona(resolveCoachingPersona())`;
- pins `.forceMode(CoachingMode.ONLINE)` (see note);
- logs `checkHealth()` in debug builds.

It also maps SPICE's stored culture name to the SDK `Language` enum via a companion helper:

```kotlin
// SpiceBaseApplication.spiceLanguageToSdkLanguage(cultureName)
fun spiceLanguageToSdkLanguage(cultureName: String?): Language =
    if (cultureName?.contains(DefinedParams.BN_Locale, ignoreCase = true) == true) {
        Language.BANGLA
    } else {
        Language.ENGLISH
    }
```

---

## Refreshing the auth token after login

On a fresh install the SDK is built with an empty `authToken` (no JWT yet). Once the user logs in you have two options.

**Option A — preferred: `updateAuthToken()`.** One volatile write; the auth interceptor reads it per request, so it applies from the next call. Every other config value (theme, persona, language, …) is untouched:

```kotlin
if (MicroCoachingSDK.isInitialized()) {
    MicroCoachingSDK.getInstance().updateAuthToken(jwt)
    // If the logged-in user changes what init assumed:
    // MicroCoachingSDK.getInstance().setPersona(resolveCoachingPersona())
    // MicroCoachingSDK.getInstance().setLanguage(resolveSdkLanguage())
}
```

**Option B — full rebuild.** Run the entire Builder chain again with the new token:

```kotlin
private fun reinitCoachingSdkWithToken() {
    val token = getAuthTokenOrNull()
    if (token.isNullOrEmpty() || !MicroCoachingSDK.isInitialized()) return

    MicroCoachingSDK.Builder(applicationContext)
        .language(MyApplication.spiceLanguageToSdkLanguage(currentCultureName()))
        .backendUrl(BuildConfig.COACHING_BACKEND_URL)
        .authToken(token)                       // the freshly issued JWT
        .persona(resolveCoachingPersona())
        .theme(MyBrandCoachingColors)           // REQUIRED again — omissions reset to defaults
        .huggingFaceToken(BuildConfig.HF_TOKEN)
        .build()
    // No schedulePeriodic() needed — sync auto-schedules inside build() when backendUrl is set.
}
```

> **⚠️ Rebuild trap:** `build()` replaces the **whole** config. Any setter this chain omits — `.theme()`, `.typography()`, `.enableVoice()`, … — silently reverts to its default the moment the user logs in ("my brand colours disappeared after login"). If you rebuild, extract a single `buildCoachingSdk(token)` helper shared by `Application.onCreate()` and the post-login site so the two chains can never drift.

**SPICE reference** — `LandingActivity.reinitCoachingSdkWithToken()` currently uses Option B, repeating the full chain from `SpiceBaseApplication` (including `.theme(UhisCoachingColors)`) with the JWT and `.forceMode(CoachingMode.EDGE)`.

> **Note:** `forceMode` is a dev/test override. SPICE pins `ONLINE` at app start and `EDGE` after login; in production you usually leave `forcedMode` **unset** so the SDK selects `ONLINE`/`EDGE`/`CACHED` automatically based on connectivity and model readiness. Confirm the intended production behaviour with the coaching team before shipping a forced mode.

---

## Telemetry (OpenTelemetry) configuration

The SDK exports spans via OTLP/HTTP. Vendor-neutral — SigNoz, Grafana Tempo, Jaeger, Datadog, or any OTLP backend.

```kotlin
MicroCoachingSDK.Builder(this)
    .enableTelemetry(true)
    .otelEndpoint("https://<your-otel-collector>/v1/traces")
    .otelHeaders(mapOf("signoz-access-token" to "<your-token>"))
    .otelServiceName("micro-coaching-spice")
    .otelSamplingRate(1.0)
    .otelBatchExportIntervalMs(5_000)
    .otelMaxBatchSize(512)
    .enableOtelDebugLogging(BuildConfig.DEBUG)
    .build()
```

**SPICE reference** — SPICE keeps telemetry **off by default** (`enableTelemetry(BuildConfig.ENABLE_COACHING_TELEMETRY)`, where the flag defaults to `false`). Turn it on only once an OTLP endpoint is provisioned.

> **Note (privacy):** spans and metrics never contain prompt text, response text, or patient-identifiable data. Patient IDs are SHA-256 hashed before any span or backend write. Keep it that way when adding new instrumentation.

---

## Language switching at runtime

```kotlin
MicroCoachingSDK.getInstance().setLanguage(Language.ENGLISH)
```

| `Language` | BCP-47 | Notes |
|---|---|---|
| `BANGLA` | `bn-BD` | Default / primary. |
| `ENGLISH` | `en-US` | Fallback. |

`Language.fromBcp47("bn")` resolves a code back to the enum (defaults to `ENGLISH` on no match). SDK Compose screens pick up the new locale automatically.

---

## Health check & shutdown

```kotlin
val health = MicroCoachingSDK.getInstance().checkHealth()
// SdkHealthReport(isModelPresent, modelFileSizeBytes, modelStateName, morningCardCount)

MicroCoachingSDK.getInstance().shutdown()   // releases resources; rarely needed by the host
```

`SdkHealthReport` fields:

| Field | Type | Meaning |
|---|---|---|
| `isModelPresent` | `Boolean` | A model file is staged on disk. |
| `modelFileSizeBytes` | `Long` | Size of the staged model (0 if absent). |
| `modelStateName` | `String` | Current `ModelState` simple name (`Idle`, `Downloading`, `Ready`, …). |
| `morningCardCount` | `Int` | Cached morning modules ready to surface. |

---

## Next steps

- [03 — UI Embedding](./03-ui-embedding.md) — show the chat, FABs, and learning cards.
- [04 — Hooks & Data](./04-hooks-and-data.md) — feed SPICE workflow events into the SDK.
- [05 — Model & Voice](./05-model-and-voice.md) — model download strategies and STT.
