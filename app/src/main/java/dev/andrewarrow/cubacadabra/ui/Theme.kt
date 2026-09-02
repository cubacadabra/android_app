package dev.andrewarrow.cubacadabra.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val DarkColors = darkColorScheme(
    primary = Color(0xFF9AB9BE),
    onPrimary = Color(0xFF173F43),
    background = Color(0xFF102326),
    surface = Color(0xFF102326),
    onBackground = Color.White,
    onSurface = Color.White,
)

private val LightColors = lightColorScheme(
    primary = Color(0xFF2F686A),
    onPrimary = Color.White,
    background = Color(0xFFF6F1E7),
    surface = Color(0xFFF6F1E7),
    onBackground = Color(0xFF173F43),
    onSurface = Color(0xFF173F43),
)

@Composable
fun CubacadabraTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = if (isSystemInDarkTheme()) DarkColors else LightColors, content = content)
}
