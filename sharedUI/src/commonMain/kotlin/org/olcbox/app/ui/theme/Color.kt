package org.olcbox.app.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.ui.graphics.Color

// Тема AKULA VPN — та же «подводная», что у сайта vpn-панели и консоли сервера
// (vpn-panel/frontend/src/styles.css): тёмная, бирюзовый акцент. Осознанно одна, без светлой и
// без «динамических цветов» Android — своё лицо вместо подкраски под обои (Павел 16.10.2026: «и стиль»).
internal val OlcboxDarkColorScheme: ColorScheme = darkColorScheme(
    primary = Color(0xFF12E0C4),
    onPrimary = Color(0xFF012420),
    primaryContainer = Color(0xFF0C2B28),
    onPrimaryContainer = Color(0xFF3AEED6),
    inversePrimary = Color(0xFF0A8A78),
    secondary = Color(0xFF3AEED6),
    onSecondary = Color(0xFF012420),
    secondaryContainer = Color(0xFF131E26),
    onSecondaryContainer = Color(0xFFE4EEF2),
    tertiary = Color(0xFFFFB84D),
    onTertiary = Color(0xFF2B1A00),
    tertiaryContainer = Color(0xFF3A2A10),
    onTertiaryContainer = Color(0xFFFFD596),
    background = Color(0xFF070C10),
    onBackground = Color(0xFFE4EEF2),
    surface = Color(0xFF070C10),
    onSurface = Color(0xFFE4EEF2),
    surfaceVariant = Color(0xFF131E26),
    onSurfaceVariant = Color(0xFF7C96A3),
    surfaceTint = Color(0xFF12E0C4),
    surfaceContainerLowest = Color(0xFF04070A),
    surfaceContainerLow = Color(0xFF0B1217),
    surfaceContainer = Color(0xFF0E161C),
    surfaceContainerHigh = Color(0xFF131E26),
    surfaceContainerHighest = Color(0xFF172531),
    inverseSurface = Color(0xFFE4EEF2),
    inverseOnSurface = Color(0xFF0E161C),
    outline = Color(0xFF2C4552),
    outlineVariant = Color(0xFF1F313D),
    error = Color(0xFFFF5D5D),
    onError = Color(0xFF2E1315),
    errorContainer = Color(0xFF2E1315),
    onErrorContainer = Color(0xFFFF8585),
    scrim = Color(0xFF04070A)
)

// Светлой темы у AKULA нет — переключатель и система получают ту же тёмную
internal val OlcboxLightColorScheme: ColorScheme = OlcboxDarkColorScheme
