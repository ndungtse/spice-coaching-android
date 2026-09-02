# 03 — UI Embedding

**Version:** 0.6.0-SNAPSHOT · **Date:** 2026-08-31 · **Status:** Draft

How to surface the SDK's user-facing screens inside your app. Each surface is shown generically, then with the exact placement used in `spice-2.0-android` (the **SPICE reference**).

All SDK UI is built with Jetpack Compose internally, but every surface is consumable from a classic XML/View/Fragment host — you do not need to migrate your app to Compose.

---

## UI surface overview

| Surface | Type | Use case | How to show it |
|---|---|---|---|
| `CoachingChatFragment` | `Fragment` | Embed the AI chat inside your own screen/toolbar. | `newInstance(patientId, systemContext)` + `FragmentTransaction`. |
| `CoachingChatBottomSheet` | `BottomSheetDialogFragment` | Modal chat from a FAB. | `show(fragmentManager)`. |
| `CoachingFlowActivity` | `Activity` | Full Learn & Grow flow (UC-1: onboarding → modules → quiz). | `launch()` / `launchLearn()` / `launchLearnModule()`. |
| `CoachingGridTile` | Composable | Self-contained "Coaching" tile for a home menu grid (observes its own badges). | Render into a `ComposeView` — see [below](#coachinggridtile-inside-a-recyclerview). |
| `ChatFab` / `DraggableChatFab` | Composable | Floating "open chat" button. | Render into a `ComposeView`. |
| `CoachingFabView` | XML `View` | FAB for non-Compose hosts. | Place in XML + `setOnCoachingFabClickListener { … }`. |
| `MorningCard` / `LearnCard` | Composable | Home-screen morning-coaching banner. | Render into a `ComposeView`. |
| `RefresherBottomSheet` | `BottomSheetDialogFragment` | Morning refresher quiz/lesson. | `show(fm, chwId, …)`. |

> **Note:** `LearnFragment` is **deprecated** — launch the learning experience with `CoachingFlowActivity.launchLearnModule(context, chwId)` instead.

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

Reached from the drawer menu item (hidden until login — see [02 — Refreshing the auth token](./02-initialization.md#refreshing-the-auth-token-after-login)):

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

**SPICE reference** — opened from the home-screen `ChatFab` (see below) via `HomeScreenFragment.launchCoachingChatSheet()`, a two-line guard + show. No model check is needed: the model-download consent and progress UI live inside the SDK's chat setup screen (see [05](./05-model-and-voice.md)).

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

**SPICE reference** — `HomeScreenFragment.setupCoachingSurfaces()` today wires exactly one slot (`@id/chatFab`, bottom-right of `res/layout/fragment_home_screen.xml`) plus the two data hooks:

```kotlin
private fun setupCoachingSurfaces() {
    if (!MicroCoachingSDK.isInitialized()) return
    val sdk = MicroCoachingSDK.getInstance()

    sdk.onHomeScreenShown(chwId)   // see 04 — Hooks & Data
    pushTodaysVisits(sdk)          // sdk.onTodaysVisitsUpdated(…) — see 04

    // Chat FAB — opens the chat bottom sheet.
    binding.chatFab.apply {
        setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
        setContent { MicroCoachingTheme { ChatFab(onClick = { launchCoachingChatSheet() }) } }
    }
}
```

> **Note:** earlier SPICE versions also mounted `MorningCard`/`LearnCard` in a home-screen banner and opened `RefresherBottomSheet` from it. That wiring was removed — the SDK's own coaching flow ("Practice Zone") now owns refreshers end-to-end. `MorningCard`, `LearnCard`, `LearnFab`, and `RefresherBottomSheet` remain public if you want a host-mounted banner, but the tile + FAB pattern above is the current recommendation.

### CoachingGridTile inside a RecyclerView

`CoachingGridTile` is the self-contained "Coaching" menu tile — it observes its own badge/indicator state, so the host only supplies `onClick`. Mounting it in a plain `ComposeView` works as shown above. Inside a `RecyclerView` whose layout manager **measures items before attaching them** (e.g. `FlexboxLayoutManager` on tablets), `AbstractComposeView.onMeasure` cannot find a window recomposer and crashes — create one in the adapter and set it before first measure:

```kotlin
class MenuAdapter : RecyclerView.Adapter<...>() {
    private var recomposer: Recomposer? = null
    private var recomposeScope: CoroutineScope? = null

    override fun onAttachedToRecyclerView(recyclerView: RecyclerView) {
        val dispatcher = AndroidUiDispatcher.CurrentThread
        val scope = CoroutineScope(dispatcher)
        val newRecomposer = Recomposer(dispatcher)
        recomposeScope = scope
        recomposer = newRecomposer
        scope.launch { newRecomposer.runRecomposeAndApplyChanges() }
    }

    override fun onDetachedFromRecyclerView(recyclerView: RecyclerView) {
        recomposeScope?.cancel()
        recomposer = null
    }

    // onCreateViewHolder — BEFORE the first onMeasure:
    //   composeView.setParentCompositionContext(recomposer)
    //   composeView.setViewCompositionStrategy(
    //       ViewCompositionStrategy.DisposeOnDetachedFromWindowOrReleasedFromPool)
    // onBindViewHolder:
    //   composeView.setContent { CoachingGridTile(onClick = { onCoachingSelected() }) }
}
```

**SPICE reference** — `DashboardMenuItemsAdapter` (view type `VIEW_TYPE_COACHING`) uses exactly this recipe; the tile's tap launches `CoachingFlowActivity.launchLearn(requireContext(), chwId)`.

---

## Theming

Wrap every SDK composable in `MicroCoachingTheme { … }` (as shown above). It provides the SDK's Material3 theme and the injected locale so text renders in the configured `Language`.

To render the SDK in **your** brand colours, do **not** wrap SDK content in your own `MaterialTheme` — that does not work, because `MaterialTheme` replaces rather than inherits a colour scheme, and SDK-owned surfaces (`CoachingFlowActivity`, the bottom sheets) have no "outside" to wrap. Register a token set on the Builder instead:

```kotlin
MicroCoachingSDK.Builder(this)
    .theme(CoachingColors.Spice.copy(primary = Color(0xFF00695C)))
    .build()
```

Full reference — the 35 tokens, what is derived from `primary` for free, and the two traps that make theming appear not to work: **[07 — Theming](./07-theming.md)**.

> **Note:** `uiTheme` in `MicroCoachingConfig` is **deprecated** and was never read by the SDK. SDK screens always render the light scheme; see [07 — Not themeable](./07-theming.md#not-themeable).

---

## Next steps

- [00 — Quick Start](./00-quick-start.md) — the end-to-end integration sequence these surfaces slot into.
- [07 — Theming](./07-theming.md) — brand colours and typography for SDK screens.
- [04 — Hooks & Data](./04-hooks-and-data.md) — `onHomeScreenShown`, `onAssessmentSubmitted`, `onReferralSubmitted`, and reading SDK data.
- [05 — Model & Voice](./05-model-and-voice.md) — the model lifecycle behind the chat setup screen.
