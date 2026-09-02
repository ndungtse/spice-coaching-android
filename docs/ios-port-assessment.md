# iOS Port Assessment — Kotlin Multiplatform Feasibility

## TL;DR

Porting the SDK to iOS via Kotlin Multiplatform (KMP) is **feasible, and the current architecture helps a lot — but it is a substantial project in its own right, not a quick port**. Roughly 80–85% of the code can survive into shared multiplatform code. The two decisions that shape everything are the **UI strategy** (Compose Multiplatform vs. native SwiftUI) and the **on-device AI stack**, which is the one area where iOS feature parity is genuinely at risk. Two behaviors will differ on iOS no matter what we do: background sync scheduling and (possibly) offline inference, because the OS itself is different.

Before promising anything, we should run a **short, time-boxed spike** (see [Recommended next step](#recommended-next-step)).

## What the SDK is made of

Measured from `sdk-android/src/main` (~60k lines of Kotlin across 462 files):

| Package | Files | Lines | Share | iOS story |
|---|---|---|---|---|
| `ui/` (Compose) | 251 | 35,635 | ~60% | Portable **only** via Compose Multiplatform; otherwise a SwiftUI rewrite |
| `ai/` (LiteRT-LM, retrieval, translation, STT) | 43 | 7,978 | ~13% | **Hardest area** — inference engines are per-platform |
| `data/` (Room, repositories) | 76 | 5,773 | ~10% | Room officially supports KMP/iOS since 2.7 — schema and DAOs largely move as-is |
| `domain/` | 47 | 5,322 | ~9% | Mostly pure Kotlin — best porting candidate |
| `sync/` (WorkManager) | 14 | 2,240 | ~4% | Logic portable; **scheduling is per-platform** (BGTaskScheduler on iOS) |
| `network/` (Retrofit/OkHttp) | 6 | 1,622 | ~3% | Rewrite against Ktor client (Retrofit is JVM-only) |
| `sdk/` (public API, Builder) | 13 | 1,062 | ~2% | Portable; iOS gets a Swift-friendly facade |
| `content/`, `progress/`, `util/` | 12 | 795 | ~1% | Mostly portable |

One number that matters: **131 of the 211 non-UI files import `android.*` / `androidx.*` today.** Some of that is Room annotations (which survive KMP), but much of it is `Context`, `Log`, `Uri`, WorkManager, etc. that must be abstracted behind interfaces. This is the invisible grind that makes port estimates slip — the code is Android-first even in the "portable" layers.

## Where the current architecture helps

Several decisions already made turn out to be exactly what a KMP port wants:

- **No Hilt / no DI framework in the SDK** (deliberate — see the note in `sdk-android/build.gradle.kts`). A forced DI framework is the #1 porting pain in Android SDKs; our manual Builder wiring translates directly to `iosMain`.
- **kotlinx.serialization + kotlinx.coroutines** everywhere. Both are multiplatform already — zero change.
- **Room** gained official KMP support (including iOS) in 2.7 — schema, entities, and DAOs largely move as-is. No SQLDelight rewrite needed, which used to be the big cost here.
- **Clean package separation** (`data` / `domain` / `sync` / `network` / `ui`). The `commonMain` vs. platform module split is mostly already drawn along package lines.
- **Pluggable STT** — sherpa-onnx already lives outside the core SDK (`:sdk-android-sherpa`, hosts opt in via `offlineSttEngineFactory`). That factory seam is precisely the shape every platform-specific engine needs on iOS too.
- **No third-party PDF library** — we already dropped pdfium for the platform `PdfRenderer`. The iOS equivalent (PDFKit) is the same kind of thin platform shim.

## The easy work

Cheap or mechanical, low risk:

- **`domain/`, `content/`, `progress/`, most of `util/`** — pure Kotlin business logic; moves to `commonMain` nearly verbatim.
- **Room migration to KMP artifacts** — mostly dependency + driver changes (`BundledSQLiteDriver` on iOS), not logic changes.
- **kotlinx.serialization models and JSON handling** — no change.
- **Markdown parsing** — `org.jetbrains:markdown` is already multiplatform.
- **Coil** — Coil 3 is multiplatform; the image-loading calls in the rich-text renderer carry over.
- **Public API / Builder** — the shape stays; iOS additionally gets a Swift-ergonomic wrapper over the generated framework.

## The hard areas

In descending order of risk:

### 1. On-device AI and voice (`ai/`, ~8k lines) — highest-variance area

- **LLM inference (LiteRT-LM):** every catalog model is a `.litertlm`. LiteRT-LM now documents iOS support (native Swift API, iOS 15+, Metal/ANE acceleration), so the question is no longer *whether* it runs but **how our specific models perform on the iPhones our users hold** — speed and memory, verified in the spike. If that fails, offline chat changes shape on iOS: a product conversation, not an engineering one.
- **ML Kit on iOS is translation only.** The Translate API (EN→BN) exists on iOS — a straightforward expect/actual with two native call sites. But ML Kit's speech APIs don't come with it: the GenAI Speech Recognition API is Android-only, and ML Kit has no TTS on either platform. Voice on iOS comes from Apple's frameworks, not ML Kit.
- **STT:** today the platform `SpeechRecognizer` (`AndroidSpeechRecognizerEngine`) plus opt-in offline sherpa-onnx. The iOS equivalent is Apple's `Speech` framework (`SFSpeechRecognizer`, and the newer on-device `SpeechAnalyzer`). **Key check: Bangla coverage in Apple's recognizer** — if it's weak, the fallback is a sherpa-onnx iOS build (it's C++ and builds for iOS) behind our existing `offlineSttEngineFactory` seam.
- **TTS:** today Android `TextToSpeech` (`CoachingTtsHelper` — chat and lesson player, per-utterance locale for mixed EN/Bangla). The iOS equivalent is `AVSpeechSynthesizer`, an equally thin on-device shim — verify Bangla voice availability and quality on real devices.
- **The good structural news:** these engines already sit behind interfaces (`VoiceInputController`, the STT engine factory), and the retrieval/ranking pipeline is mostly pure Kotlin — the logic stays common; only the engines are per-platform.

### 2. UI strategy (35.6k lines, 60% of the SDK) — the biggest cost decision

Two options:

- **Compose Multiplatform (CMP)** — stable on iOS since mid-2025. Carries our UI over nearly verbatim and is the only way "works exactly the same" is *literally* true. Costs: the iOS artifact becomes a fat XCFramework (Compose runtime + Skia, tens of MB), and iOS host teams sometimes push back on embedding that. Some Android-specific UI entry points (`BottomSheetDialogFragment`, `VideoPlayerActivity`) still need iOS-native replacements around the edges.
- **Native SwiftUI** — idiomatic for iOS hosts, but a from-scratch rewrite of 35k lines, and "exactly the same" becomes a permanent manual design-parity effort across two UI codebases.

For an embedded SDK where the client explicitly wants identical behavior, **CMP is the recommendation** — but host-app appetite for binary size needs checking first (part of the spike).

### 3. Background sync (`sync/`) — guaranteed behavioral difference

WorkManager does not exist on iOS. The sync *logic* ports; the *scheduling* becomes `BGTaskScheduler` behind an expect/actual seam — and iOS is far stingier about background execution. iOS may defer background sync for hours or until the device is charging. **Sync will not behave "exactly the same" on iOS.** This should be set as an expectation with the client early.

### 4. Networking (`network/`)

Retrofit and OkHttp are JVM-only. ~1.6k lines rewritten against **Ktor client** (Darwin engine on iOS). Mechanical, but it touches every API call and the interceptor/logging setup.

### 5. Internal diagnostics (OpenTelemetry) — not our product telemetry/events

To avoid confusion: this is **not** the coaching telemetry/events the SDK reports to our backend — those are ordinary API calls and port together with the network layer. This section is only about the SDK's internal OpenTelemetry diagnostics (traces/metrics exported via OTLP). The OpenTelemetry Java SDK is not multiplatform, and OTel-Kotlin is immature. Plan: put the diagnostics behind a small interface in `commonMain`; keep the current OTel wiring on Android, ship a thinner iOS implementation initially.

### 6. The 131 Android-coupled files

Every `Context`, `Log`, `Uri`, file-path, and resource reference in non-UI code needs an abstraction or an expect/actual. Individually trivial, collectively a large amount of careful, boring work — and the main source of estimate slip.

## Where we will need platform-specific code (expect/actual map)

| Concern | Android (`androidMain`) — mostly exists today | iOS (`iosMain`) — to build |
|---|---|---|
| LLM inference | LiteRT-LM | LiteRT-LM iOS or MediaPipe LLM (spike to verify) |
| Translation EN→BN | ML Kit Translate | ML Kit Translate iOS |
| System STT | Platform `SpeechRecognizer` | Apple `Speech` (`SFSpeechRecognizer` / `SpeechAnalyzer`) |
| Offline STT | sherpa-onnx via `:sdk-android-sherpa` | sherpa-onnx iOS build behind the same factory |
| TTS | Android `TextToSpeech` | `AVSpeechSynthesizer` |
| Background sync scheduling | WorkManager | `BGTaskScheduler` |
| Video playback | Media3 / ExoPlayer | AVPlayer / AVKit |
| PDF viewing | `android.graphics.pdf.PdfRenderer` | PDFKit |
| HTTP engine | Ktor/OkHttp engine | Ktor/Darwin engine |
| SQLite driver | Room Android driver | Room `BundledSQLiteDriver` |
| SDK init / lifecycle | androidx.startup, Application context | Framework init from the host app |
| Bottom sheet / host entry points | `BottomSheetDialogFragment`, Activities | `UIViewController` wrappers around CMP |
| Internal diagnostics export (OpenTelemetry) | OpenTelemetry Java SDK | Thin native implementation behind interface |
| Model file storage & no-compress packaging | AGP `noCompress`, app files dir | Bundle resources / app support dir |

## Packaging size and performance expectations

The two questions an iOS host team will ask first. Ballparks below are library facts; our actual numbers come from the spike.

### Packaging size

- **The CMP baseline cost is fixed.** Compose Multiplatform statically links the Compose runtime + Skia into our framework: roughly ~10 MB of compressed download and tens of MB installed, before any of our code — we're shipping a rendering engine Apple doesn't provide.
- **Our code compiles bulkier on iOS.** Kotlin/Native binaries are larger per line than dex — several more MB on top. App Store thinning and compression cut the user-facing impact; the spike's skeleton-XCFramework measurement gives the real number.
- **Some things get cheaper on iOS.** ExoPlayer goes away (AVPlayer is the OS), PDF viewing is PDFKit (the OS), and `.litertlm` models download at runtime on both platforms — zero install cost.
- **ML Kit Translate is the heavy spot.** The iOS pod's installed footprint is tens of MB — notably worse than the Android artifact — plus the ~20 MB EN→BN language pack downloaded on demand. If translation matters less on iOS, make it an opt-in sub-pod from day one (same pattern as `:sdk-android-sherpa`).
- **No Compose dedup on iOS.** On Android, Compose is a Gradle dependency shared with the host app; on iOS the CMP runtime is statically linked *inside our framework*, so a host embedding a second CMP-based SDK would carry two copies of Skia. Fine for one SDK (link symbols hidden to avoid clashes) — state it to the client up front.

### Performance

- **UI rendering: expect parity, verify feel.** CMP renders via Skia/Metal at native refresh rates (including 120 Hz ProMotion) and benchmarks at parity with SwiftUI for list-heavy screens — which is what our learn/quiz/chat UI is. The rough edges are text-field/keyboard interop, scroll-edge physics, and VoiceOver accessibility: much improved, none fully free, each needing an explicit QA pass.
- **First-open latency.** The first CMP screen pays a one-time runtime/Skia init cost (order of one to a few hundred ms on modern iPhones) — reads as a slow first tap inside a host app. Mitigate by pre-warming the runtime at SDK init, designed in from the start.
- **Business logic: fine, with one thing to profile.** Kotlin/Native has no JIT — irrelevant for repositories, sync, and serialization, but the offline retrieval/ranking pipeline (`ai/retrieval`) is our hottest pure-Kotlin loop and can run meaningfully slower on Native than on ART. If it does, the fix is targeted optimization of that path, not a strategy change.
- **Inference: the hardware helps; the memory ceiling doesn't.** Recent iPhones should match or beat our users' mid-range Android devices on our small `litert-community` models. The iOS-specific risk is memory: iOS kills apps exceeding their memory budget (jetsam) far more aggressively than Android, and the budget belongs to the *host app*. Model load + KV cache inside someone else's budget is exactly what the spike measures — tokens/sec *and* peak memory.
- **Sync is a scheduling story, not a speed story.** Foreground sync feels identical; iOS doesn't make background sync slower, it makes it *later* (see the background sync section above).
- **Battery/thermal:** same class as Android for equivalent work; sustained on-device inference throttles on both platforms.

**Summary:** size carries a fixed ~10 MB download tax plus a heavy optional translation pod; performance should be at parity or better everywhere except first-open latency and the memory ceiling during inference — and both of those are measurable in the spike, not matters of opinion.

## Rough plan and relative effort

Assuming the spike passes, phased so the Android SDK keeps shipping throughout:

| Phase | Work | Relative size |
|---|---|---|
| 1 | Restructure into `commonMain`/`androidMain`/`iosMain`; Retrofit→Ktor; Room→KMP artifacts | Medium |
| 2 | Abstract the Android-coupled non-UI files behind interfaces | Medium |
| 3 | CMP UI bring-up on iOS + native replacements (video, PDF, sheets, navigation entry) | Large |
| 4 | AI and voice stack on iOS (inference, translation, STT/TTS) | Large, and the highest variance |
| 5 | XCFramework packaging, Swift facade, sample iOS host app, device QA | Small–medium |

The AI stack drives the overall uncertainty — its size isn't really known until the spike answers the inference question. Throughout, the restructure is incremental and the Android artifact remains the same `.aar` to hosts.

## Recommended next step

A **short, time-boxed spike** answering only the two kill questions, before any commitment to the client:

1. **Inference:** take one of our `.litertlm` catalog models and get it generating tokens on a physical iPhone (LiteRT-LM iOS or MediaPipe). Measure tokens/sec and memory. If this fails, the whole plan changes shape.
2. **Packaging:** stand up a skeleton KMP module (one Room table, one Ktor call, one Compose screen) exported as an XCFramework into a toy iOS app. Measure binary size and confirm the embedding story is acceptable to an iOS host team.
3. **Voice:** confirm Bangla coverage in Apple's speech recognizer and Bangla TTS voice quality on a real device — a quick check that decides whether iOS STT needs sherpa-onnx.

## The overall framing for the porting expectations

- The Android investment is **not throwaway** — ~80–85% of the code becomes shared multiplatform code that drives both SDKs.
- "An iOS SDK that works exactly the same" is a milestone at the **end of a substantial effort**, not a conversion step.
- Two things will behave somewhat differently on iOS regardless of approach: **background sync** (iOS throttles background work) and possibly **offline AI** (pending the spike) — because the operating systems differ, not because of the porting strategy.
