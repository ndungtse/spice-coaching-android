package com.medtroniclabs.microcoaching.ui.flow

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.lifecycle.Lifecycle
import androidx.navigation.NavHostController

// Stress-safe navigation helpers + deep-restore recovery surface for the coaching
// NavHost, extracted verbatim from CoachingNavGraph.kt (behaviour-preserving).
// Same package, so no call-site imports change; visibility widened private->internal.

/**
 * Recovery surface for a deep destination that was restored without its backing
 * [LearnViewModel] state.
 *
 * The deep screens (LessonContent / LessonPlayer / QuizQuestion / QuizResult)
 * render gated on `uiState` — which only lives in memory (`activeModule`,
 * `activeQuestions`, `_uiState`). Navigation-Compose, however, persists the back
 * stack across **process death**, so a low-memory kill (typically after the CHW
 * leaves via the Home / Recents system buttons) can restore us straight onto one
 * of those routes with the VM reset to [LearnUiState.Loading]. With nothing to
 * render the screen goes blank — exactly the QA-reported symptom.
 *
 * Rather than composing blank we bounce back to the modules home, which renders
 * its own `Loading → ModuleList` states gracefully. We prefer [NavHostController.popBackStack]
 * to ModuleReady (the restored start destination sits at the bottom of the stack,
 * so this also clears the orphaned deep entries); only if it isn't on the stack do
 * we navigate fresh.
 *
 * Callers gate entry to this with a `remember { uiState.value }` snapshot — read
 * once when the destination first enters composition — so it can only fire on a
 * genuine state-loss restore, never during the normal back-navigation window where
 * `popToModuleList()` momentarily flips `uiState` to `ModuleList` while the old
 * entry is still animating out.
 */
@Composable
internal fun RecoverToModulesHome(navController: NavHostController) {
    LaunchedEffect(Unit) {
        navController.popToHome()
    }
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        CircularProgressIndicator()
    }
}

// ── Stress-safe navigation helpers ──────────────────────────────────────────
//
// QA's back-spam stress test surfaced the remaining white-screen class: during
// a pop/navigate TRANSITION the outgoing destination is still composed and its
// BackHandler / click handlers are still live. A second back press (or a
// double-tap) queued in that window re-fires the handler, and the second
// popBackStack() removes the destination BENEATH — spam long enough and the
// START destination is popped, leaving the NavHost with an EMPTY back stack,
// which composes nothing: a permanent white screen. Duplicate navigate() calls
// are the same race (stacked duplicate entries → extra back presses → the
// double-pop above).
//
// Every user-triggered navigation below therefore goes through these helpers:
//  • [whenSettled] drops events that arrive while a transition is in flight
//    (the current entry is only RESUMED when navigation is settled) — the
//    documented dedupe pattern for Navigation-Compose.
//  • [popOrFinish] can never empty the NavHost — at the bottom of the stack it
//    exits the coaching flow instead of popping the start destination.
//  • [popToOrHome]/[popToHome] make targeted pops self-recovering: a target
//    route missing from the stack rebuilds onto the modules home instead of
//    silently doing nothing (dead button) or blanking.

/** True when the top entry is settled — no push/pop transition in flight. */
internal fun NavHostController.isSettled(): Boolean =
    currentBackStackEntry?.lifecycle?.currentState?.isAtLeast(Lifecycle.State.RESUMED) == true

/** Run [action] only when navigation is settled; drops spammed duplicates. */
internal inline fun NavHostController.whenSettled(action: () -> Unit) {
    if (isSettled()) action()
}

/**
 * Pop one entry without ever emptying the NavHost: at the bottom of the stack
 * the coaching flow exits via [onFinish] instead of popping the start
 * destination into a blank host.
 */
internal fun NavHostController.popOrFinish(onFinish: () -> Unit) {
    if (previousBackStackEntry != null) popBackStack() else onFinish()
}

/**
 * Pop back to the modules home. When ModuleReady isn't on the stack (an
 * onboarding-start stack or an exotic restore), rebuild the stack onto it —
 * a failed targeted pop is a dead button at best and a blank host at worst.
 */
internal fun NavHostController.popToHome() {
    val popped = popBackStack(route = CoachingRoute.ModuleReady.route, inclusive = false)
    if (!popped) {
        navigate(CoachingRoute.ModuleReady.route) {
            popUpTo(graph.startDestinationId) { inclusive = true }
            launchSingleTop = true
        }
    }
}

/** Pop back to [route]; recover to the modules home when it's not on the stack. */
internal fun NavHostController.popToOrHome(route: String) {
    if (!popBackStack(route = route, inclusive = false)) popToHome()
}
