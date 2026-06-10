# SDK Technical Reference

Full technical documentation for the MicroCoaching Android SDK.

- Quick start and integration examples → [README.md](../README.md)
- Contribution conventions → [../CONTRIBUTING.md](../CONTRIBUTING.md)

---

## Module structure

### `sdk-android/` — the library

This is the only module that gets published as an `.aar`. It has no dependency on Hilt or any DI framework, so it can be imported into any Android app without forcing a particular architecture.

```
sdk-android/src/main/java/com/medtroniclabs/microcoaching/
│
├── MicroCoachingSDK.kt          ← singleton entry point + Builder
├── MicroCoachingConfig.kt       ← all client-configurable settings (immutable after build())
├── MicroCoachingInitializer.kt  ← AndroidX App Startup — zero-boilerplate auto-init
│
├── telemetry/
│   └── TelemetryManager.kt     ← OTel SDK init, span helpers, offline queue flush
│
├── services/
│   ├── LLMService.kt           ← interface + LLMConfiguration + LLMError sealed class
│   ├── GemmaService.kt         ← MediaPipe Gemma 3 (.task files)
│   ├── LiteRtLmService.kt      ← LiteRT-LM stub (.litertlm — not yet on public Maven)
│   └── InferenceRouter.kt      ← picks engine by model file extension at runtime
│
├── model/
│   ├── ModelManager.kt         ← download scheduling, SHA-256 verify, state machine
│   ├── ModelDownloadWorker.kt  ← WorkManager worker (resumable download)
│   └── ModelState.kt           ← Idle | Downloading | DownloadFailed | Ready | LoadFailed
│
├── chat/
│   ├── ChatSession.kt          ← session state + buildPrompt() (system + history + message)
│   ├── ChatUiState.kt          ← Loading | ModelNotReady | Ready | Error
│   ├── ChatViewModel.kt        ← orchestrates LLM + telemetry + repo; manual Factory
│   └── CoachingChatFragment.kt ← exportable Fragment (ComposeView inside AndroidX Fragment)
│
├── ui/
│   ├── screens/ChatScreen.kt   ← Compose root; handles all 4 ChatUiState branches
│   ├── components/             ← MessageBubble, StreamingBubble, ChatInputBar, loaders
│   └── theme/                  ← MicroCoachingTheme (light + dark)
│
├── data/
│   ├── db/
│   │   ├── MicroCoachingDatabase.kt     ← Room DB (separate from SPICE NCDMergerDatabase)
│   │   ├── entity/                      ← ChatMessageEntity, CoachingEventEntity, TelemetryQueueEntity
│   │   └── dao/                         ← ChatMessageDao, CoachingEventDao, TelemetryQueueDao
│   ├── model/
│   │   ├── ChatMessage.kt               ← domain model (id, role, text, sessionId, traceId)
│   │   └── PatientSnapshot.kt           ← anonymised patient context passed from SPICE
│   └── repository/
│       ├── ChatRepositoryImpl.kt
│       └── CoachingEventRepositoryImpl.kt
│
├── sdk/
│   ├── CoachingDataRepository.kt        ← PUBLIC pull interface for SPICE
│   └── MicroCoachingDataCallback.kt     ← PUBLIC push callback interface for SPICE
│
└── network/
    ├── CoachingApiService.kt            ← Retrofit — syncEvents, getScenarios, getModelMetadata
    └── NetworkModule.kt                 ← OkHttpClient with JWT Bearer + tenant headers
```

### `app/` — sample / demo app

Not shipped to users. Its only purpose is to show a working end-to-end integration of `sdk-android` and to serve as a manual test harness during development.

```
app/src/main/java/com/medtroniclabs/microcoaching/sample/
├── SampleApplication.kt   ← plain Application; calls MicroCoachingSDK.Builder(...).build()
└── MainActivity.kt         ← AppCompatActivity; embeds CoachingChatFragment
```

**What `app/` is not:**
- Not an installable product
- Not a separate SDK — it imports `:sdk-android` as a Gradle project dependency
- Has no Hilt, no separate Room DB, no networking of its own

