package com.medtroniclabs.microcoaching.ui.fab

import android.content.Context
import android.util.AttributeSet
import android.widget.FrameLayout
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import com.medtroniclabs.microcoaching.ui.theme.MicroCoachingTheme

/**
 * XML-embeddable coaching FAB.
 *
 * Wraps a [ComposeView] that renders [CoachingFab] (green FAB + numeric badge).
 * SPICE drops it into its XML layout without touching Compose directly.
 *
 * **SPICE XML usage:**
 * ```xml
 * <com.medtroniclabs.microcoaching.ui.fab.CoachingFabView
 *     android:id="@+id/coachingFab"
 *     android:layout_width="wrap_content"
 *     android:layout_height="wrap_content"
 *     android:layout_marginEnd="16dp"
 *     android:layout_marginBottom="16dp"
 *     app:layout_constraintEnd_toEndOf="parent"
 *     app:layout_constraintBottom_toBottomOf="parent" />
 * ```
 *
 * **SPICE Kotlin (Fragment.onViewCreated):**
 * ```kotlin
 * binding.coachingFab.setOnCoachingFabClickListener {
 *     CoachingFlowActivity.launch(requireActivity())
 * }
 * ```
 *
 * The badge count is hardcoded to `1` in Phase 0.5.
 * Phase 3 will expose `setBadgeCount(count: Int)` driven by a pending-module count.
 */
class CoachingFabView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : FrameLayout(context, attrs, defStyleAttr) {

    private var clickListener: (() -> Unit)? = null

    init {
        val composeView = ComposeView(context).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
            setContent {
                MicroCoachingTheme {
                    CoachingFab(
                        badgeCount = 1, // Phase 3: drive from LearnRepository
                        onClick = { clickListener?.invoke() },
                    )
                }
            }
        }
        addView(composeView)
    }

    /**
     * Register a click listener for the FAB.
     * Called when the CHW taps the coaching FAB.
     */
    fun setOnCoachingFabClickListener(listener: () -> Unit) {
        clickListener = listener
    }
}
