# 07 — Theming & Brand Customization

**Version:** 0.6.0-SNAPSHOT · **Date:** 2026-08-31 · **Status:** Draft

The SDK ships a SPICE-blue light theme. A host app can replace the brand — colours and
type scale — so SDK screens match its own identity. This doc covers how, what is
overridable, and the two traps that make theming appear not to work.

Prerequisites: [01 — Setup](./01-setup.md) and [02 — Initialization](./02-initialization.md).

---

## Quick start

Two steps. Define a token set, register it on the Builder.

```kotlin
// app/src/main/java/.../microcoaching/UhisCoachingTheme.kt
import androidx.compose.ui.graphics.Color
import com.medtroniclabs.microcoaching.ui.theme.CoachingColors

val UhisCoachingColors: CoachingColors = CoachingColors.Spice.copy(
    primary = Color(0xFFD6218C),            // brand magenta
    primaryContainer = Color(0xFFFBE3F0),    // soft wash for chips and banners
    onPrimaryContainer = Color(0xFF8E1259),  // deep shade for content on the wash
    secondary = Color(0xFF8E1259),
)
```

```kotlin
// Application.onCreate()
MicroCoachingSDK.Builder(this)
    .language(Language.BANGLA)
    .backendUrl(BuildConfig.COACHING_BACKEND_URL)
    .theme(UhisCoachingColors)
    .typography(coachingTypography(fontFamily = MyBrandFont))
    .build()
```

Four tokens is usually enough. Everything else — the neutral text ramp, success /
warning / error / reward families, locked states — inherits the SDK's defaults, which is
the point of `copy`: state what you care about, inherit a coherent design for the rest.

---

## Two traps

### 1. Registration is init-time only — you cannot wrap SDK content

`MicroCoachingTheme` is not a theme you can override from outside. Wrapping SDK content
in your own `MaterialTheme` does **not** work, for two reasons:

* `MaterialTheme` **replaces** the colour scheme rather than inheriting it, so the SDK's
  wrapper discards whatever an outer theme provided.
* For most surfaces there is no "outside". `CoachingFlowActivity` and the four
  bottom-sheet fragments own their own Compose roots, and `SdkLocalizedTheme` applies the
  wrapper internally at every entry point.

The theme therefore has to reach the SDK through `MicroCoachingConfig`, which means the
Builder.

### 2. Every `Builder.build()` replaces the whole config

`build()` does not merge — it constructs a fresh `MicroCoachingConfig`. Anything a
`build()` call omits reverts to the SDK default, silently, with no compile error.

This matters because the SPICE integration initialises the SDK **twice**: once in
`SpiceBaseApplication.initCoachingSdk()` at process start, and again in
`LandingActivity.reinitCoachingSdkWithToken()` once auth resolves (see
[02 — post-login rebuild](./02-initialization.md)). A `.theme()` on only the first call
is discarded the moment the user logs in — the app looks correctly branded on the splash
and reverts to SPICE blue immediately after.

> **Add `.theme()` to every `Builder.build()` site in your app.** If you add any Builder
> option and see it "not apply", check whether a later rebuild omits it. Note the two
> SPICE sites also differ deliberately (`forceMode` is `ONLINE` in the Application and
> `EDGE` after login), so they cannot simply be merged.

---

## What is overridable

`CoachingColors` holds 35 tokens. Names are semantic, never brand-derived — `primary`,
not `spiceBlue` — because a token named for a colour is misleading once a host has
changed it.

### Read at call sites as `MaterialTheme.colorScheme.*`

Eighteen tokens project onto real Material 3 roles.

| Token | SPICE default | M3 role |
|---|---|---|
| `primary` | `0xFF2514BE` | `primary` |
| `onPrimary` | `0xFFFFFFFF` | `onPrimary` |
| `primaryContainer` | `0xFFE3F3FA` | `primaryContainer` |
| `onPrimaryContainer` | `0xFF004B87` | `onPrimaryContainer` |
| `secondary` | `0xFF004B87` | `secondary` |
| `onSecondary` | `0xFFFFFFFF` | `onSecondary` |
| `background` | `0xFFFFFFFF` | `background` |
| `onBackground` | `0xFF001E46` | `onBackground` |
| `surface` | `0xFFFFFFFF` | `surface` |
| `onSurface` | `0xFF001E46` | `onSurface` |
| `surfaceMuted` | `0xFFF8FAFC` | `surfaceContainerLow` |
| `textMuted` | `0xFF6B6B7B` | `onSurfaceVariant` |
| `border` | `0xFFEFEFF3` | `outlineVariant` |
| `borderStrong` | `0xFFD0D5DD` | `outline` |
| `error` | `0xFFB00020` | `error` |
| `errorContainer` | `0xFFFFEBEE` | `errorContainer` |
| `onErrorContainer` | `0xFF7F0014` | `onErrorContainer` |
| `scrim` | `0xFF000000` | `scrim` |

Roles not listed keep their `lightColorScheme` defaults — the SDK has no design intent
for `tertiary` or `inversePrimary`, and inventing tokens for them would grow the API
without giving hosts anything they asked for.

### Read at call sites as `CoachingTheme.colors.*`

