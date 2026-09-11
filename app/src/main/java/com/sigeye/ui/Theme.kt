package com.sigeye.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.graphics.Color

private val Green = Color(0xFF2E7D57)
private val GreenLight = Color(0xFF7FE3A3)

private val LightColors = lightColorScheme(
    primary = Green,
    tertiary = Color(0xFF7A5AA8),
)

private val DarkColors = darkColorScheme(
    primary = GreenLight,
    tertiary = Color(0xFFC4A9F0),
)

@Composable
fun SigEyeTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = if (isSystemInDarkTheme()) DarkColors else LightColors) {
        Surface(modifier = Modifier.fillMaxSize()) { content() }
    }
}
