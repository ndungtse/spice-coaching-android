package com.medtroniclabs.microcoaching.ui.markdown

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.medtroniclabs.microcoaching.ui.theme.SpiceBlue
import com.medtroniclabs.microcoaching.ui.theme.SpiceBlueContainer
import com.medtroniclabs.microcoaching.ui.theme.SpiceNavy

/**
 * Visual configuration for [MarkdownText]. Pull defaults from [MarkdownDefaults.style]
 * and override per call-site when a screen needs different sizing.
 */
@Immutable
data class MarkdownStyle(
    val textStyle: TextStyle,
    val textColor: Color,
    val h1: TextStyle,
    val h2: TextStyle,
    val h3: TextStyle,
    val h4: TextStyle,
    val h5: TextStyle,
    val h6: TextStyle,
    val headingColor: Color,
    val linkColor: Color,
    val codeStyle: TextStyle,
    val codeBackground: Color,
    val blockQuoteAccent: Color,
    val blockQuoteBackground: Color,
    val tableHeaderBackground: Color,
    val tableBorderColor: Color,
    val tableCellPadding: PaddingValues,
    val listIndent: Dp,
    val blockSpacing: Dp,
)

object MarkdownDefaults {

    @Composable
    fun style(
        textStyle: TextStyle = MaterialTheme.typography.bodyMedium,
        textColor: Color = Color(0xFF344054),
        h1: TextStyle = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.Bold),
        h2: TextStyle = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
        h3: TextStyle = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
        h4: TextStyle = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
        h5: TextStyle = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.Bold),
        h6: TextStyle = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold),
        headingColor: Color = SpiceNavy,
        linkColor: Color = SpiceBlue,
        codeStyle: TextStyle = textStyle.copy(fontFamily = FontFamily.Monospace),
        codeBackground: Color = Color(0xFFF2F4F7),
        blockQuoteAccent: Color = SpiceBlue,
        blockQuoteBackground: Color = SpiceBlueContainer.copy(alpha = 0.4f),
        tableHeaderBackground: Color = SpiceBlueContainer,
        tableBorderColor: Color = Color(0xFFD0D5DD),
        tableCellPadding: PaddingValues = PaddingValues(horizontal = 12.dp, vertical = 10.dp),
        listIndent: Dp = 20.dp,
        blockSpacing: Dp = 12.dp,
    ): MarkdownStyle = MarkdownStyle(
        textStyle = textStyle.copy(color = textColor),
        textColor = textColor,
        h1 = h1, h2 = h2, h3 = h3, h4 = h4, h5 = h5, h6 = h6,
        headingColor = headingColor,
        linkColor = linkColor,
        codeStyle = codeStyle,
        codeBackground = codeBackground,
        blockQuoteAccent = blockQuoteAccent,
        blockQuoteBackground = blockQuoteBackground,
        tableHeaderBackground = tableHeaderBackground,
        tableBorderColor = tableBorderColor,
        tableCellPadding = tableCellPadding,
        listIndent = listIndent,
        blockSpacing = blockSpacing,
    )
}