---

## Feature reference

### OTel Telemetry

**Entry point:** `TelemetryManager` — initialised lazily via `MicroCoachingSDK.telemetry`

The SDK instruments itself with OpenTelemetry. All configuration comes from `MicroCoachingConfig` — the host app controls the endpoint, headers, sampling rate, and batch settings.

| Builder option | Default | Purpose |
|---|---|---|
| `otelEndpoint(url)` | `""` (disabled) | OTLP/HTTP collector URL |
| `otelHeaders(map)` | `emptyMap()` | Auth headers (SigNoz, Grafana, etc.) |
| `otelServiceName(name)` | `"micro-coaching-android"` | `service.name` attribute |
| `otelSamplingRate(rate)` | `1.0` | 0.0–1.0 head-based sampling |
| `otelBatchExportIntervalMs(ms)` | `5000` | How often to flush the batch |
| `otelMaxBatchSize(n)` | `512` | Max spans per batch |
| `enableOtelDebugLogging(bool)` | `false` | Also logs spans to Logcat |

**Span helpers available to internal SDK components:**

```kotlin
telemetry.startChatSession(sessionId)
telemetry.endChatSession(span, messageCount)
telemetry.startInferenceStream(sessionId)
telemetry.endInferenceStream(span, inputTokens, outputTokens, latencyMs)
telemetry.recordModelLoad(modelName, latencyMs)
telemetry.flushPendingSpans()   // called by onConnectivityRestored()
```

**Privacy:** No prompt text, response text, or patient data ever enters a span attribute. Hashed session IDs only.

**Offline behaviour:** When the OTLP export fails (no connectivity), spans are queued in `TelemetryQueueEntity` (Room). They are flushed when `MicroCoachingSDK.onConnectivityRestored()` is called.

---

### LLM Service

**Entry point:** `InferenceRouter` — used internally by `ChatViewModel`

The SDK supports two on-device engines selected automatically at runtime by model file extension:

| Engine | File type | Status | Device requirement |
|---|---|---|---|
| `GemmaService` (MediaPipe) | `.task` | Active | 3 GB RAM, arm64-v8a |
| `LiteRtLmService` | `.litertlm` | Stub — LiteRT SDK not yet on public Maven | 6 GB RAM, Snapdragon 8 Gen 2+ |

There is no online LLM fallback. This is intentional — patient context must never leave the device.

**Model resolution order:**
1. Explicit `modelPath` in config if set
2. First `.task` file found in `getExternalFilesDir(null)`
3. First `.litertlm` file found in `getExternalFilesDir(null)`

**Model download strategies:**

| Strategy | When download triggers |
|---|---|
| `ON_SDK_INIT` | Immediately after `Builder.build()` |
| `ON_FIRST_USE` | When the chat UI is first opened (default) |
| `PROVIDED` | Never — model file is pre-placed by the host app |
| `MANUAL` | Never — host app calls `ModelManager.triggerDownload()` explicitly |

---

### Model Download Providers

**Entry point:** `ModelDownloadWorker` (WorkManager) + `ModelManager`

When the SDK needs to download a model it tries each provider in order, moving to the next on failure. The default order is **Backend → HuggingFace**.

| Provider | Source | Auth | Status |
|---|---|---|---|
| `ModelProvider.Backend` | `{backendUrl}/api/v1/models/gemma/download` | SPICE JWT (`authToken`) | Placeholder — backend endpoint pending |
| `ModelProvider.HuggingFace` | `huggingFaceModelUrl` (default: Gemma3-1B-IT INT4) | HF token (`huggingFaceToken`) | Active — requires HF account + model access |

**Configuring providers in the Builder:**

