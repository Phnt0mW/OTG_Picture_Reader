package com.example.otg_picture_reader.ui.theme

import android.app.Activity
import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

private val DarkColorScheme = darkColorScheme(
    primary = TealMist90,
    secondary = OceanAccent90,
    tertiary = DeepSlate90,
    background = DeepSlate10,
    surface = Color(0xFF0F1D26),
    surfaceVariant = Color(0xFF172933)
)

private val LightColorScheme = lightColorScheme(
    primary = TealMist10,
    secondary = OceanAccent10,
    tertiary = DeepSlate10,
    background = Color(0xFFF7FAFB),
    surface = Color(0xFFF3F7F9),
    surfaceVariant = Color(0xFFE4EDF1)
)

@Composable
fun OTG_Picture_ReaderTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    // Dynamic color is available on Android 12+
    dynamicColor: Boolean = true,
    content: @Composable () -> Unit
) {
    val colorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val context = LocalContext.current
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }

        darkTheme -> DarkColorScheme
        else -> LightColorScheme
    }

    MaterialTheme(
        colorScheme = colorScheme,
        shapes = AppShapes,
        typography = Typography,
        content = content
    )
}
