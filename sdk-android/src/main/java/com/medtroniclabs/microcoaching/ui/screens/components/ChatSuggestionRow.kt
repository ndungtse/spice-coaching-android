package com.medtroniclabs.microcoaching.ui.screens.components

import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.medtroniclabs.microcoaching.Language
import com.medtroniclabs.microcoaching.MicroCoachingSDK
import com.medtroniclabs.microcoaching.ui.chat.SuggestedQuestion

/**
 * Horizontally scrollable suggestion chip row pinned above the input. Replaces
 * the prior in-list vertical chip stack to match `ai-coach.png` and to keep
 * suggestions reachable without losing scroll position in the message list.
 */
@Composable
fun SuggestionRow(
    questions: List<SuggestedQuestion>,
    onSendSuggested: (SuggestedQuestion) -> Unit,
) {
    // Default to BANGLA in Compose previews (no initialised SDK there).
    val sdkLanguage = if (androidx.compose.ui.platform.LocalInspectionMode.current) {
        Language.BANGLA
    } else {
        MicroCoachingSDK.getInstance().language
    }
    LazyRow(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        contentPadding = PaddingValues(horizontal = 16.dp),
    ) {
        // Key includes the list index because two suggestions can carry identical
        // text — e.g. when a Bangla session emits a chip whose English `question`
        // field was copied from `banglaQuestion` because translation hasn't landed
        // yet (or moduleFamilyId-derived dedup didn't run). Without the prefix
        // index the LazyRow throws `IllegalArgumentException: Key … was already
        // used` and brings down the whole compose tree.
        itemsIndexed(
            items = questions,
            key = { idx, q -> "$idx:${q.banglaQuestion}:${q.question}" },
        ) { _, q ->
            val displayText = when (sdkLanguage) {
                Language.ENGLISH -> q.question.ifBlank { q.banglaQuestion }
                Language.BANGLA -> q.banglaQuestion.ifBlank { q.question }
            }
            Surface(
                shape = RoundedCornerShape(50),
                color = MaterialTheme.colorScheme.surface,
                modifier = Modifier
                    .border(
                        width = 1.dp,
                        color = MaterialTheme.colorScheme.primary.copy(alpha = 0.4f),
                        shape = RoundedCornerShape(50),
                    )
                    .clickable { onSendSuggested(q) },
            ) {
                Text(
                    text = displayText,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
                )
            }
        }
    }
}
