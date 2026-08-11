package com.medtroniclabs.microcoaching.ui.podashboard.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.medtroniclabs.microcoaching.R
import com.medtroniclabs.microcoaching.ui.theme.SpiceBlue
import com.medtroniclabs.microcoaching.ui.theme.SpiceBlueContainer

private val SearchBorder = Color(0xFFD1D5DB)
private val ControlHeight = 44.dp

/** Compact search field driving client-side filtering, with an optional (decorative) filter affordance. */
@Composable
fun SkSearchBar(
    query: String,
    onQueryChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    hint: String = stringResource(R.string.po_search_hint),
    showFilterButton: Boolean = true,
) {
    Row(modifier = modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Row(
            modifier = Modifier
                .weight(1f)
                .height(ControlHeight)
                .clip(RoundedCornerShape(percent = 50))
                .background(Color.White)
                .border(1.dp, SearchBorder, RoundedCornerShape(percent = 50))
                .padding(horizontal = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Filled.Search, contentDescription = null, tint = MutedText, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Box(modifier = Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
                if (query.isEmpty()) {
                    Text(hint, color = MutedText, style = MaterialTheme.typography.bodyMedium)
                }
                BasicTextField(
                    value = query,
                    onValueChange = onQueryChange,
                    singleLine = true,
                    textStyle = MaterialTheme.typography.bodyMedium.copy(color = MaterialTheme.colorScheme.onBackground),
                    cursorBrush = SolidColor(SpiceBlue),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
        if (showFilterButton) {
            Spacer(Modifier.width(8.dp))
            // Decorative filter affordance — no filter menu yet.
            Box(
                modifier = Modifier.size(ControlHeight).clip(RoundedCornerShape(12.dp)).background(SpiceBlueContainer),
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.Filled.Tune, contentDescription = null, tint = SpiceBlue, modifier = Modifier.size(20.dp))
            }
        }
    }
}
