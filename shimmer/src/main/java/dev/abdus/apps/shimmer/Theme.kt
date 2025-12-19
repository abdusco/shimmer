package dev.abdus.apps.shimmer

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

// Palette Colors - Change these to update the entire theme
private val ColorVeryDarkBlue = Color(0xFF0D1B2A)
private val ColorDarkBlueGray = Color(0xFF1B263B)
private val ColorMediumBlueGray = Color(0xFF415A77)
private val ColorLightBlueGray = Color(0xFF778DA9)
private val ColorVeryLightGray = Color(0xFFE0E1DD)

// Lighter shades for better icon contrast
private val ColorIconLight = Color(0xFF8da9c4) // Lighter blue-gray for icons
private val ColorPrimaryLight = Color(0xFF5A7A9A) // Lighter primary for better visibility

// Standard Colors
private val ColorWhite = Color(0xFFFFFFFF)
private val ColorBlack = Color(0xFF000000)
private val ColorError = Color(0xFFCF6679)
private val ColorErrorDark = Color(0xFFB00020)
private val ColorErrorContainer = Color(0xFFFFDAD6)
private val ColorErrorOnContainer = Color(0xFF410002)
private val ColorLightGray = Color(0xFFF5F5F5)
private val ColorOutlineVariantLight = Color(0xFFD0D0D0)

private val DarkColorScheme = darkColorScheme(
    primary = ColorPrimaryLight, // Lighter for better icon contrast
    onPrimary = ColorWhite,
    primaryContainer = ColorLightBlueGray,
    onPrimaryContainer = ColorVeryDarkBlue,
    secondary = ColorIconLight, // Lighter for better contrast
    onSecondary = ColorVeryDarkBlue,
    secondaryContainer = ColorMediumBlueGray,
    onSecondaryContainer = ColorVeryLightGray,
    tertiary = ColorIconLight, // Lighter for better contrast
    onTertiary = ColorVeryDarkBlue,
    error = ColorError,
    onError = ColorBlack,
    errorContainer = ColorErrorDark,
    onErrorContainer = ColorErrorContainer,
    background = ColorVeryDarkBlue,
    onBackground = ColorVeryLightGray,
    surface = ColorDarkBlueGray,
    onSurface = ColorVeryLightGray,
    surfaceVariant = ColorDarkBlueGray,
    onSurfaceVariant = ColorIconLight, // Lighter for better icon contrast
    outline = ColorIconLight, // Lighter for better visibility
    outlineVariant = ColorDarkBlueGray,
    scrim = ColorBlack,
    inverseSurface = ColorVeryLightGray,
    inverseOnSurface = ColorVeryDarkBlue,
    inversePrimary = ColorPrimaryLight,
    surfaceTint = ColorPrimaryLight,
)

private val LightColorScheme = lightColorScheme(
    primary = ColorMediumBlueGray,
    onPrimary = ColorWhite,
    primaryContainer = ColorLightBlueGray,
    onPrimaryContainer = ColorVeryDarkBlue,
    secondary = ColorLightBlueGray,
    onSecondary = ColorWhite,
    secondaryContainer = ColorVeryLightGray,
    onSecondaryContainer = ColorDarkBlueGray,
    tertiary = ColorLightBlueGray,
    onTertiary = ColorWhite,
    error = ColorErrorDark,
    onError = ColorWhite,
    errorContainer = ColorErrorContainer,
    onErrorContainer = ColorErrorOnContainer,
    background = ColorVeryLightGray,
    onBackground = ColorVeryDarkBlue,
    surface = ColorWhite,
    onSurface = ColorVeryDarkBlue,
    surfaceVariant = ColorLightGray,
    onSurfaceVariant = ColorMediumBlueGray,
    outline = ColorMediumBlueGray,
    outlineVariant = ColorOutlineVariantLight,
    scrim = ColorBlack,
    inverseSurface = ColorVeryDarkBlue,
    inverseOnSurface = ColorVeryLightGray,
    inversePrimary = ColorLightBlueGray,
    surfaceTint = ColorMediumBlueGray,
)

@Composable
fun ShimmerTheme(
    darkTheme: Boolean = true, // Default to dark theme
    content: @Composable () -> Unit
) {
    val colorScheme = if (darkTheme) {
        DarkColorScheme
    } else {
        LightColorScheme
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = MaterialTheme.typography,
        content = content
    )
}

