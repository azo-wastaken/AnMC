
package com.anmc.net

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable

private val AnmcDarkColors = darkColorScheme(
    primary = androidx.compose.ui.graphics.Color(0xFFC8D841),
    onPrimary = androidx.compose.ui.graphics.Color(0xFF14180E),
    secondary = androidx.compose.ui.graphics.Color(0xFFBDCA4A),
    onSecondary = androidx.compose.ui.graphics.Color(0xFF14180E),
    tertiary = androidx.compose.ui.graphics.Color(0xFFDFEBAE),
    background = androidx.compose.ui.graphics.Color(0xFF14180E),
    onBackground = androidx.compose.ui.graphics.Color(0xFFEAE9E7),
    surface = androidx.compose.ui.graphics.Color(0xFF14180E),
    onSurface = androidx.compose.ui.graphics.Color(0xFFEAE9E7),
    surfaceVariant = androidx.compose.ui.graphics.Color(0xFF292E20),
    onSurfaceVariant = androidx.compose.ui.graphics.Color(0xFFDFEBAE),
    outline = androidx.compose.ui.graphics.Color(0xFF7B8338)
)

private val AnmcLightColors = lightColorScheme(
    primary = androidx.compose.ui.graphics.Color(0xFF7B8338),
    onPrimary = androidx.compose.ui.graphics.Color(0xFFFFFFFF),
    secondary = androidx.compose.ui.graphics.Color(0xFFBDCA4A),
    onSecondary = androidx.compose.ui.graphics.Color(0xFF14180E),
    tertiary = androidx.compose.ui.graphics.Color(0xFFC8D841),
    background = androidx.compose.ui.graphics.Color(0xFFEAE9E7),
    onBackground = androidx.compose.ui.graphics.Color(0xFF14180E),
    surface = androidx.compose.ui.graphics.Color(0xFFF8F8F5),
    onSurface = androidx.compose.ui.graphics.Color(0xFF14180E),
    surfaceVariant = androidx.compose.ui.graphics.Color(0xFFDFEBAE),
    onSurfaceVariant = androidx.compose.ui.graphics.Color(0xFF454A2B),
    outline = androidx.compose.ui.graphics.Color(0xFF7B8338)
)

@Composable
fun ANMCTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    MaterialTheme(
        colorScheme = if (darkTheme) AnmcDarkColors else AnmcLightColors,
        content = content
    )
}
