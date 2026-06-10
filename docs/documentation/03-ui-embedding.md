# 03 — UI Embedding

**Version:** 0.3.8-SNAPSHOT · **Date:** 2026-06-03 · **Status:** Draft

How to surface the SDK's user-facing screens inside your app. Each surface is shown generically, then with the exact placement used in `spice-2.0-android` (the **SPICE reference**).

All SDK UI is built with Jetpack Compose internally, but every surface is consumable from a classic XML/View/Fragment host — you do not need to migrate your app to Compose.

---

## UI surface overview

| Surface | Type | Use case | How to show it |
|---|---|---|---|
| `CoachingChatFragment` | `Fragment` | Embed the AI chat inside your own screen/toolbar. | `newInstance(patientId, systemContext)` + `FragmentTransaction`. |
| `CoachingChatBottomSheet` | `BottomSheetDialogFragment` | Modal chat from a FAB. | `show(fragmentManager)`. |
| `CoachingFlowActivity` | `Activity` | Full Learn & Grow flow (UC-1: onboarding → modules → quiz). | `launch()` / `launchLearn()` / `launchLearnModule()`. |
| `ChatFab` | Composable | Floating "open chat" button. | Render into a `ComposeView`. |
| `MorningCard` / `LearnCard` | Composable | Home-screen morning-coaching banner. | Render into a `ComposeView`. |
| `RefresherBottomSheet` | `BottomSheetDialogFragment` | Morning refresher quiz/lesson. | `show(fm, chwId, …)`. |

Package: chat surfaces are under `com.medtroniclabs.microcoaching.ui.chat`; Compose components under `com.medtroniclabs.microcoaching.ui.components`; the flow activity under `…ui.flow`; theme wrapper `com.medtroniclabs.microcoaching.ui.theme.MicroCoachingTheme`.

---

## Embedding the chat as a Fragment

`CoachingChatFragment` manages all of its own state — model loading/download, streaming inference, error recovery, and offline degradation. The host only provides a container (and usually a toolbar).

```kotlin
// CoachingChatFragment.newInstance(patientId: String = "", systemContext: String = "")
supportFragmentManager.beginTransaction()
    .replace(R.id.coaching_container, CoachingChatFragment.newInstance())
    .commit()
```

With patient context (UC-2 Apply — counselling a specific patient):

```kotlin
CoachingChatFragment.newInstance(
    patientId = patient.patientTrackId.toString(),   // anonymised before any backend call
    systemContext = "Focus on hypertension medication adherence counselling.",
)
```

| Argument | Default | Meaning |
|---|---|---|
| `patientId` | `""` | Patient identifier for patient-specific coaching. Empty = general coaching. |
| `systemContext` | `""` | Optional extra system-prompt context for the session. |

**SPICE reference** — SPICE hosts the fragment in a thin wrapper Activity opened from the navigation drawer:

```kotlin
// CoachingAssistantActivity.kt
@AndroidEntryPoint
class CoachingAssistantActivity : BaseActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val contentView = layoutInflater.inflate(R.layout.activity_coaching_assistant, null)
        setMainContentView(
            view = contentView,
            isToolbarVisible = true,
            title = getString(R.string.chw_assistant),
            homeAndBackVisibility = Pair(false, true),
        )
        if (savedInstanceState == null) {
            supportFragmentManager
                .beginTransaction()
                .replace(R.id.coaching_assistant_container, CoachingChatFragment.newInstance())
                .commit()
        }
    }

    companion object {
        fun launch(context: Context) =
            context.startActivity(Intent(context, CoachingAssistantActivity::class.java))
    }
}
```

The container layout is a bare `FrameLayout`:

```xml
<!-- res/layout/activity_coaching_assistant.xml -->
<FrameLayout xmlns:android="http://schemas.android.com/apk/res/android"
    android:id="@+id/coaching_assistant_container"
    android:layout_width="match_parent"
    android:layout_height="match_parent" />
```