```kotlin
// Default order — SDK tries Backend first, HuggingFace on failure
MicroCoachingSDK.Builder(this)
    .backendUrl(BuildConfig.COACHING_BACKEND_URL)
    .authToken(SecuredPreference.getToken())
    .huggingFaceToken(BuildConfig.HF_TOKEN)   // from local.properties in dev
    .build()

// Override order — skip backend, go straight to HuggingFace
MicroCoachingSDK.Builder(this)
    .modelProviders(listOf(ModelProvider.HuggingFace, ModelProvider.Backend))
    .huggingFaceToken(BuildConfig.HF_TOKEN)
    .build()

// Use a different HuggingFace model URL
MicroCoachingSDK.Builder(this)
    .huggingFaceModelUrl("https://huggingface.co/your-org/your-model/resolve/main/model.litertlm?download=true")
    .huggingFaceToken(BuildConfig.HF_TOKEN)
    .build()
```

**HuggingFace setup (for development):**

1. Create a token at https://huggingface.co/settings/tokens (type: Read)
2. Request model access at https://huggingface.co/litert-community/Gemma3-1B-IT
3. Add to `local.properties` (gitignored):
   ```
   HUGGING_FACE_TOKEN=hf_your_token_here
   ```
4. The sample app reads `BuildConfig.HF_TOKEN` from that entry automatically.
   For SPICE production, pass the token securely from your secrets manager.

**Download features:**
- Resumable via HTTP `Range` requests — survives network interruptions
- Progress reported via WorkManager (`ModelManager.state` updates in real time)
- Wi-Fi only by default (`wifiOnlyModelDownload = true`)
- Battery constraint applied — won't start when battery is low

---

### Chat Fragment

**Entry point:** `CoachingChatFragment`

A standard AndroidX `Fragment` that wraps a Compose UI via `ComposeView`. SPICE embeds it using the normal Fragment API — no Compose migration needed in SPICE.

```kotlin
CoachingChatFragment.newInstance(
    patientId = "pt_abc123",             // optional — used for session scoping
    systemContext = "Patient has T2DM"   // optional — prepended to LLM system prompt
)
```

**UI states handled internally:**

| State | What the user sees |
|---|---|
| `Loading` | Full-screen spinner |
| `ModelNotReady` | Download prompt + progress bar |
| `Ready` | Chat history + streaming bubble + input bar |
| `Error` | Error message + retry option |

**ViewModel:** `ChatViewModel` uses a manual `ViewModelProvider.Factory` — no Hilt annotation. Safe to use in any host app regardless of its DI setup.

---

### Room Database

**Entry point:** `MicroCoachingDatabase`

Completely separate from SPICE's `NCDMergerDatabase`. The SDK never reads from or writes to SPICE's Room instance, and SPICE never accesses SDK DAOs directly.

| Table | Purpose |
|---|---|
| `chat_messages` | Persisted chat history per session |
| `coaching_events` | Append-only coaching event log (synced to backend) |
| `telemetry_queue` | Offline OTel span queue (drained on connectivity restore) |

SPICE accesses SDK data exclusively via `CoachingDataRepository` (pull) or `MicroCoachingDataCallback` (push) — never via DAOs.

---

### Data access patterns

Two patterns are supported simultaneously. Register both if needed.

**Pull — `CoachingDataRepository`:**
```kotlin
// Available via MicroCoachingSDK.getInstance().dataRepository
interface CoachingDataRepository {
    suspend fun getChatHistory(sessionId: String): List<ChatMessage>
    suspend fun getAllSessionIds(): List<String>
    suspend fun getPendingCoachingEvents(): List<Map<String, Any>>
    suspend fun markEventsSynced(ids: List<Long>)
    suspend fun exportAllData(): SdkDataExport
}
```

**Push — `MicroCoachingDataCallback`:**
```kotlin
interface MicroCoachingDataCallback {
    fun onCoachingEventsReady(events: List<Map<String, Any>>) {}
    fun onSyncCompleted(syncedCount: Int) {}
    fun onModelDownloadProgress(percent: Int) {}
    fun onModelReady(modelPath: String) {}
    fun onModelDownloadFailed(reason: String) {}
}
```

---

### Networking

**Entry point:** `NetworkModule.createOkHttpClient(config)`

The SDK has its own OkHttpClient. It forwards the SPICE JWT automatically:

```
Authorization: Bearer <config.authToken>
X-Tenant-Id: <config.tenantId>
X-SDK-Version: <BuildConfig.SDK_VERSION>
```

