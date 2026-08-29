package io.github.jmallus.guidage.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val Accent = Color(0xFF1565C0)
private val AccentLight = Color(0xFF64B5F6)

@Composable
fun GuidageTheme(content: @Composable () -> Unit) {
    val colors = if (isSystemInDarkTheme()) {
        darkColorScheme(primary = AccentLight, secondary = AccentLight)
    } else {
        lightColorScheme(primary = Accent, secondary = Accent)
    }
    MaterialTheme(colorScheme = colors, content = content)
}
