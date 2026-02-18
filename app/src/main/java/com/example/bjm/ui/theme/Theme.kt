package com.example.bjm.ui.theme

import android.app.Activity
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

// 1. Define a vibrant, modern green color palette
val PrimaryGreen = Color(0xFF00C853)
val SecondaryGreen = Color(0xFF69F0AE)
val SurfaceGreen = Color(0xFF1B1B1B) // Slightly lighter than pure black
val BackgroundGreen = Color(0xFF121212)
val TextGreen = Color(0xFFE0E0E0)

// 2. Create the new Dark Green Color Scheme
private val GreenColorScheme = darkColorScheme(
    primary = PrimaryGreen,
    secondary = SecondaryGreen,
    background = BackgroundGreen,
    surface = SurfaceGreen,
    onPrimary = Color.Black,
    onSecondary = Color.Black,
    onBackground = TextGreen,
    onSurface = TextGreen,
    primaryContainer = PrimaryGreen.copy(alpha = 0.2f),
    surfaceVariant = SurfaceGreen.copy(alpha = 0.5f)
)

@Composable
fun BJMTheme(
    // Force dark theme for the consistent green look
    darkTheme: Boolean = true, 
    content: @Composable () -> Unit
) {
    // 3. Apply the new GreenColorScheme
    val colorScheme = GreenColorScheme
    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as Activity).window
            window.statusBarColor = colorScheme.background.toArgb()
            WindowCompat.getInsetsController(window, view).isAppearanceLightStatusBars = !darkTheme
        }
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = Typography,
        content = content
    )
}