Declared in the manifest (internal only, IME resizes the input):

```xml
<!-- app/src/main/AndroidManifest.xml -->
<activity
    android:name=".ui.coaching.CoachingAssistantActivity"
    android:configChanges="orientation|screenSize|keyboardHidden"
    android:exported="false"
    android:windowSoftInputMode="adjustResize" />
```

Reached from the drawer menu item (hidden until login — see [02 — Re-initializing after login](./02-initialization.md#re-initializing-after-login-jwt)):

```xml
<!-- res/menu/activity_landing_menu.xml -->
<item
    android:id="@+id/chwAssistant"
    android:icon="@drawable/ic_chw_assistant"
    android:title="@string/chw_assistant"
    android:visible="false" />
```

> **Note:** in the embedded (fragment) path the chat's header close-icon is hidden — the host owns dismissal (here, the toolbar back button). The bottom-sheet path below shows its own close affordance.

---

## Showing the chat as a bottom sheet

For a modal chat (e.g. from a floating button), use `CoachingChatBottomSheet`. Same chat surface, self-dismissing.

```kotlin
// returns the fragment tag (String); no `tag` parameter to pass in
CoachingChatBottomSheet.show(parentFragmentManager)

// with patient context:
CoachingChatBottomSheet.show(
    parentFragmentManager,
    patientId = patient.patientTrackId.toString(),
    systemContext = "Counsel on exclusive breastfeeding.",
)
```

> **Note:** the signature is `show(fm: FragmentManager, patientId: String = "", systemContext: String = ""): String` and it **returns** the tag. There is no `tag` argument to pass.

**SPICE reference** — opened from the home-screen `ChatFab` (see below) via `HomeScreenFragment.launchCoachingChatSheet()`, which first checks model presence and otherwise prompts a download (see [05](./05-model-and-voice.md#driving-a-download-ui)).

---

## Launching the full coaching flow

`CoachingFlowActivity` runs the end-to-end Learn & Grow experience (UC-1) — onboarding slides, module list, lessons, quizzes — managing its own back stack. The host just launches it.

```kotlin
CoachingFlowActivity.launch(context, chwId)            // generic entry
CoachingFlowActivity.launchLearn(context, chwId)       // "Learn & Grow" button
CoachingFlowActivity.launchLearnModule(context, chwId) // jump straight to the module list
```

`chwId` is optional (falls back to the SDK's current CHW id, or an `"unknown_chw"` sentinel). The SDK manages navigation internally — do not manipulate its back stack.

> **Note:** `CoachingFlowActivity` is declared in the SDK's own manifest (along with `DocumentPreviewActivity` and `VideoPlayerActivity`, used internally for citation previews and rich-card video). They merge into your app automatically — you do not declare them. Of the three, only `CoachingFlowActivity` is launched directly by the host.

---

## Home-screen Compose components

The SDK provides ready-made composables for a home screen. You render them into `ComposeView` slots placed in your existing XML layout.

The slot pattern:

```xml
<!-- in your home layout -->
<androidx.compose.ui.platform.ComposeView
    android:id="@+id/coachingCardBanner"
    android:layout_width="match_parent"
    android:layout_height="wrap_content" />
```

```kotlin
binding.coachingCardBanner.apply {
    setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
    setContent {
        MicroCoachingTheme {
            // SDK composables go here
        }
    }
}
```

Component signatures:

| Composable | Signature |
|---|---|
| `ChatFab` | `ChatFab(onClick: () -> Unit, modifier: Modifier = Modifier)` |
| `MorningCard` | `MorningCard(moduleTitle, cardCount, questionCount, estimatedMinutes, onStart, onSkip, modifier)` |
| `LearnCard` | `LearnCard(moduleTitle, questionCount, estimatedMinutes, onStart, onSkip, modifier)` |
| `LearnFab` | `LearnFab(onClick: () -> Unit, modifier: Modifier = Modifier, badgeCount: Int = 0)` |

**SPICE reference** — `HomeScreenFragment.setupCoachingSurfaces()` wires two slots declared in `res/layout/fragment_home_screen.xml` (`@id/coachingCardBanner` above the menu grid, `@id/chatFab` bottom-right):

```kotlin
private fun setupCoachingSurfaces() {
    if (!MicroCoachingSDK.isInitialized()) return
    val sdk = MicroCoachingSDK.getInstance()

    sdk.onHomeScreenShown(chwId)   // see 04 — Hooks & Data

    // Morning-coaching banner — collapses to nothing when there is no module.
    binding.coachingCardBanner.apply {
        setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
        setContent {
            MicroCoachingTheme {
                val top = sdk.getSelectedMorningModule()
                val sdkDismissed by sdk.morningRefresherDismissed.collectAsState()
                // ... resolves title for current language, question count, etc. ...
                if (top != null /* && not dismissed */) {
                    if (top.cardCount > 0) {
                        MorningCard(
                            moduleTitle = title,
                            cardCount = top.cardCount,
                            questionCount = effectiveQuestionCount,
                            estimatedMinutes = top.estimatedMinutes,
                            onStart = onStart,   // opens RefresherBottomSheet
                            onSkip = onSkip,     // sdk.dismissMorningRefresher()
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                        )
                    } else {
                        LearnCard(/* moduleTitle, questionCount, estimatedMinutes, onStart, onSkip */)
                    }
                }
            }
        }
    }

    // Chat FAB — opens the chat bottom sheet.
    binding.chatFab.apply {
        setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
        setContent { MicroCoachingTheme { ChatFab(onClick = { launchCoachingChatSheet() }) } }
    }
}
```

Key points from the SPICE wiring:
- `MorningCard` is used when the top module has card content (`cardCount > 0`); `LearnCard` is the fallback for question-only modules.
- The banner reads `sdk.getSelectedMorningModule()` and collapses when it is `null` or dismissed.
- `onSkip` calls `sdk.dismissMorningRefresher()`; `onStart` opens `RefresherBottomSheet` (below).

> **Note (tablets):** the `res/layout-sw600dp/fragment_home_screen.xml` variant declares an extra `@id/learnFab` `ComposeView` slot, but it is **not currently wired** in `HomeScreenFragment` (no `setContent`). Treat it as a reserved slot, not a live LearnFab. Wire it with `LearnFab(onClick = { CoachingFlowActivity.launchLearn(requireContext(), chwId) })` if you want a tablet Learn entry point.

---

## Morning refresher & learn bottom sheets

`RefresherBottomSheet` presents the morning refresher (cards → quiz). SPICE opens it from the `MorningCard`/`LearnCard` Start action:

```kotlin
RefresherBottomSheet.show(
    parentFragmentManager,
    chwId,
    fromHomeScreen = true,
    entryMode = RefresherBottomSheet.EntryMode.QUESTION_FIRST,
)
```

The label's "wrong question count" comes from `QuickLearnViewModel`:

```kotlin
val morningVm: QuickLearnViewModel = viewModel(
    factory = QuickLearnViewModel.factory(appContext, chwId),
)
val wrongCount by morningVm.wrongQuestionCount.collectAsState()
LaunchedEffect(top?.moduleId) { morningVm.computeWrongQuestionCount() }
```

---

## Theming

Wrap every SDK composable in `MicroCoachingTheme { … }` (as shown above). It provides the SDK's Material3 theme and the injected locale so text renders in the configured `Language`.

> **Note:** `uiTheme` in `MicroCoachingConfig` does not currently change colours — SDK screens always render the light scheme. See [02 — config reference](./02-initialization.md#behaviour-thresholds-ui-data-testing).

---

## Next steps

- [04 — Hooks & Data](./04-hooks-and-data.md) — `onHomeScreenShown`, `onAssessmentSubmitted`, and reading SDK data.
- [05 — Model & Voice](./05-model-and-voice.md) — gating chat on model presence and the download prompt.
