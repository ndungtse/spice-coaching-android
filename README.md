# MicroCoaching Android SDK

An Android library that embeds AI coaching directly inside the SPICE clinical app, delivering on-device guidance to Community Health Workers (CHWs) in Bangladesh — with full OpenTelemetry observability, an exportable chat UI, and offline-first data storage.

> **Version:** `0.3.3-SNAPSHOT` · **Min SDK:** 24 (Android 7.0) · **ABI:** `arm64-v8a` · **Language:** Kotlin

---

## What's in this repo

| Module | Role |
|---|---|
| `sdk-android/` | The library — produces the `.aar` consumed by SPICE or any host app |
| `app/` | Sample app that imports `sdk-android` and shows a working integration |

See [docs/SDK.md](docs/SDK.md) for a full breakdown of every feature and component.

---

## Using the SDK in your project

### 1. Add the dependency

**Option A — Local project dependency (for SPICE development)**

In SPICE's `settings.gradle.kts`:
```kotlin
includeBuild("../micro-coaching-android-sdk") {
    dependencySubstitution {
        substitute(module("com.medtroniclabs:micro-coaching-sdk")).using(project(":sdk-android"))
    }
}
```

Or as a direct project reference if both repos share a root:
```kotlin
// settings.gradle.kts
include(":sdk-android")
project(":sdk-android").projectDir = file("../micro-coaching-android-sdk/sdk-android")
```

Then in SPICE's `app/build.gradle.kts`:
```kotlin
implementation(project(":sdk-android"))
```

**Option B — Maven (when published)**

```kotlin
// build.gradle.kts
dependencies {
    implementation("com.medtroniclabs:micro-coaching-sdk:0.1.0")
}
```

See [Publishing to Maven](#publishing-to-maven) below.

---

### 2. Initialise in Application.onCreate()

```kotlin
class SpiceApplication : Application() {
    override fun onCreate() {
        super.onCreate()

        MicroCoachingSDK.Builder(this)
            .language(Language.BANGLA)
            .backendUrl(BuildConfig.COACHING_BACKEND_URL)
            .authToken(SecuredPreference.getToken())      // SPICE JWT
            .otelEndpoint(BuildConfig.OTEL_ENDPOINT)
            .otelHeaders(mapOf("signoz-access-token" to BuildConfig.SIGNOZ_TOKEN))
            .enableTelemetry(BuildConfig.ENABLE_COACHING_TELEMETRY)
            .enableChat(true)
            .modelPath(getExternalFilesDir(null)?.absolutePath + "/gemma3.task")
            .build()
    }
}
```

Every option has a sensible default — the only required call is `Builder(context).build()`.

---

### 3. Embed the chat UI

```kotlin
// In any SPICE Activity or Fragment:
supportFragmentManager.beginTransaction()
    .replace(R.id.coaching_container, CoachingChatFragment.newInstance(
        patientId = patient.patientTrackId,
        systemContext = "Patient has hypertension. Provide diet counselling."
    ))
    .commit()
```

`CoachingChatFragment` handles all states internally: model loading, streaming inference, error recovery, and offline graceful degradation.

---

### 4. Access SDK data from SPICE

**Pull pattern (query on demand):**
```kotlin
// Hilt AppModule
@Provides @Singleton
fun provideCoachingRepo(): CoachingDataRepository =
    MicroCoachingSDK.getInstance().dataRepository

// In any ViewModel
val history = coachingRepo.getChatHistory(sessionId)
val pending = coachingRepo.getPendingCoachingEvents()
```

**Push pattern (subscribe to events):**
```kotlin
MicroCoachingSDK.Builder(this)
    // ...
    .dataCallback(object : MicroCoachingDataCallback {
        override fun onCoachingEventsReady(events: List<Map<String, Any>>) {
            // Forward to SPICE backend as needed
        }
        override fun onModelReady(modelPath: String) {
            Log.d("SPICE", "Coaching model ready: $modelPath")
        }
    })
    .build()
```

---

### 5. Wire SPICE lifecycle hooks

```kotlin
val sdk = MicroCoachingSDK.getInstance()

// In SPICE home screen
sdk.onHomeScreenShown(chwId = session.userId)

// When CHW selects a patient
sdk.onPatientSelected(patientId = patient.patientTrackId)

// After assessment submission
sdk.onAssessmentSubmitted(encounterId = encounter.id, patientId = patient.patientTrackId)

// When connectivity is restored (triggers OTel flush)
sdk.onConnectivityRestored()
```

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

See [docs/SDK.md — Maven Publishing](docs/SDK.md#maven-publishing) for the full guide.

**Quick summary:**
1. Set the version in `sdk-android/build.gradle.kts` → `buildConfigField("String", "SDK_VERSION", "\"x.y.z\"")`
2. Also set `version = "x.y.z"` and `group = "com.medtroniclabs"` in the same file (once the `maven-publish` plugin is added)
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
| Android min SDK | 24 (Android 7.0 Nougat) |
| Target / Compile SDK | 35 |
| ABI | `arm64-v8a` only |
| Kotlin | 2.1.20 |
| AGP | 9.1.0 |
| Gemma model | `.task` file (Gemma 3 1B INT4, ~800 MB) placed in `getExternalFilesDir(null)` |

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