Seventeen tokens have no Material 3 equivalent.

| Token | SPICE default | Serves |
|---|---|---|
| `textStrong` | `0xFF101828` | Titles. Distinct from `onSurface`, which is blue-navy |
| `textBody` | `0xFF344054` | Body copy, markdown |
| `textDisabled` | `0xFF888888` | Inactive labels |
| `success` / `successContainer` / `onSuccessContainer` | `0xFF1B6B4A` / `0xFFD7F0E5` / `0xFF0A3D27` | Correct answers, completed modules |
| `warning` / `warningContainer` / `onWarningContainer` | `0xFFF57C00` / `0xFFFFF3CD` / `0xFF856404` | Streaks, achievements, moderate severity |
| `attention` | `0xFFB91C1C` | The NEW and CRITICAL badges, high-severity chip |
| `reward` / `rewardContainer` / `rewardOutline` | `0xFFFFC83D` / `0xFFFFF8E1` / `0xFFEBC85B` | XP badges and bursts |
| `quizOptionSurface` | `0xFFF4F2FA` | Quiz options inside a white sheet |
| `lockedSurface` / `lockedOutline` | `0xFFE4E8EF` / `0xFFC3C9D4` | Locked badges, untravelled journey path |
| `categoryTags` | 3 `(container, onContainer)` pairs | Content-domain and practice-zone chips |

`attention` is deliberately **not** `error`: two of its four uses label new content rather
than a failure, so a "NEW" flag would otherwise read as an error state.

The split is enforced by the type system, not by convention — `CoachingTheme.colors` has
no `primary` property, so a call site cannot end up with two ways to ask for it.

---

## What you get for free

Some colours are *derived* from `primary`, so overriding the brand carries them along
without your naming them:

| Surface | Derivation |
|---|---|
| Outgoing chat bubble | `primary` at full strength; label is `onPrimary` |
| Incoming chat bubble | `primary` at 6% over `surface`; label picked by contrast |
| SK-detail header gradient | `primary` → `primary` at 85% over `surface` |
| Progress-bar tracks | the bar's own fill at 15% alpha |
| Coaching status bar | `primary` |
| `onAttention` badge labels | picked by measured contrast against `attention` |

Where a label is "picked by contrast", the SDK measures WCAG contrast and chooses the
more readable of light or dark. This protects hosts with a pale brand colour from
white-on-pale text without them having to think about it.

> **On blends and hue.** The outgoing bubble was originally a blend of `primary` and
> deliberately is not any more. The safe blend factor turns out to depend on the brand
> hue's luminance: a dark blue is dark enough for white text from 70% up, while a light
> magenta is not until 90% — leaving an 85–95% band where neither white nor near-black
> clears WCAG AA. Full strength has no such cliff. If you add a derived colour, check it
> against both a dark and a light brand hue.

---

## Typography

```kotlin
.typography(coachingTypography(fontFamily = MyBrandFont))
```

`coachingTypography()` returns a Material 3 `Typography` with all fifteen styles defined
and the supplied family applied to every one. Passing no argument gives
`FontFamily.Default`.

All fifteen are filled in on purpose: the SDK previously defined only three, so a host
font would have been honoured by body text and silently ignored by every heading.

---

## Not themeable

| Thing | Why |
|---|---|
| Dark mode | The four bottom-sheet fragments force `Theme_Material3_Light_BottomSheetDialog` and three manifest activities force `Theme.AppCompat.Light.NoActionBar`. A dark Compose scheme inside a light window renders invisible light-on-light text. Supporting dark mode means changing those window themes. |
| Leaderboard medals | Gold, silver and bronze carry universal meaning; tinting bronze to a brand colour would be a bug, not a feature. |
| Shapes and elevation | No tokens today. |
| Runtime theme switching | Registration is init-time. Changing theme requires another `build()`. |
| `uiTheme` / `CoachingUiTheme` | **Deprecated.** Never read by the SDK. Use `themeColors`. |

Colours over media the theme does not control — video-player chrome, thumbnail scrims,
the image viewer's backdrop — stay white or black on purpose.

---

## Verifying

Register a deliberately clashing theme and walk the surfaces. Anything still showing the
old brand is a missed spot:

```kotlin
.theme(CoachingColors.Spice.copy(primary = Color(0xFF00695C)))  // teal
```

Walk: coaching home (SK and PO), module detail, all-modules grid, quiz question and
result, refresher sheet, quick-learn sheet, chat sheet (both bubbles), PO dashboard and
every drilldown, badges, Your Journey, onboarding slides and coach marks, training
videos, training requests, the FABs, the morning card.

CI guards regression on the SDK side: `scripts/check-no-hardcoded-colors.sh` fails the
build if a `Color(0x…)` literal appears outside `ui/theme/`. It cannot see named
constants like `Color.White`, so review still has to ask whether a named colour is
carrying a token's role.

---

## Next steps

- [03 — UI Embedding](./03-ui-embedding.md) — where `MicroCoachingTheme` wraps each surface.
- [02 — Initialization](./02-initialization.md) — the Builder and the post-login rebuild.
- [06 — Troubleshooting](./06-troubleshooting.md) — build and runtime errors.