The auth token is never stored by the SDK — it is read from `MicroCoachingConfig.authToken` on every request. SPICE must re-build or update the SDK config when its JWT refreshes.

---

## SPICE integration — step by step

### Step 1 — Add the dependency

Local development (both repos on the same machine):
```kotlin
// spice-android/settings.gradle.kts
includeBuild("../micro-coaching-android-sdk") {
    dependencySubstitution {
        substitute(module("com.medtroniclabs:micro-coaching-sdk"))
            .using(project(":sdk-android"))
    }
}
```

Production (from Maven):
```kotlin
implementation("com.medtroniclabs:micro-coaching-sdk:0.1.0")
```

### Step 2 — Initialise in Application

```kotlin
class SpiceApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        MicroCoachingSDK.Builder(this)
            .language(Language.BANGLA)
            .backendUrl(BuildConfig.COACHING_BACKEND_URL)
            .authToken(SecuredPreference.getToken())
            .otelEndpoint(BuildConfig.OTEL_ENDPOINT)
            .otelHeaders(mapOf("signoz-access-token" to BuildConfig.SIGNOZ_TOKEN))
            .enableTelemetry(BuildConfig.ENABLE_COACHING_TELEMETRY)
            .enableChat(true)
            .modelDownloadStrategy(ModelDownloadStrategy.ON_FIRST_USE)
            .build()
    }
}
```

### Step 3 — Expose the data repository via Hilt (optional)

```kotlin
// spice-android — AppModule.kt
@Module @InstallIn(SingletonComponent::class)
object CoachingModule {
    @Provides @Singleton
    fun provideCoachingDataRepository(): CoachingDataRepository =
        MicroCoachingSDK.getInstance().dataRepository
}
```

### Step 4 — Embed the chat Fragment

Add a container to the layout where you want the coaching chat to appear:
```xml
<!-- any_spice_layout.xml -->
<FrameLayout
    android:id="@+id/coaching_container"
    android:layout_width="match_parent"
    android:layout_height="match_parent" />
```

Then embed the Fragment:
```kotlin
supportFragmentManager.beginTransaction()
    .replace(R.id.coaching_container,
        CoachingChatFragment.newInstance(
            patientId = patient.patientTrackId,
            systemContext = buildCoachingContext(patient)
        )
    )
    .addToBackStack(null)
    .commit()
```

### Step 5 — Call lifecycle hooks

```kotlin
val sdk = MicroCoachingSDK.getInstance()

// In HomeFragment.onResume()
sdk.onHomeScreenShown(chwId = session.userId)

// When CHW taps a patient row
sdk.onPatientSelected(patientId = patient.patientTrackId)

// In AssessmentViewModel after successful submit
sdk.onAssessmentSubmitted(encounterId = encounter.id, patientId = patient.patientTrackId)

// In a ConnectivityManager callback
sdk.onConnectivityRestored()
```

### Step 6 — Handle JWT refresh

The SDK does not refresh tokens. When SPICE refreshes its JWT, rebuild the SDK:
```kotlin
// After token refresh in SPICE:
MicroCoachingSDK.Builder(this)
    .authToken(newToken)
    // ... same other options
    .build()   // replaces the singleton
```

---

## Maven publishing

### How it works

The SDK produces an `.aar` (Android Archive). Publishing it to a Maven repository lets any Android project add it as a standard Gradle dependency, exactly like any other library.

### Setting up maven-publish

Add the plugin and publishing config to `sdk-android/build.gradle.kts`:

