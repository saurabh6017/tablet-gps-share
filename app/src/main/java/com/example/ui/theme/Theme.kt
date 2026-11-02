package com.example.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val TacticalDarkColorScheme = darkColorScheme(
    primary = RadarCyan,
    onPrimary = DeepSpaceNavy,
    primaryContainer = RadarCyanDim,
    onPrimaryContainer = HudTextPrimary,
    secondary = SignalEmerald,
    onSecondary = DeepSpaceNavy,
    secondaryContainer = SignalEmeraldContainer,
    onSecondaryContainer = SignalEmerald,
    tertiary = BeaconAmber,
    onTertiary = DeepSpaceNavy,
    tertiaryContainer = BeaconAmberContainer,
    onTertiaryContainer = BeaconAmber,
    error = AlertCrimson,
    onError = Color.White,
    errorContainer = AlertCrimsonContainer,
    onErrorContainer = AlertCrimson,
    background = DeepSpaceNavy,
    onBackground = HudTextPrimary,
    surface = ObsidianSurface,
    onSurface = HudTextPrimary,
    surfaceVariant = SlateCardSurface,
    onSurfaceVariant = HudTextSecondary,
    surfaceContainerHigh = ElevatedHudSurface,
    outline = HudBorderSubtle
)

private val DaylightColorScheme = lightColorScheme(
    primary = DaylightPrimary,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFE0F2FE),
    onPrimaryContainer = Color(0xFF0369A1),
    secondary = DaylightSecondary,
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFD1FAE5),
    onSecondaryContainer = Color(0xFF065F46),
    tertiary = Color(0xFFD97706),
    onTertiary = Color.White,
    error = AlertCrimson,
    background = DaylightBackground,
    onBackground = DaylightTextPrimary,
    surface = DaylightSurface,
    onSurface = DaylightTextPrimary,
    surfaceVariant = DaylightCard,
    onSurfaceVariant = DaylightTextSecondary,
    outline = Color(0xFFCBD5E1)
)

@Composable
fun MyApplicationTheme(
    darkTheme: Boolean = true,
    content: @Composable () -> Unit
) {
    val colorScheme = if (darkTheme) TacticalDarkColorScheme else DaylightColorScheme

    MaterialTheme(
        colorScheme = colorScheme,
        typography = Typography,
        content = content
    )
}
