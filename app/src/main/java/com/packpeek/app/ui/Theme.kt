package com.packpeek.app.ui

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.packpeek.app.data.Settings

/** Accent seeds: a light-theme primary and a dark-theme primary per palette. */
private val accents =
    mapOf(
        "TEAL" to (Color(0xFF00696B) to Color(0xFF6FD7D8)),
        "BLUE" to (Color(0xFF205EAB) to Color(0xFFA6C8FF)),
        "PURPLE" to (Color(0xFF6840A3) to Color(0xFFD6B9FF)),
        "ORANGE" to (Color(0xFF8F4C00) to Color(0xFFFFB873)),
        "RED" to (Color(0xFFA63440) to Color(0xFFFFB2B9)),
        "GRAY" to (Color(0xFF465C68) to Color(0xFFADC9D9)),
    )

/**
 * Builds every Material 3 color role from one accent. Setting only `primary` leaves the rest on
 * the baseline purple palette, which made tonal buttons and containers clash with the accent.
 */
private fun lightScheme(p: Color): ColorScheme {
    val white = Color.White
    val ink = Color(0xFF191C1C)
    fun tint(amount: Float) = lerp(white, p, amount)
    return lightColorScheme(
        primary = p,
        onPrimary = white,
        primaryContainer = tint(0.18f),
        onPrimaryContainer = lerp(p, Color.Black, 0.55f),
        secondary = lerp(p, Color(0xFF5B6464), 0.55f),
        onSecondary = white,
        secondaryContainer = tint(0.12f),
        onSecondaryContainer = lerp(p, Color.Black, 0.6f),
        tertiary = Color(0xFF7A5900),
        onTertiary = white,
        tertiaryContainer = Color(0xFFFFDEA6),
        onTertiaryContainer = Color(0xFF261900),
        background = tint(0.015f),
        onBackground = ink,
        surface = tint(0.015f),
        onSurface = ink,
        surfaceVariant = tint(0.08f),
        onSurfaceVariant = Color(0xFF3F4948),
        surfaceTint = p,
        outline = Color(0xFF6F7979),
        outlineVariant = Color(0xFFBEC8C8),
        surfaceBright = tint(0.015f),
        surfaceDim = lerp(Color(0xFFD8DADA), p, 0.04f),
        surfaceContainerLowest = white,
        surfaceContainerLow = tint(0.035f),
        surfaceContainer = tint(0.055f),
        surfaceContainerHigh = tint(0.08f),
        surfaceContainerHighest = tint(0.11f),
    )
}

private fun darkScheme(seed: Color, p: Color): ColorScheme {
    val base = Color(0xFF101414)
    val ink = Color(0xFFE0E3E3)
    fun tint(level: Float, amount: Float = 0.06f) =
        lerp(lerp(base, Color.White, level), seed, amount)
    return darkColorScheme(
        primary = p,
        onPrimary = lerp(seed, Color.Black, 0.6f),
        primaryContainer = lerp(seed, Color.Black, 0.25f),
        onPrimaryContainer = lerp(p, Color.White, 0.6f),
        secondary = lerp(p, Color(0xFFBFC8C8), 0.5f),
        onSecondary = lerp(seed, Color.Black, 0.7f),
        secondaryContainer = lerp(seed, base, 0.7f),
        onSecondaryContainer = lerp(p, Color.White, 0.7f),
        tertiary = Color(0xFFEFBF48),
        onTertiary = Color(0xFF402D00),
        tertiaryContainer = Color(0xFF5C4300),
        onTertiaryContainer = Color(0xFFFFDEA6),
        background = tint(0f),
        onBackground = ink,
        surface = tint(0f),
        onSurface = ink,
        surfaceVariant = tint(0.12f),
        onSurfaceVariant = Color(0xFFBEC8C8),
        surfaceTint = p,
        outline = Color(0xFF889392),
        outlineVariant = Color(0xFF3F4948),
        surfaceBright = tint(0.14f),
        surfaceDim = tint(0f),
        surfaceContainerLowest = lerp(base, Color.Black, 0.3f),
        surfaceContainerLow = tint(0.035f),
        surfaceContainer = tint(0.055f),
        surfaceContainerHigh = tint(0.085f),
        surfaceContainerHighest = tint(0.115f),
    )
}

private val PackShapes =
    Shapes(
        extraSmall = RoundedCornerShape(8.dp),
        small = RoundedCornerShape(12.dp),
        medium = RoundedCornerShape(16.dp),
        large = RoundedCornerShape(24.dp),
        extraLarge = RoundedCornerShape(28.dp),
    )

@Composable
fun PackPeekTheme(settings: Settings?, content: @Composable () -> Unit) {
    val dark =
        when (settings?.theme) {
            "LIGHT" -> false
            "DARK" -> true
            else -> isSystemInDarkTheme()
        }
    val context = LocalContext.current
    val (light, night) = accents[settings?.accent] ?: accents.getValue("TEAL")
    val scheme =
        when {
            settings?.accent == "DYNAMIC" && Build.VERSION.SDK_INT >= 31 ->
                if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
            dark -> darkScheme(light, night)
            else -> lightScheme(light)
        }
    MaterialTheme(colorScheme = scheme, shapes = PackShapes, content = content)
}
