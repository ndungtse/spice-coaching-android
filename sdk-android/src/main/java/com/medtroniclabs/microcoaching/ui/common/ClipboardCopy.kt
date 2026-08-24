package com.medtroniclabs.microcoaching.ui.common

import android.content.ClipData
import android.os.Build
import android.widget.Toast
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import com.medtroniclabs.microcoaching.R
import kotlinx.coroutines.launch

/**
 * Returns a `copy(text)` action for chat message text.
 *
 * Single definition so every copy affordance behaves the same — the assistant action row and
 * the long-press menu on a user bubble both call this.
 *
 * Confirms the copy only below Android 13, which shows a system confirmation of its own:
 * above it a toast of ours would double up, below it a silent copy reads as a dead control.
 * Blank text is ignored rather than clearing whatever the user already had on the clipboard.
 */
@Composable
internal fun rememberCopyToClipboard(): (String) -> Unit {
    val clipboard = LocalClipboard.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val confirmation = stringResource(R.string.chat_copied)
    return remember(clipboard, context, scope, confirmation) {
        { text: String ->
            if (text.isNotBlank()) {
                // Writing to the clipboard suspends, so it runs in the composition's scope
                // and is cancelled with the surface rather than outliving it.
                scope.launch {
                    clipboard.setClipEntry(ClipEntry(ClipData.newPlainText(null, text)))
                    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
                        Toast.makeText(context, confirmation, Toast.LENGTH_SHORT).show()
                    }
                }
                Unit
            }
        }
    }
}
