# 00 — Quick Start: Full Host-App Integration

**Version:** 0.6.0-SNAPSHOT · **Date:** 2026-08-31 · **Status:** Current

Everything a host app needs to integrate the MicroCoaching SDK, in one file, in the order you'd do it. Steps 1–4 are **required**, steps 5–7 are recommended, and [§9](#9-what-not-to-wire) lists the things you must **not** wire (they moved inside the SDK or never had an effect).

Every snippet is genericized from the real `spice-2.0-android` integration (`SpiceBaseApplication`, `LandingActivity`, `HomeScreenFragment`). Placeholders like `https://<your-coaching-backend>/` stand in for real values — keep secrets in gitignored `environment.properties`, never in source.

A working integration is exactly four things:

1. the Maven dependency,
2. one `MicroCoachingSDK.Builder(context)…build()` call in `Application.onCreate()`,
3. one `onHomeScreenShown(chwId)` call when your home screen appears,
4. one embedded UI entry point (tile, FAB, or chat sheet).

Everything else is optional and degrades gracefully when absent.

---

## 0. Prerequisites

| Requirement | Value |
|---|---|
| JDK | 17+ |
| Android SDK Platform | API 36 (`compileSdk 36`, `minSdk 23`) |
| Device / emulator | `arm64-v8a` |
| SDK repo clone | this repo, to publish to Maven Local |

Publish the SDK artifacts to Maven Local from this repo:

```bash
./gradlew :sdk-android:publishToMavenLocal
# Optional — offline Bengali speech-to-text sidecar (~30 MB):
./gradlew :sdk-android-sherpa:publishToMavenLocal
```

---

## 1. Gradle wiring *(required)*

**`settings.gradle.kts`** — `mavenLocal()` first in the repositories:

```kotlin
dependencyResolutionManagement {
    repositories {
        mavenLocal()
        google()
        mavenCentral()
    }
}
```

**`app/build.gradle.kts`**:

```kotlin
dependencies {
    implementation("com.medtroniclabs.microcoaching:sdk-android:0.6.0-SNAPSHOT")
    // Optional: offline Bengali STT engine (bundles sherpa-onnx, ~30 MB)
    implementation("com.medtroniclabs.microcoaching:sdk-android-sherpa:0.4.0-SNAPSHOT")
}

android {
    buildFeatures { compose = true }   // the SDK exposes Compose entry points

    packaging {
        // The SDK bundles large native ML runtimes; legacy packaging keeps the APK ~2.5x smaller.
        jniLibs { useLegacyPackaging = true }
    }
}

// Only if your app's Kotlin is older than the SDK's (2.1.x):
kotlinOptions {
    jvmTarget = "17"
    freeCompilerArgs += "-Xskip-metadata-version-check"
}
```

You also need the Compose compiler plugin if your app doesn't have it yet: `id("org.jetbrains.kotlin.plugin.compose")`.

**`proguard-rules.pro`** — the SDK ships its own consumer keep rules automatically; the host only silences MediaPipe's transitive protobuf warnings:

```proguard
-dontwarn com.google.protobuf.GeneratedMessageLite$GeneratedExtension
-dontwarn com.google.protobuf.GeneratedMessageLite
-dontwarn com.google.protobuf.MessageLiteOrBuilder
-dontwarn com.google.protobuf.Parser
-dontwarn com.google.protobuf.InvalidProtocolBufferException
```

> **No AndroidManifest changes.** All permissions (`INTERNET`, `RECORD_AUDIO`, `POST_NOTIFICATIONS`, foreground-service types, …), the SDK's activities, its `FileProvider`, and the WorkManager service patch arrive via manifest merge.

---

## 2. Environment & BuildConfig *(recommended pattern)*

Keep the backend URL and tokens out of source. The SPICE pattern: a gitignored `environment.properties` at the repo root, surfaced as `BuildConfig` fields.

```properties
# environment.properties (gitignored)
COACHING_BACKEND_URL=https://<your-coaching-backend>/
HF_TOKEN=hf_your_token_here
ENABLE_COACHING_TELEMETRY=false
```

