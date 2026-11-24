@file:OptIn(ExperimentalMaterial3ExpressiveApi::class)

package com.fworld.fmessage.ui

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.fworld.fmessage.data.Rec
import com.fworld.fmessage.data.b
import com.fworld.fmessage.data.s

fun colorOf(hex: String?, fallback: Color = Color(0xFF6366F1)): Color = try {
    if (hex.isNullOrBlank()) fallback else Color(android.graphics.Color.parseColor(hex))
} catch (e: Exception) { fallback }

private fun seedScheme(seed: Color, dark: Boolean): ColorScheme {
    val white = Color.White
    val black = Color.Black
    return if (!dark) {
        lightColorScheme(
            primary = seed,
            onPrimary = white,
            primaryContainer = lerp(seed, white, 0.82f),
            onPrimaryContainer = lerp(seed, black, 0.62f),
            secondary = lerp(seed, Color(0xFF6B7280), 0.55f),
            onSecondary = white,
            secondaryContainer = lerp(seed, white, 0.88f),
            onSecondaryContainer = lerp(seed, black, 0.65f),
            tertiary = lerp(seed, Color(0xFFEC4899), 0.55f),
            onTertiary = white,
            tertiaryContainer = lerp(lerp(seed, Color(0xFFEC4899), 0.55f), white, 0.82f),
            onTertiaryContainer = lerp(seed, black, 0.7f),
            background = lerp(white, seed, 0.025f),
            onBackground = Color(0xFF1B1B1F),
            surface = lerp(white, seed, 0.025f),
            onSurface = Color(0xFF1B1B1F),
            surfaceVariant = lerp(white, seed, 0.1f),
            onSurfaceVariant = Color(0xFF46464F),
            outline = lerp(Color(0xFF777680), seed, 0.2f),
            outlineVariant = lerp(Color(0xFFC7C5D0), seed, 0.15f),
            surfaceContainerLowest = white,
            surfaceContainerLow = lerp(white, seed, 0.035f),
            surfaceContainer = lerp(white, seed, 0.055f),
            surfaceContainerHigh = lerp(white, seed, 0.08f),
            surfaceContainerHighest = lerp(white, seed, 0.11f),
        )
    } else {
        darkColorScheme(
            primary = lerp(seed, white, 0.45f),
            onPrimary = lerp(seed, black, 0.72f),
            primaryContainer = lerp(seed, black, 0.5f),
            onPrimaryContainer = lerp(seed, white, 0.85f),
            secondary = lerp(lerp(seed, Color(0xFF9CA3AF), 0.55f), white, 0.3f),
            onSecondary = lerp(seed, black, 0.8f),
            secondaryContainer = lerp(seed, black, 0.62f),
            onSecondaryContainer = lerp(seed, white, 0.85f),
            tertiary = lerp(lerp(seed, Color(0xFFEC4899), 0.55f), white, 0.4f),
            onTertiary = lerp(seed, black, 0.8f),
            tertiaryContainer = lerp(lerp(seed, Color(0xFFEC4899), 0.55f), black, 0.55f),
            onTertiaryContainer = lerp(seed, white, 0.85f),
            background = lerp(Color(0xFF121214), seed, 0.06f),
            onBackground = Color(0xFFE5E1E6),
            surface = lerp(Color(0xFF121214), seed, 0.06f),
            onSurface = Color(0xFFE5E1E6),
            surfaceVariant = lerp(Color(0xFF2A2A2F), seed, 0.12f),
            onSurfaceVariant = Color(0xFFC8C5D0),
            outline = lerp(Color(0xFF918F9A), seed, 0.2f),
            outlineVariant = lerp(Color(0xFF46464F), seed, 0.15f),
            surfaceContainerLowest = lerp(Color(0xFF0D0D0F), seed, 0.04f),
            surfaceContainerLow = lerp(Color(0xFF1A1A1D), seed, 0.07f),
            surfaceContainer = lerp(Color(0xFF1E1E22), seed, 0.09f),
            surfaceContainerHigh = lerp(Color(0xFF29292D), seed, 0.11f),
            surfaceContainerHighest = lerp(Color(0xFF343438), seed, 0.13f),
        )
    }
}

private fun scaledTypography(scale: Float, family: FontFamily): Typography {
    val b = Typography()
    fun TextStyle.s(): TextStyle = copy(fontSize = fontSize * scale, lineHeight = lineHeight * scale, fontFamily = family)
    return b.copy(
        displayLarge = b.displayLarge.s(), displayMedium = b.displayMedium.s(), displaySmall = b.displaySmall.s(),
        headlineLarge = b.headlineLarge.s(), headlineMedium = b.headlineMedium.s(), headlineSmall = b.headlineSmall.s(),
        titleLarge = b.titleLarge.s(), titleMedium = b.titleMedium.s(), titleSmall = b.titleSmall.s(),
        bodyLarge = b.bodyLarge.s(), bodyMedium = b.bodyMedium.s(), bodySmall = b.bodySmall.s(),
        labelLarge = b.labelLarge.s(), labelMedium = b.labelMedium.s(), labelSmall = b.labelSmall.s(),
    )
}

@Composable
fun FTheme(user: Rec?, themeMode: String, content: @Composable () -> Unit) {
    val dark = when (themeMode) {
        "dark" -> true
        "light" -> false
        else -> isSystemInDarkTheme()
    }
    val ctx = LocalContext.current
    val m3 = user?.s("design_style") == "material3"
    val accent = colorOf(user?.s("accent_color"))
    var scheme = if (m3 && Build.VERSION.SDK_INT >= 31) {
        if (dark) dynamicDarkColorScheme(ctx) else dynamicLightColorScheme(ctx)
    } else {
        seedScheme(if (m3) Color(0xFF6750A4) else accent, dark)
    }
    if (dark && user?.b("dark_mode_oled") == true) {
        scheme = scheme.copy(
            background = Color.Black, surface = Color.Black,
            surfaceContainerLowest = Color.Black,
            surfaceContainerLow = Color(0xFF0B0B0C), surfaceContainer = Color(0xFF111113),
        )
    }
    if (user?.b("high_contrast") == true) {
        scheme = scheme.copy(
            onSurface = if (dark) Color.White else Color.Black,
            onBackground = if (dark) Color.White else Color.Black,
            outline = if (dark) Color.White else Color.Black,
        )
    }
    val size = when (user?.s("font_size")) { "small" -> 13f; "large" -> 16f; "xl" -> 18f; else -> 14f }
    val family = when (user?.s("font_family")) {
        "serif" -> FontFamily.Serif
        "mono" -> FontFamily.Monospace
        "rounded" -> FontFamily.SansSerif
        else -> FontFamily.Default
    }
    val shapes = Shapes(
        extraSmall = RoundedCornerShape(8.dp),
        small = RoundedCornerShape(14.dp),
        medium = RoundedCornerShape(20.dp),
        large = RoundedCornerShape(28.dp),
        extraLarge = RoundedCornerShape(36.dp),
    )
    MaterialExpressiveTheme(
        colorScheme = scheme,
        motionScheme = MotionScheme.expressive(),
        shapes = shapes,
        typography = scaledTypography(size / 14f, family),
        content = content,
    )
}
