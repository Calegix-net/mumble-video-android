package com.calegix.mumblevideo.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import com.calegix.mumblevideo.data.settings.ThemeMode

private val LightColors = lightColorScheme(
    primary = Color(0xFF00696E),
    onPrimary = Color.White,
    primaryContainer = Color(0xFF9CF0F5),
    onPrimaryContainer = Color(0xFF002022),
    secondary = Color(0xFF4A6365),
    secondaryContainer = Color(0xFFCCE8E9),
    tertiary = Color(0xFF4F5F7D),
    tertiaryContainer = Color(0xFFD7E2FF),
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFF80D4D9),
    onPrimary = Color(0xFF00373A),
    primaryContainer = Color(0xFF004F53),
    onPrimaryContainer = Color(0xFF9CF0F5),
    secondary = Color(0xFFB0CCCD),
    secondaryContainer = Color(0xFF324B4D),
    tertiary = Color(0xFFB7C7EA),
    tertiaryContainer = Color(0xFF37475F),
)

/** Colours for talking indicators, which need to read the same on any scheme. */
object TalkColors {
    val Talking = Color(0xFF2EBD59)
    val Shouting = Color(0xFF3B82F6)
    val Whispering = Color(0xFFB45CF0)
}

/** True when the true-black OLED variant is active, for the few places that care. */
val LocalTrueBlack = staticCompositionLocalOf { false }

@Composable
fun MumbleVideoTheme(
    themeMode: ThemeMode = ThemeMode.System,
    trueBlack: Boolean = false,
    dynamicColor: Boolean = true,
    content: @Composable () -> Unit,
) {
    val dark = when (themeMode) {
        ThemeMode.System -> isSystemInDarkTheme()
        ThemeMode.Light -> false
        ThemeMode.Dark -> true
    }
    val context = LocalContext.current
    val base = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ->
            if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)

        dark -> DarkColors

        else -> LightColors
    }
    val pureBlack = dark && trueBlack
    val scheme = if (pureBlack) base.toTrueBlack() else base
    CompositionLocalProvider(LocalTrueBlack provides pureBlack) {
        MaterialTheme(colorScheme = scheme, content = content)
    }
}

/**
 * Pushes every large surface to black so OLED pixels are off, keeping only small raised
 * containers (cards, sheets, the nav bar) a hair above black so they stay distinguishable.
 */
private fun ColorScheme.toTrueBlack(): ColorScheme = copy(
    background = Color.Black,
    surface = Color.Black,
    surfaceDim = Color.Black,
    surfaceBright = Color(0xFF1A1A1A),
    surfaceContainerLowest = Color.Black,
    surfaceContainerLow = Color(0xFF050505),
    surfaceContainer = Color(0xFF0A0A0A),
    surfaceContainerHigh = Color(0xFF121212),
    surfaceContainerHighest = Color(0xFF1A1A1A),
    surfaceVariant = Color(0xFF121212),
    inverseOnSurface = Color.Black,
)
