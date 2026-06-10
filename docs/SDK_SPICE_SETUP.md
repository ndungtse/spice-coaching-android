# SPICE + MicroCoaching SDK — Developer Setup Guide

This guide walks through building the MicroCoaching SDK from source, publishing it to your local Maven cache, and then building the SPICE Android app that consumes it. It covers every URL and credential you need to change.

---

## Prerequisites

| Tool | Minimum version | Notes |
|---|---|---|
| JDK | 17 | `java -version` to check |
| Android Studio | Hedgehog (2023.1) or newer | Bundles Gradle; Android SDK must be installed |
| Android SDK | API 34+ | Install via Android Studio SDK Manager |
| Git | Any recent version | |

---

## Repository layout

You need two repos checked out **side-by-side** in the same parent folder:

```
Medtronics/
├── micro-coaching-android-sdk/   ← SDK source (produces the .aar)
└── spice-android/                ← SPICE host app (consumes the SDK via Maven Local)
```

Clone them:

```bash
git clone <micro-coaching-android-sdk-repo-url> micro-coaching-android-sdk
git clone <spice-android-repo-url> spice-android
```

---

## Part 1 — Build and publish the SDK

### 1. Configure SDK `local.properties`

```bash
cd micro-coaching-android-sdk
cp example.local.properties local.properties
```

Open `local.properties` and set:

```properties
# Path to your Android SDK — Android Studio sets this automatically on first open.
# On macOS the default is:
sdk.dir=/Users/<your-username>/Library/Android/sdk

# HuggingFace token — needed to download the on-device Gemma model.
# 1. Create a token at https://huggingface.co/settings/tokens (type: Read)
# 2. Request model access at https://huggingface.co/litert-community/Gemma3-1B-IT
# 3. Paste it here.
HF_TOKEN=hf_your_token_here
```

Set `HF_TOKEN=<your-huggingface-token>` in `local.properties`. Get a token at https://huggingface.co/settings/tokens.

### 2. Build and publish the SDK to Maven Local

Maven Local is a cache on your machine (`~/.m2/repository`). SPICE resolves the SDK from there — no Artifactory or remote registry needed.

```bash
cd micro-coaching-android-sdk
./gradlew :sdk-android:publishToMavenLocal
```

Expected output (last few lines):

```
> Task :sdk-android:publishReleasePublicationToMavenLocal
> Task :sdk-android:publishToMavenLocal
BUILD SUCCESSFUL
```

This publishes `com.medtroniclabs.microcoaching:sdk-android:0.1.0-SNAPSHOT` to `~/.m2`.

> **Every time you change SDK code**, re-run this command before rebuilding SPICE.

---

## Part 2 — Configure and build SPICE

### 3. Configure SPICE `local.properties`

```bash
cd spice-android
```

Create `local.properties` (it is gitignored):

```properties
# Android SDK path
sdk.dir=/Users/<your-username>/Library/Android/sdk

# HuggingFace token — same token as the SDK (used for model download inside SPICE)
HF_TOKEN=hf_your_token_here

# Coaching platform backend URL
# This is the micro-coaching backend (FastAPI/Django), NOT the SPICE server.
# For local development with the coaching-platform repo running on your machine:
#   COACHING_BACKEND_URL=http://10.0.2.2:8000       ← emulator → localhost:8000
#   COACHING_BACKEND_URL=http://192.168.x.x:8000    ← physical device → your machine's LAN IP
# Leave blank or omit to use the default (http://10.0.2.2:8000).
COACHING_BACKEND_URL=http://10.0.2.2:8000
```

### 4. Configure the SPICE server URL

