package com.example.open_script.ui.theme

import android.app.Activity
import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext

private val DarkColorScheme = darkColorScheme(
    primary = androidx.compose.ui.graphics.Color(0xFFF34D4D),
    onPrimary = androidx.compose.ui.graphics.Color.White,
    primaryContainer = androidx.compose.ui.graphics.Color(0xFF7B292F),
    onPrimaryContainer = androidx.compose.ui.graphics.Color.White,
    secondary = androidx.compose.ui.graphics.Color(0xFFE3E9F3),
    secondaryContainer = androidx.compose.ui.graphics.Color(0xFF26344C),
    onSecondaryContainer = androidx.compose.ui.graphics.Color(0xFFF8FAFC),
    background = androidx.compose.ui.graphics.Color(0xFF0F172A),
    onBackground = androidx.compose.ui.graphics.Color(0xFFF8FAFC),
    surface = androidx.compose.ui.graphics.Color(0xFF162036),
    onSurface = androidx.compose.ui.graphics.Color(0xFFF8FAFC),
    surfaceVariant = androidx.compose.ui.graphics.Color(0xFF202D45),
    surfaceTint = androidx.compose.ui.graphics.Color(0xFF7992B5),
    surfaceDim = androidx.compose.ui.graphics.Color(0xFF0F172A),
    surfaceBright = androidx.compose.ui.graphics.Color(0xFF26344C),
    surfaceContainerLowest = androidx.compose.ui.graphics.Color(0xFF0B1220),
    surfaceContainerLow = androidx.compose.ui.graphics.Color(0xFF131D30),
    surfaceContainer = androidx.compose.ui.graphics.Color(0xFF162036),
    surfaceContainerHigh = androidx.compose.ui.graphics.Color(0xFF1D2A41),
    surfaceContainerHighest = androidx.compose.ui.graphics.Color(0xFF26344C),
    onSurfaceVariant = androidx.compose.ui.graphics.Color(0xFFB6C2D5),
    outline = androidx.compose.ui.graphics.Color(0xFF3A4961),
    error = androidx.compose.ui.graphics.Color(0xFFFFB4AB)
)

private val LightColorScheme = lightColorScheme(
    primary = Purple40,
    secondary = PurpleGrey40,
    tertiary = Pink40

    /* Other default colors to override
    background = Color(0xFFFFFBFE),
    surface = Color(0xFFFFFBFE),
    onPrimary = Color.White,
    onSecondary = Color.White,
    onTertiary = Color.White,
    onBackground = Color(0xFF1C1B1F),
    onSurface = Color(0xFF1C1B1F),
    */
)

@Composable
fun Open_scriptTheme(
    darkTheme: Boolean = true,
    // Dynamic color is available on Android 12+
    dynamicColor: Boolean = false,
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
        typography = Typography,
        content = content
    )
}
