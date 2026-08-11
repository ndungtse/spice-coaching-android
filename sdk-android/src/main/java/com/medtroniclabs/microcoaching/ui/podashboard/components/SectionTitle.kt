package com.medtroniclabs.microcoaching.ui.podashboard.components

import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.medtroniclabs.microcoaching.ui.theme.SpiceBlue

/** Bold accent section header ("My SKs", "Module Completion", …). */
@Composable
fun SectionTitle(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text,
        color = SpiceBlue,
        fontWeight = FontWeight.Bold,
        style = MaterialTheme.typography.titleSmall,
        modifier = modifier.padding(horizontal = 16.dp, vertical = 8.dp),
    )
}