The SPICE server URL (Medtronic's NCD backend) is set in [`app/build.gradle`](../spice-android/app/build.gradle) inside the `development` product flavor.

Open `spice-android/app/build.gradle` and find the `development` block (around line 83):

```groovy
development {
    dimension "version"
    applicationIdSuffix ".dev"
    ext {
        // ↓ SPICE server (Medtronic NCD backend)
        server = [debug: "<your-spice-server-url>",
                  release: "<your-spice-server-url>"]

        // admin panel URL — only needed for admin flows
        admin = [debug: "http://<your-lan-ip>:3000/", release: "http://<your-lan-ip>:3000/"]
        salt  = [debug: "spice_opensource", release: "spice_opensource"]
    }
    ...
}
```

**Two server URLs to potentially change:**

| Field | What it points to | Example value |
|---|---|---|
| `server` | SPICE NCD backend (Spring Boot, port 8762) | `http://10.0.2.2:8762` (emulator) or your deployment URL |
| `COACHING_BACKEND_URL` (in `local.properties`) | MicroCoaching coaching-platform backend (FastAPI, port 8000) | `http://10.0.2.2:8000` |

#### Set your SPICE server URL

Point `server` at a SPICE NCD backend you control. Use the SPICE site-user credentials provided by your deployment admin to log in.

For an emulator running against a SPICE backend on the same machine:

```groovy
server = [debug: "http://10.0.2.2:8762", release: "http://10.0.2.2:8762"]
```

`10.0.2.2` is the Android emulator's alias for your machine's `localhost`. For a physical device, use your machine's LAN IP (e.g. `192.168.x.x`).

#### Coaching platform backend (`COACHING_BACKEND_URL`)

This is separate from the SPICE server. It is the `coaching-platform` backend that handles RAG answers, scenario sync, and telemetry for the AI coaching features.

- **Local (`coaching-platform` repo running on your machine):** `http://10.0.2.2:8000` (emulator) or your LAN IP (physical device)
- **ngrok-exposed coaching platform:** set the ngrok URL here, e.g. `https://your-ngrok-id.ngrok-free.app`

If you have no coaching platform running, leave the default — the SDK will fall back to on-device Gemma for AI responses and the sync worker will retry silently.

---

### 5. Build the SPICE APK

```bash
cd spice-android
./gradlew :app:assembleDevelopmentDebug
```

The APK is written to:

```
app/build/outputs/apk/development/debug/SPICE_development_debug_<timestamp>.apk
```

### 6. Install on a device or emulator

**Via ADB (physical device or running emulator):**

```bash
adb install -r app/build/outputs/apk/development/debug/SPICE_development_debug_*.apk
```

**Via Android Studio:** Open the project, select the `developmentDebug` variant, and click Run.

---

## Part 3 — First launch checklist

1. Open SPICE and log in with the CHW credentials above (or your own account).
2. Navigate to the home screen. The coaching card and AI assistant button should appear.
3. On first launch, the app will prompt to download the Gemma model (~800 MB). Tap **Yes** to start.
4. You can use the AI chat immediately via the coaching platform backend while the model downloads in the background.
5. Once download completes, the on-device model is used automatically — no restart required.

**Logcat health check** (visible in Android Studio or `adb logcat`):

```
I/SpiceBaseApplication: SDK health: SdkHealthReport(isModelPresent=false, isInferenceReady=false, ...)
```

After model download completes:

```
I/ModelManager: Model ready at: /sdcard/Android/data/.../files/gemma3-1b-it-int4.litertlm
I/SpiceBaseApplication: SDK health: SdkHealthReport(isModelPresent=true, isInferenceReady=true, ...)
```

---

## Quick reference — what to change and where

| What to change | File | Field |
|---|---|---|
| SPICE NCD server URL | `spice-android/app/build.gradle` | `development.server` |
| Coaching platform backend URL | `spice-android/local.properties` | `COACHING_BACKEND_URL` |
| HuggingFace token (model download) | `spice-android/local.properties` | `HF_TOKEN` |
| HuggingFace token (SDK build) | `micro-coaching-android-sdk/local.properties` | `HF_TOKEN` |
| SDK published version | `micro-coaching-android-sdk/sdk-android/build.gradle.kts` | `version` (publishing block) |
| SDK version consumed by SPICE | `spice-android/app/build.gradle` | `implementation 'com.medtroniclabs.microcoaching:sdk-android:<version>'` |

---

## Troubleshooting

**`Could not resolve com.medtroniclabs.microcoaching:sdk-android:0.1.0-SNAPSHOT`**
→ Run `./gradlew :sdk-android:publishToMavenLocal` from the SDK repo first. SPICE pulls the SDK from `~/.m2`, not from a remote registry.

**Build fails with `Unresolved reference: MicroCoachingSDK`**
→ The SDK was not published, or the version in `spice-android/app/build.gradle` does not match the published version. Check `~/.m2/repository/com/medtroniclabs/microcoaching/sdk-android/` to see what version exists.

**`HTTP 401` in Logcat on coaching API calls**
→ The auth token was not picked up. On first install, the token is empty at `Application.onCreate()` time because the user has not logged in yet. This is expected — the token is refreshed in `LandingActivity` after login.

**Model download stays at 0% / never starts**
→ Check that `HF_TOKEN` is set in `spice-android/local.properties`. Without it, the HuggingFace request returns HTTP 401 and the download fails silently.

**Bengali TTS opens system settings instead of speaking**
→ The Bengali (bn-BD) voice pack is not installed. Follow the system prompt to install it. TTS will work on the next session after installation.
