# 01 — Build Setup & Dependency Wiring

**Version:** 0.3.8-SNAPSHOT · **Date:** 2026-06-03 · **Status:** Draft

How to add the MicroCoaching SDK to a SPICE 2.0 build that does not yet reference it. Every step is shown generically first, then with the exact change made in the real `spice-2.0-android` integration (the **SPICE reference**).

> **Note:** Code examples that need a token, key, or backend URL use placeholders (`hf_your_token_here`, `https://<your-coaching-backend>/`). Real values live in `environment.properties` / `local.properties`, which are gitignored — obtain them from the team. Never commit a real token.

---

## Overview

The SDK ships as **two Maven artifacts**, both consumed from your local Maven repository (`~/.m2`):

| Artifact | Required? | Purpose |
|---|---|---|
| `com.medtroniclabs.microcoaching:sdk-android:0.3.8-SNAPSHOT` | **Yes** | The SDK library (chat, learn, model, telemetry, data). |
| `com.medtroniclabs.microcoaching:sdk-android-sherpa:0.3.7-SNAPSHOT` | Optional | Offline Bengali speech-to-text (bundles sherpa-onnx, ~30 MB native libs). Omit if you only need English / online Bengali STT. |

> **Note:** the two artifacts are intentionally on different versions (`0.3.8` and `0.3.7`). `sdk-android-sherpa` depends on `sdk-android`, so the combination resolves correctly. Use the exact versions above.