```kotlin
// app/build.gradle.kts
val envProperties = java.util.Properties().apply {
    rootProject.file("environment.properties").takeIf { it.exists() }?.inputStream()?.use { load(it) }
}
android {
    defaultConfig {
        buildConfigField("String", "COACHING_BACKEND_URL", "\"${envProperties["COACHING_BACKEND_URL"] ?: ""}\"")
        buildConfigField("String", "HF_TOKEN", "\"${envProperties["HF_TOKEN"] ?: ""}\"")
        buildConfigField("boolean", "ENABLE_COACHING_TELEMETRY", "${envProperties["ENABLE_COACHING_TELEMETRY"] ?: "false"}")
    }
}
```

`HF_TOKEN` is optional — the default on-device model is not gated. Set it only if you point `huggingFaceModelUrl` at a gated repo.

---

## 3. Initialize in `Application.onCreate()` *(required)*

Minimal viable init — every option has a default, but a blank `backendUrl` disables sync and the network monitor entirely, so realistically you always pass it:

```kotlin
MicroCoachingSDK.Builder(this)
    .backendUrl(BuildConfig.COACHING_BACKEND_URL)
    .authToken(getStoredTokenOrEmpty())   // usually empty before first login — that's fine
    .build()
```

The full recommended chain, mirroring `SpiceBaseApplication.initCoachingSdk()`. Build **after** your secure storage is ready (the token and language are read from it) and before any Activity:

```kotlin
class MyApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        initPreference()          // secure storage first — init reads the token from it
        initCoachingSdk()
    }

    private fun initCoachingSdk() {
        val authToken = getStoredTokenOrEmpty()
        MicroCoachingSDK.Builder(this)
            .language(resolveSdkLanguage())                    // Language.BANGLA / Language.ENGLISH
            .backendUrl(BuildConfig.COACHING_BACKEND_URL)
            .authToken(authToken)
            .persona(resolveCoachingPersona())                 // CoachingPersona.SK / .PO from the user's role
            .theme(MyBrandCoachingColors)                      // see step 3a
            .typography(coachingTypography(fontFamily = MyBrandFont))
            .enableVoice(true)
            .offlineSttEngineFactory(SherpaOnnxStt.factory)    // only with :sdk-android-sherpa
            .modelDownloadStrategy(ModelDownloadStrategy.ON_FIRST_USE)
            .huggingFaceToken(BuildConfig.HF_TOKEN)
            .enableTelemetry(BuildConfig.ENABLE_COACHING_TELEMETRY)
            .build()

        if (BuildConfig.DEBUG) {
            Log.i("Coaching", "health: ${MicroCoachingSDK.getInstance().checkHealth()}")
        }
    }
}
```

Three things to know about `build()`:

- **It replaces the singleton.** A second `build()` shuts down the previous instance and constructs a fresh one from *only* the setters in that chain — anything omitted reverts to its default (see the trap in step 4).
- **Blank `backendUrl` = offline/demo mode.** Sync, the network monitor, and telemetry flushes are all disabled.
- **Do not schedule sync yourself.** Periodic sync auto-schedules inside the SDK when `backendUrl` is set; calling `syncCoordinator.schedulePeriodic()` is redundant.

### 3a. Branding (one-time setup)

Define your palette once by copying the default and overriding brand tokens; the SDK derives chat bubbles, gradients, and badges from `primary` for free:

```kotlin
val MyBrandCoachingColors: CoachingColors = CoachingColors.Spice.copy(
    primary = Color(0xFF00695C),
    primaryContainer = Color(0xFFB2DFDB),
    onPrimaryContainer = Color(0xFF004D40),
    secondary = Color(0xFF00897B),
)
```

Full token reference, what's derived vs. fixed, and the two traps that make theming appear broken: [07 — Theming](./07-theming.md).

---

## 4. Refresh the auth token after login *(required with real auth)*

On a fresh install the SDK is built with an empty token. Once your login flow has a JWT:

**Option A — preferred: `updateAuthToken`.** One volatile write; every existing option (theme, persona, language, …) is untouched, and the auth interceptor picks it up from the next request:

```kotlin
// e.g. in your post-login landing screen
if (MicroCoachingSDK.isInitialized()) {
    MicroCoachingSDK.getInstance().updateAuthToken(jwt)
    // If the logged-in user changes what you passed at init:
    // MicroCoachingSDK.getInstance().setPersona(resolveCoachingPersona())
    // MicroCoachingSDK.getInstance().setLanguage(resolveSdkLanguage())
}
```

