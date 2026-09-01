# MicroCoaching Android SDK

An Android library that embeds AI coaching directly inside the SPICE clinical app, delivering on-device guidance to Community Health Workers (CHWs) in Bangladesh — with full OpenTelemetry observability, an exportable chat UI, and offline-first data storage.

> **Version:** `0.6.0-SNAPSHOT` · **Min SDK:** 23 · **ABI:** `arm64-v8a` · **Language:** Kotlin

---

## What's in this repo

| Module | Role |
|---|---|
| `sdk-android/` | The library — produces the `.aar` consumed by SPICE or any host app |
| `sdk-android-sherpa/` | Optional offline Bengali speech-to-text sidecar (bundles sherpa-onnx) |
| `app/` | Sample app that imports `sdk-android` and shows a working integration |

See [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md) for the full architecture — components, data model, workflows, and decisions.

---

## Using the SDK in your project

**Full integration guide: [docs/documentation/00-quick-start.md](docs/documentation/00-quick-start.md)** — everything a host app needs (gradle wiring, init, post-login token refresh, UI embedding, hooks, theming, and what NOT to wire) in one file. The gist:

```bash
# Publish to Maven Local from this repo
./gradlew :sdk-android:publishToMavenLocal
```

```kotlin
// settings.gradle.kts — mavenLocal() first; app/build.gradle.kts:
implementation("com.medtroniclabs.microcoaching:sdk-android:0.6.0-SNAPSHOT")
```

```kotlin
// Application.onCreate() — every option has a default; blank backendUrl disables sync
MicroCoachingSDK.Builder(this)
    .language(Language.BANGLA)
    .backendUrl(BuildConfig.COACHING_BACKEND_URL)
    .authToken(getStoredTokenOrEmpty())
    .theme(MyBrandCoachingColors)          // CoachingColors.Spice.copy(primary = …)
    .build()

// After login:
MicroCoachingSDK.getInstance().updateAuthToken(jwt)
```

```kotlin
// Identify the CHW (required — all other hooks no-op without it), then show a surface
MicroCoachingSDK.getInstance().onHomeScreenShown(chwId)
CoachingChatBottomSheet.show(supportFragmentManager)
```

Deeper topics: [initialization & config](docs/documentation/02-initialization.md) · [UI embedding](docs/documentation/03-ui-embedding.md) · [workflow hooks & data](docs/documentation/04-hooks-and-data.md) · [model & voice](docs/documentation/05-model-and-voice.md) · [theming](docs/documentation/07-theming.md) · [troubleshooting](docs/documentation/06-troubleshooting.md).

---

## Telemetry configuration

The SDK exports OpenTelemetry spans via OTLP/HTTP. Vendor-neutral — works with SigNoz, Grafana Tempo, Jaeger, or any OTLP-compatible backend.

```kotlin
MicroCoachingSDK.Builder(this)
    .enableTelemetry(true)
    .otelEndpoint("https://ingest.signoz.io/v1/traces")
    .otelHeaders(mapOf("signoz-access-token" to "your-token"))
    .otelServiceName("micro-coaching-spice")
    .otelSamplingRate(1.0)
    .otelBatchExportIntervalMs(5_000)
    .otelMaxBatchSize(512)
    .enableOtelDebugLogging(BuildConfig.DEBUG)
```

Privacy guarantee: no prompt text, no response text, and no patient-identifiable data appear in any span or metric.

---

## Publishing to Maven

See [docs/ARCHITECTURE.md — Build, Publishing & Consumption](docs/ARCHITECTURE.md#8-build-publishing--consumption) for the full guide.

**Quick summary:**
1. Set the version in `sdk-android/build.gradle.kts` → `buildConfigField("String", "SDK_VERSION", "\"x.y.z\"")`
2. Also set `version = "x.y.z"` and `group = "com.medtroniclabs.microcoaching"` in the same file's publishing block
3. Run `./gradlew :sdk-android:publishToMavenLocal` to test locally
4. Run `./gradlew :sdk-android:publish` to push to the configured remote repository

---

## Build commands

```bash
./gradlew :sdk-android:assembleDebug    # build the SDK .aar
./gradlew :app:assembleDebug            # build and run the sample app
./gradlew :sdk-android:test             # run unit tests
./gradlew :sdk-android:publishToMavenLocal  # publish to ~/.m2 for local testing
```

---

## Requirements

| Requirement | Value |
|---|---|
| Android min SDK | 23 |
| Compile SDK | 36 |
| ABI | `arm64-v8a` only |
| Kotlin | 2.1.20 |
| AGP | 9.1.0 |
| On-device model | Downloaded at runtime by the SDK (consent-gated, default strategy `ON_FIRST_USE`) — see [docs/documentation/05-model-and-voice.md](docs/documentation/05-model-and-voice.md) |

---

## Contributing

See [CONTRIBUTING.md](CONTRIBUTING.md) for setup, build, and PR conventions.

A few SDK-specific constraints worth knowing up front:
- No Hilt inside `sdk-android/` — the library has no DI framework so it can be embedded in host apps that bring their own.
- All PRs target the default branch.

## License

Licensed under the [Apache License 2.0](LICENSE).

## Security

See [SECURITY.md](SECURITY.md) for how to report vulnerabilities.
