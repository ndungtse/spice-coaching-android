package com.medtroniclabs.microcoaching.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalContext
import com.medtroniclabs.microcoaching.MicroCoachingSDK
import com.medtroniclabs.microcoaching.ui.theme.MicroCoachingTheme

/**
 * Wraps [content] so that every `stringResource(...)` inside resolves from the
 * **SDK-configured language** (`MicroCoachingSDK.language`), regardless of the
 * host activity's resource locale, and applies [MicroCoachingTheme].
 *
 * Use this at every Compose entry point that the SDK exposes — fragments,
 * dialog fragments, bottom-sheets, and host-embeddable components (banner,
 * FABs). Direct callers of `setContent { ... }` should wrap the inner block
 * with this helper rather than calling `MicroCoachingTheme` directly.
 *
 * Example:
 * ```kotlin
 * ComposeView(requireContext()).apply {
 *     setContent {
 *         SdkLocalizedTheme {
 *             ModuleReadyScreen(...)
 *         }
 *     }
 * }
 * ```
 *
 * Implementation mirrors the canonical pattern from [com.medtroniclabs.microcoaching.ui.flow.CoachingFlowActivity].
 */
@Composable
fun SdkLocalizedTheme(content: @Composable () -> Unit) {
    val base = LocalContext.current
    val langCtx = SdkLocaleHelper.wrap(base, MicroCoachingSDK.getInstance().language)
    CompositionLocalProvider(LocalContext provides langCtx) {
        MicroCoachingTheme {
            content()
        }
    }
}
