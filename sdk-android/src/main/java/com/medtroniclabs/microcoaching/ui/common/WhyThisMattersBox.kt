package com.medtroniclabs.microcoaching.ui.common

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Lightbulb
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import com.medtroniclabs.microcoaching.ui.theme.SpiceBlueDark
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.medtroniclabs.microcoaching.R

/**
 * The blue "why this matters" explanation box shown under a quiz answer. Shared by
 * [AnswerFeedbackOverlay] and [InlineAnswerFeedback] (previously a byte-identical private copy
 * in each).
 */
@Composable
internal fun WhyThisMattersBox(explanation: String) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(Color(0xFFDCEEFF), shape = RoundedCornerShape(10.dp))
            .padding(12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                imageVector = Icons.Outlined.Lightbulb,
                contentDescription = null,
                tint = SpiceBlueDark,
                modifier = Modifier.size(16.dp),
            )
            Spacer(modifier = Modifier.size(6.dp))
            Text(
                text = stringResource(R.string.feedback_why_this_matters),
                style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold),
                color = SpiceBlueDark,
            )
        }
        Spacer(Modifier.height(6.dp))
        Text(
            text = explanation,
            style = MaterialTheme.typography.bodySmall,
            color = Color(0xFF1A3A5C),
        )
    }
}