SPICE consumes these from **Maven Local** — you build the SDK once with `publishToMavenLocal`, and Gradle resolves the artifacts from `~/.m2`. There is no remote (Artifactory / Maven Central) artifact today. See [Alternative consumption models](#alternative-consumption-models) for other options.

---

## Prerequisites

| Requirement | Value |
|---|---|
| JDK | 17 or newer (Android Studio's bundled JDK works) |
| Android Studio | Ladybug or newer (AGP 8.13 compatible) |
| Android SDK Platform | API 36 (`compileSdk`) |
| Device / emulator ABI | `arm64-v8a` (the SDK ships native libs for arm64 only) |
| Local clone of the SDK repo | `micro-coaching-android-sdk` — needed for `publishToMavenLocal` |

---

## Step 1 — Build & publish the SDK to Maven Local

From the **SDK repo** (`micro-coaching-android-sdk`), publish both artifacts:

```bash
./gradlew :sdk-android:publishToMavenLocal
./gradlew :sdk-android-sherpa:publishToMavenLocal   # only if you need offline Bengali STT
```

This writes the `.aar` + POM to `~/.m2/repository/com/medtroniclabs/microcoaching/`. Re-run after every SDK change you want SPICE to pick up.

> **Note:** the SDK's own model-download token is **not** baked into the `.aar`. If you build the SDK locally, copy `example.local.properties` to `local.properties` and set `HF_TOKEN` there (see [05 — Model & Voice](./05-model-and-voice.md)). Host apps pass their own token at runtime via the Builder — see Step 6.

---

## Step 2 — Add `mavenLocal()` to the repositories

The resolver must look in Maven Local **before** Google/Maven Central.

```kotlin
// settings.gradle.kts
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        mavenLocal()      // <-- resolves the SDK artifacts from ~/.m2
        google()
        mavenCentral()
    }
}
```

**SPICE reference** — `Spice-SL/settings.gradle.kts`: `mavenLocal()` is the first entry under `dependencyResolutionManagement.repositories`.

---

## Step 3 — Add the dependencies

```kotlin
// app/build.gradle.kts
dependencies {
    implementation("com.medtroniclabs.microcoaching:sdk-android:0.3.8-SNAPSHOT")

    // Optional — offline Bengali STT. Bundles sherpa-onnx (~30 MB).
    implementation("com.medtroniclabs.microcoaching:sdk-android-sherpa:0.3.7-SNAPSHOT")
}
```

**SPICE reference** — `Spice-SL/app/build.gradle.kts`, in the `dependencies { }` block (just after `implementation(project(":analytics"))`).

---

## Step 4 — Plugins, Kotlin & Compose

The SDK's UI surfaces are built with Jetpack Compose. With Kotlin 2.x the Compose compiler is supplied by the **`org.jetbrains.kotlin.plugin.compose`** plugin — there is **no** manual `composeOptions { kotlinCompilerExtensionVersion = ... }` block anymore.

```kotlin
// root build.gradle.kts — declare plugin versions
plugins {
    id("org.jetbrains.kotlin.android") version "2.1.20" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.1.20" apply false   // <-- add
    // ...
}
```

```kotlin
// app/build.gradle.kts — apply the plugins
plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")   // <-- add
    // ...
}

android {
    buildFeatures {
        compose = true
        buildConfig = true
    }
    // No composeOptions block — the kotlin.plugin.compose plugin provides the compiler.
}
```

**SPICE reference** — version declarations in `Spice-SL/build.gradle.kts` (Kotlin `2.1.20`, compose plugin `2.1.20`); applied in `Spice-SL/app/build.gradle.kts` with `compose = true` and the explanatory comment that no `kotlinCompilerExtensionVersion` is required.

> **Note:** SPICE already enables `compose = true` because the SDK's home-screen components (FAB, cards) are rendered through `ComposeView` slots. If your app has no Compose at all, this is the change that introduces it — it is additive and does not require migrating existing XML/View screens.

---

## Step 5 — `gradle.properties`

Kotlin 2.1.x's K2-based KAPT (`KAPT4`) can crash on synthetic nodes during annotation processing. Force the K1 KAPT backend:

```properties
# gradle.properties
kapt.use.k2=false
```

**SPICE reference** — `Spice-SL/gradle.properties` (the line is present with the explanatory comment).

> **Note:** this only matters if your app uses KAPT (SPICE does, for Hilt + Room). A pure-KSP app can skip it.

---

## Step 6 — Configuration values (`BuildConfig` + `environment.properties`)

The SDK needs three host-supplied values at init time: the coaching backend URL, the HuggingFace model-download token, and a telemetry on/off flag. SPICE surfaces them as `BuildConfig` fields sourced from a gitignored `environment.properties`.

```kotlin
// app/build.gradle.kts
android {
    defaultConfig {
        buildConfigField("String", "HF_TOKEN", "\"${envProperties["HF_TOKEN"] ?: ""}\"")
        buildConfigField(
            "boolean",
            "ENABLE_COACHING_TELEMETRY",
            "${envProperties["ENABLE_COACHING_TELEMETRY"] ?: "false"}",
        )
    }
    // COACHING_BACKEND_URL is set per build flavor (dev/qa/staging/production):
    // buildConfigField("String", "COACHING_BACKEND_URL",
    //     "\"${envProperties["UHIS_DEV_COACHING_BACKEND_URL"] ?: "http://10.0.2.2:8000/"}\"")
}
```

```properties
# environment.properties  (gitignored — placeholders shown)
HF_TOKEN=hf_your_token_here
ENABLE_COACHING_TELEMETRY=false
UHIS_DEV_COACHING_BACKEND_URL=https://<your-coaching-backend>/
UHIS_QA_COACHING_BACKEND_URL=https://<your-coaching-backend>/
```

**SPICE reference** — flavor-agnostic fields in `Spice-SL/app/build.gradle.kts` `defaultConfig`; `COACHING_BACKEND_URL` is added to each flavor's `debug`/`release` `buildConfigField` block (dev, qa, staging, production), with a `http://10.0.2.2:8000/` emulator-loopback fallback. Keys are read from `Spice-SL/environment.properties`.

> **Note (security):** `environment.properties` and `local.properties` hold real tokens, keystore passwords, and DB keys. They are gitignored — keep them that way. Documentation, commits, and logs must use placeholders only. See the [security checklist](./06-troubleshooting.md#security--secrets-checklist).

---

## Step 7 — Verify the wiring

Build a debug variant:

```bash
./gradlew :app:assembleDevDebug
```

A clean build means the artifacts resolved and the manifest merged. To confirm at runtime, log the SDK health snapshot right after you initialise it (see [02 — Initialization](./02-initialization.md)):

```kotlin
if (BuildConfig.DEBUG) {
    Timber.i("MicroCoachingSDK health: %s", MicroCoachingSDK.getInstance().checkHealth())
}
// -> SdkHealthReport(isModelPresent=false, modelFileSizeBytes=0, modelStateName=Idle, morningCardCount=0)
```

`isModelPresent=false` on a fresh install is expected — the model downloads on first use (see [05 — Model & Voice](./05-model-and-voice.md)).

---

## Version matrix

What the SDK is built with vs. what the SPICE app uses. The SDK ships a prebuilt `.aar`, so the AGP/Compose-BOM differences are fine — only `compileSdk`, `minSdk`, and Kotlin need to be compatible on the consuming side.

| Component | SDK (`sdk-android`) | SPICE app |
|---|---|---|
| Kotlin | 2.1.20 | 2.1.20 |
| AGP | 9.1.0 | 8.13.0 |
| `compileSdk` | 36 | 36 |
| `minSdk` | 23 | 23 |
| `targetSdk` | — (library) | 36 |
| JVM target | 11 | 1.8 (+ core-library desugaring) |
| Compose BOM | 2025.04.01 | 2024.10.01 |
| Room | 2.7.0 | 2.7.1 |
| WorkManager | 2.10.1 | 2.9.1 |
| Hilt | none (SDK is DI-free) | 2.56 |

> **Note:** the SDK declares `tools:overrideLibrary="com.google.mediapipe.tasks.genai"` in its own manifest because MediaPipe declares `minSdk 24` while the SDK ships at `minSdk 23`. MediaPipe calls are gated behind a runtime API check, so the override is safe. You do **not** need to add it yourself (SPICE lists it defensively only).

---

## Alternative consumption models

| Model | When to use | How |
|---|---|---|
| **Maven Local** (what SPICE uses) | Day-to-day development; SDK and app built on the same machine. | `publishToMavenLocal` + `mavenLocal()` + explicit version deps (Steps 1–3). |
| **Composite build** | You want SDK source changes to recompile without a manual publish. | `includeBuild("../micro-coaching-android-sdk")` with `dependencySubstitution` in `settings.gradle.kts`. |
| **Published Maven** (future) | CI / release builds pulling a versioned artifact from Artifactory. | Add the remote repo to `repositories {}`; depend on the published version. Not configured today. |

---

## Next steps

- [02 — Initialization](./02-initialization.md) — build the SDK in `Application.onCreate()` and configure it.
- [06 — Troubleshooting](./06-troubleshooting.md) — common build/resolve errors and fixes.
- Architecture & Maven publishing internals: [docs/SDK.md](../SDK.md).
