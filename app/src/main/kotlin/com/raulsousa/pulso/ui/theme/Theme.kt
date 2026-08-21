package com.raulsousa.pulso.ui.theme

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

/**
 * Paleta própria: azul de sinal, âmbar de energia, rosa de alerta.
 *
 * Serve de fallback onde não há cor dinâmica (Android 11 e anteriores) e como
 * identidade da marca. Onde há Material You, a cor do papel de parede vence —
 * a casa do usuário deve parecer dele, não minha.
 */
private val Signal = Color(0xFF38BDF8)
private val SignalDark = Color(0xFF0284C7)
private val Energy = Color(0xFFFBBF24)
private val Alert = Color(0xFFF472B6)
private val Ink = Color(0xFF0B1120)
private val Paper = Color(0xFFF8FAFC)

private val DarkScheme = darkColorScheme(
    primary = Signal,
    onPrimary = Ink,
    secondary = Energy,
    onSecondary = Ink,
    tertiary = Alert,
    background = Ink,
    surface = Color(0xFF111827),
    surfaceVariant = Color(0xFF1F2937),
    error = Color(0xFFFB7185),
)

private val LightScheme = lightColorScheme(
    primary = SignalDark,
    onPrimary = Color.White,
    secondary = Color(0xFFB45309),
    tertiary = Color(0xFFBE185D),
    background = Paper,
    surface = Color.White,
    surfaceVariant = Color(0xFFE2E8F0),
    error = Color(0xFFB91C1C),
)

@Composable
fun PulsoTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = true,
    content: @Composable () -> Unit,
) {
    val context = LocalContext.current
    val colorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ->
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)

        darkTheme -> DarkScheme
        else -> LightScheme
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = PulsoTypography,
        content = content,
    )
}
