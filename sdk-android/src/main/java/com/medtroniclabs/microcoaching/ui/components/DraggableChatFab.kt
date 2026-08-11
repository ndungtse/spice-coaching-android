package com.medtroniclabs.microcoaching.ui.components

import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt

/**
 * Draggable wrapper around [ChatFab]. Starts at the bottom-right corner of the
 * enclosing [BoxScope] with [restingEndPadding] / [restingBottomPadding] of
 * clearance, then follows the user's finger across the screen so the FAB never
 * has to live permanently over a button bar or important content.
 *
 * Drag offset survives configuration changes via [rememberSaveable] but resets
 * between activity instances — that's a deliberate design call: the FAB returns
 * to its safe resting position on each new coaching session, so a stale offset
 * doesn't strand the button off-screen after a layout change.
 *
 * @param restingBottomPadding bottom margin of the resting position. Defaults to
 *   96.dp so the FAB clears a typical Previous / Next button bar (button height
 *   ~56.dp + container padding ~16.dp + breathing room ~24.dp).
 * @param restingEndPadding end margin of the resting position. Defaults to 16.dp
 *   matching the rest of the SDK's screen-edge inset.
 */
@Composable
fun BoxScope.DraggableChatFab(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    restingBottomPadding: Dp = 80.dp,
    restingEndPadding: Dp = 16.dp,
) {
    // Offsets are stored in pixels (not Dp) so the drag handler can keep
    // sub-pixel precision without recomputing density each frame.
    var offsetX by rememberSaveable { mutableStateOf(0f) }
    var offsetY by rememberSaveable { mutableStateOf(0f) }

    // Parent + FAB sizes drive the clamp. Without clamping, a flick can park the
    // FAB off-screen with no way back. Initialised to 0 — the first onSizeChanged
    // callback populates real values before the user can drag.
    var parentWidth by remember { mutableStateOf(0) }
    var parentHeight by remember { mutableStateOf(0) }
    var fabWidth by remember { mutableStateOf(0) }
    var fabHeight by remember { mutableStateOf(0) }

    // Invisible sibling that reports the enclosing Box's render size. Rendered
    // first so the FAB stacks on top in the parent's draw order.
    Box(
        Modifier
            .matchParentSize()
            .onSizeChanged {
                parentWidth = it.width
                parentHeight = it.height
            },
    )

    Box(
        modifier = modifier
            .align(Alignment.BottomEnd)
            .padding(end = restingEndPadding, bottom = restingBottomPadding)
            .offset { IntOffset(offsetX.roundToInt(), offsetY.roundToInt()) }
            .onSizeChanged {
                fabWidth = it.width
                fabHeight = it.height
            }
            .pointerInput(Unit) {
                detectDragGestures(
                    onDrag = { change, dragAmount ->
                        change.consume()
                        // The resting position sits at the bottom-right of the parent.
                        // Positive X / Y would push the FAB further off the bottom-right
                        // edge — clamp to ≤ 0. The lower bound is the parent extent minus
                        // the FAB extent, so the FAB can travel exactly to the
                        // top-left corner of the parent and no further.
                        val maxNegX = -(parentWidth - fabWidth).coerceAtLeast(0).toFloat()
                        val maxNegY = -(parentHeight - fabHeight).coerceAtLeast(0).toFloat()
                        offsetX = (offsetX + dragAmount.x).coerceIn(maxNegX, 0f)
                        offsetY = (offsetY + dragAmount.y).coerceIn(maxNegY, 0f)
                    },
                )
            },
    ) {
        ChatFab(onClick = onClick)
    }
}