```kotlin
plugins {
    // existing plugins...
    id("maven-publish")
}

// Version and group — single source of truth
val sdkVersion = "0.1.0"
val sdkGroup   = "com.medtroniclabs"
val sdkArtifact = "micro-coaching-sdk"

android {
    // existing config...
    publishing {
        singleVariant("release") {
            withSourcesJar()
            withJavadocJar()
        }
    }
}

publishing {
    publications {
        create<MavenPublication>("release") {
            groupId    = sdkGroup
            artifactId = sdkArtifact
            version    = sdkVersion

            afterEvaluate {
                from(components["release"])
            }

            pom {
                name.set("MicroCoaching Android SDK")
                description.set("On-device AI coaching for Community Health Workers")
                url.set("https://github.com/Medtronic-LABS/spice-coaching-android")
                licenses {
                    license {
                        name.set("Apache-2.0")
                        url.set("https://www.apache.org/licenses/LICENSE-2.0")
                    }
                }
            }
        }
    }

    repositories {
        // Local ~/.m2 — for testing before publishing remotely
        mavenLocal()

        // GitLab Package Registry (recommended for private SDK distribution)
        maven {
            name = "GitLabPackages"
            url  = uri("https://<GITLAB_HOST>/api/v4/projects/<PROJECT_ID>/packages/maven")
            credentials {
                username = providers.gradleProperty("gitlab.user").orNull
                    ?: System.getenv("GITLAB_USER")
                password = providers.gradleProperty("gitlab.token").orNull
                    ?: System.getenv("GITLAB_TOKEN")
            }
        }

        // Maven Central (for public open-source release — requires Sonatype account)
        // maven {
        //     name = "MavenCentral"
        //     url  = uri("https://s01.oss.sonatype.org/service/local/staging/deploy/maven2/")
        //     credentials {
        //         username = providers.gradleProperty("ossrhUsername").orNull
        //         password = providers.gradleProperty("ossrhPassword").orNull
        //     }
        // }
    }
}
```

### Version management

**All version values live in one place — `sdk-android/build.gradle.kts`:**

```kotlin
val sdkVersion = "0.1.0"   // ← change this for every release
```

This value is also injected into the app at compile time:
```kotlin
buildConfigField("String", "SDK_VERSION", "\"$sdkVersion\"")
```

So `BuildConfig.SDK_VERSION` is always in sync with the published artifact version.

**Versioning convention — semantic versioning (`MAJOR.MINOR.PATCH`):**

| Segment | When to increment |
|---|---|
| `MAJOR` | Breaking changes to the public API (Builder options removed, interfaces changed) |
| `MINOR` | New features added in a backward-compatible way |
| `PATCH` | Bug fixes only |

**Pre-release suffixes:**
```
0.1.0-alpha01   ← early feature preview
0.1.0-beta01    ← feature-complete, stabilisation only
0.1.0-rc01      ← release candidate
0.1.0           ← stable release
```

### Publishing commands

```bash
# Test locally — publishes to ~/.m2/repository/
./gradlew :sdk-android:publishToMavenLocal

# Consume the local build in another project:
# repositories { mavenLocal() }
# implementation("com.medtroniclabs:micro-coaching-sdk:0.1.0")

# Publish to GitHub Packages (configure repository in build.gradle.kts first)
GITHUB_ACTOR=your-username GITHUB_TOKEN=your-token \
  ./gradlew :sdk-android:publish
```

Store credentials in `~/.gradle/gradle.properties` (never commit this file):
```properties
github.actor=your-github-username
github.token=your-personal-access-token
```

### Consuming the published artefact

```kotlin
// host-app/build.gradle.kts
repositories {
    mavenCentral()
    // Or GitHub Packages, JitPack, etc. — wherever the SDK is published.
}

dependencies {
    implementation("com.medtroniclabs:micro-coaching-sdk:0.3.3-SNAPSHOT")
}
```

### Release checklist

Before publishing a new version:
- [ ] Update `sdkVersion` in `sdk-android/build.gradle.kts`
- [ ] Run `./gradlew :sdk-android:assembleRelease` — build must be clean
- [ ] Run `./gradlew :sdk-android:test` — all tests must pass
- [ ] Tag the release commit: `git tag v0.1.0 && git push origin v0.1.0`
- [ ] Run `./gradlew :sdk-android:publishToMavenLocal` and test in a consumer project
- [ ] Run `./gradlew :sdk-android:publish` to push to the remote repository
- [ ] Open an MR from `feature/*` → `dev`, merge, then `dev` → `main`
- [ ] Create a GitLab Release at the tag with changelog
