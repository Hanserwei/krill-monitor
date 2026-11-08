package com.hanserwei.krill_monitor

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight

internal fun monitorColors(dark: Boolean): ColorScheme =
    if (dark) {
        darkColorScheme(
            primary = Color(0xFF62DFC9),
            onPrimary = Color(0xFF00382E),
            primaryContainer = Color(0xFF144D46),
            onPrimaryContainer = Color(0xFFB3F4E6),
            secondary = Color(0xFF91BCF2),
            secondaryContainer = Color(0xFF233D58),
            onSecondaryContainer = Color(0xFFD9E8FF),
            tertiary = Color(0xFFFFAC8F),
            background = Color(0xFF0C1920),
            onBackground = Color(0xFFE2ECEF),
            surface = Color(0xFF13262F),
            onSurface = Color(0xFFE2ECEF),
            surfaceVariant = Color(0xFF233B43),
            onSurfaceVariant = Color(0xFFA7BDC4),
            surfaceContainer = Color(0xFF193039),
            surfaceContainerLow = Color(0xFF10232B),
            surfaceContainerHigh = Color(0xFF203A44),
            surfaceContainerHighest = Color(0xFF29434C),
            outline = Color(0xFF6D8A94),
            outlineVariant = Color(0xFF2B4650),
            error = Color(0xFFFFB3A9),
            errorContainer = Color(0xFF512E2D),
            onErrorContainer = Color(0xFFFFDAD4),
        )
    } else {
        lightColorScheme(
            primary = Color(0xFF007E70),
            onPrimary = Color.White,
            primaryContainer = Color(0xFFD2F5EB),
            onPrimaryContainer = Color(0xFF08483F),
            secondary = Color(0xFF3D6D9B),
            secondaryContainer = Color(0xFFE4EFFA),
            onSecondaryContainer = Color(0xFF244B70),
            tertiary = Color(0xFFB85836),
            background = Color(0xFFF2F6F8),
            onBackground = Color(0xFF142F3B),
            surface = Color.White,
            onSurface = Color(0xFF142F3B),
            surfaceVariant = Color(0xFFE9F0F3),
            onSurfaceVariant = Color(0xFF607780),
            surfaceContainer = Color(0xFFEAF3F4),
            surfaceContainerLow = Color(0xFFF6F9FA),
            surfaceContainerHigh = Color(0xFFE3EEF1),
            surfaceContainerHighest = Color(0xFFDCE8EC),
            outline = Color(0xFF8FA6AE),
            outlineVariant = Color(0xFFDBE6EA),
            error = Color(0xFFB63A35),
            errorContainer = Color(0xFFFFEDE8),
            onErrorContainer = Color(0xFF852C28),
        )
    }

internal fun monitorTypography(font: FontFamily): Typography {
    val base = Typography(fontFamily = font)
    return base.copy(
        headlineLarge = base.headlineLarge.copy(fontWeight = FontWeight.Bold),
        headlineSmall = base.headlineSmall.copy(fontWeight = FontWeight.SemiBold),
        titleLarge = base.titleLarge.copy(fontWeight = FontWeight.SemiBold),
        titleMedium = base.titleMedium.copy(fontWeight = FontWeight.SemiBold),
    )
}