**Option B — full rebuild** (the pattern SPICE ships in `LandingActivity.reinitCoachingSdkWithToken()`): run the *entire* Builder chain from step 3 again with the new token.

> **⚠️ The "theme reverts after login" trap.** A rebuild replaces the whole config — every setter the rebuild chain omits silently resets to its default. Forget `.theme()` in the post-login chain and your brand colours vanish the moment the user logs in. If you rebuild, extract one `buildCoachingSdk(token: String)` helper and call it from both `Application.onCreate()` and the post-login site so the chains can never drift.

---

## 5. Identify the CHW *(required)*

Call this whenever your home screen appears:

```kotlin
// HomeScreenFragment.onViewCreated / your home Composable's LaunchedEffect
if (MicroCoachingSDK.isInitialized()) {
    MicroCoachingSDK.getInstance().onHomeScreenShown(chwId)   // your stable user id, as a String
}
```

> **This is the #1 "nothing happens" cause.** `onHomeScreenShown` is the **only** call that sets the current CHW id. Until it runs, every other hook, badge, morning card, and dashboard silently no-ops. Guard all SDK calls with `MicroCoachingSDK.isInitialized()` — some screens can be reached before the first `build()` completes.

---

## 6. Embed a UI entry point *(required — pick at least one)*

All SDK screens bring their own theme and locale; you never wrap them in your `MaterialTheme`.

**a) Coaching tile → full coaching flow** (what SPICE's home grid does):

```kotlin
// In a ComposeView cell / your Compose home screen. The tile is self-contained —
// it observes its own badge counts, no extra wiring.
CoachingGridTile(onClick = {
    CoachingFlowActivity.launchLearn(context, chwId)
})
```

If the tile lives in a `RecyclerView` measured before attach (e.g. `FlexboxLayoutManager` on tablets), `ComposeView` can't find a window recomposer during `onMeasure` — create one manually in the adapter and set it with `setParentCompositionContext(...)` before first measure. Working recipe: `DashboardMenuItemsAdapter` in [03 — UI Embedding](./03-ui-embedding.md#coachinggridtile-inside-a-recyclerview).

**b) Chat FAB + bottom sheet** (SPICE's home screen):

```kotlin
binding.chatFab.apply {   // a ComposeView in your XML layout
    setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
    setContent {
        MicroCoachingTheme {
            ChatFab(onClick = {
                if (MicroCoachingSDK.isInitialized()) {
                    CoachingChatBottomSheet.show(parentFragmentManager)
                }
            })
        }
    }
}
```

`CoachingChatBottomSheet.show(fm, patientId = "", systemContext = "")` also accepts a patient id and free-text context for patient-scoped chat. `CoachingChatFragment.newInstance(...)` exists for embedding chat as a plain fragment.

**c) Other launchers:** `CoachingFlowActivity.launch(context, chwId)` (full flow) and `launchLearnModule(context, chwId)` (learning only); `DraggableChatFab`, `CoachingFabView` (XML view with `setOnCoachingFabClickListener`).

> **First run:** the model-download consent screen lives **inside** the SDK's chat setup flow (default strategy `ON_FIRST_USE`). Do not build your own download prompt — see [05 — Model & Voice](./05-model-and-voice.md).

---

## 7. Recommended extras *(each optional — all no-op safely)*

**Notifications permission** — the model download shows a foreground-service notification; request `POST_NOTIFICATIONS` on API 33+ (if denied, the download still runs, just silently).

**Connectivity restored** — nudges an immediate telemetry flush + sync. The SDK also watches the network itself, so this is belt-and-braces:

```kotlin
override fun onResume() {
    super.onResume()
    if (MicroCoachingSDK.isInitialized() && connectivityManager.isNetworkAvailable()) {
        MicroCoachingSDK.getInstance().onConnectivityRestored()
    }
}
```

**Forward assessments and referrals** — the richer the data, the better the coaching. Call from a background dispatcher; snapshot mutable state before launching:

```kotlin
private fun notifyCoachingSdk(assessment: AssessmentEntity, asReferral: Boolean) {
    if (!MicroCoachingSDK.isInitialized()) return
    lifecycleScope.launch(Dispatchers.IO) {
        val sdk = MicroCoachingSDK.getInstance()
        if (asReferral) {
            sdk.onReferralSubmitted(
                encounterId = encounterIdOrBlank,
                patientId = assessment.patientId.orEmpty(),
                referralData = assessment.toComplianceState(),   // see below
            )
        } else {
            sdk.onAssessmentSubmitted(
                encounterId = encounterIdOrBlank,
                patientId = assessment.patientId.orEmpty(),
                assessmentData = assessment.toSdkAssessmentMap(),
            )
        }
    }
}
```

