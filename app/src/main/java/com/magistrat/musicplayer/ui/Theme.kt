package com.magistrat.musicplayer.ui

import android.os.Build
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

private val DarkColors = darkColorScheme(
    primary = Color(0xFFB69DF8),
    secondary = Color(0xFF9FD3C7),
    background = Color(0xFF121212),
    surface = Color(0xFF121212),
)

@Composable
fun MusicTheme(content: @Composable () -> Unit) {
    val colors = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) dynamicDarkColorScheme(LocalContext.current) else DarkColors
    MaterialTheme(colorScheme = colors, content = content)
}