`onReferralSubmitted` is where referral-correctness coaching happens. Its `referralData` map carries two nested branches — what the rule engine **recommended** and what the CHW **actually** did:

```kotlin
fun AssessmentEntity.toComplianceState(): Map<String, Any> = buildMap {
    put("recommended", mapOf(
        "isReferred" to systemSaysRefer,              // Boolean
        "referredReason" to systemReferralReasons,    // List<String>
        // + your rule engine's structured output, nested as-is
    ))
    put("actual", mapOf(
        "didRefer" to chwDidRefer,                    // Boolean
        "destinationTier" to pickedFacilityType,      // String, from your facility picker
    ))
}
```

Missing paths are fail-safe: an absent `actual.*` branch means "can't tell", so no gap ever fires on incomplete data. Full key contract and the SPICE mapper: [04 — Hooks & Data](./04-hooks-and-data.md#forwarding-referral-data--onreferralsubmitted).

**Today's visits** — powers "what's due today" coaching. Push a PII-free projection of today's schedule when the home screen loads:

```kotlin
sdk.onTodaysVisitsUpdated(visitsDueToday.map {
    TodaysVisit(
        type = it.appointmentType,          // "HH_VISIT", "MEDICAL_REVIEW", …
        encounterType = it.encounterType,   // "ANC", "PNC_MOTHER", … or null
        dueDateIso = it.dueDate,
        isPregnant = it.isPregnant,         // null = unknown, constraint skipped
    )
})
```

**Logout** — clear the per-user dashboard cache: `MicroCoachingSDK.getInstance().clearDashboardCache()`.

---

## 8. Verify it works

```kotlin
val health = MicroCoachingSDK.getInstance().checkHealth()
// SdkHealthReport(isModelPresent, modelFileSizeBytes, modelStateName, morningCardCount)
```

```bash
adb logcat -s MicroCoachingSDK ModelManager
```

Expected first-run sequence: init logs → periodic sync scheduled (if `backendUrl` set) → `onHomeScreenShown` stores the CHW id and fetches morning cards → tile/FAB render → first chat open shows the model-download consent screen.

Manual smoke tests: `flushTelemetryNow()` (immediate span flush), `triggerFullInboundSync()` (full catalogue re-pull). Troubleshooting tables: [06 — Troubleshooting](./06-troubleshooting.md).

---

## 9. What NOT to wire

Things that were once host responsibilities (or look like they should be) and are now internal — skip them:

| Don't | Why |
|---|---|
| Wire, enable, or disable **gap detection/evaluation** | Fully internal and always-on. There is no Builder flag and no host API. `onAssessmentSubmitted` emits workflow signals only; referral-correctness gaps are evaluated inside `onReferralSubmitted` from the data you already forward in §7. |
| Set `enableChat` / `enableLearnModule` / `enableApplyModule` / `enableMeasureModule` | Reserved flags — **currently no effect** (no read sites in the SDK). `enableVoice` **is** functional. |
| Use `uiTheme(...)` | Deprecated, never read. Use `.theme()` / `.typography()` ([07](./07-theming.md)). |
| Edit `AndroidManifest.xml` | Everything merges from the SDK. |
| Call `syncCoordinator.schedulePeriodic()` | Auto-scheduled at init when `backendUrl` is set. |
| Build a model-download prompt | The consent + progress UI lives inside the SDK's chat setup screen. |
| Wrap SDK screens in your `MaterialTheme` | SDK screens own their Compose roots; theming is init-time via `.theme()` ([07](./07-theming.md)). |

---

## Where to go deeper

[01 — Setup](./01-setup.md) · [02 — Initialization](./02-initialization.md) · [03 — UI Embedding](./03-ui-embedding.md) · [04 — Hooks & Data](./04-hooks-and-data.md) · [05 — Model & Voice](./05-model-and-voice.md) · [06 — Troubleshooting](./06-troubleshooting.md) · [07 — Theming](./07-theming.md)
