package com.raulsousa.pulso.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/**
 * Tipografia do sistema, com dois ajustes deliberados: números de telemetria em
 * peso médio e tabular, e tópicos MQTT em monoespaçado. Tópico é endereço — ler
 * `casa/sala/lampada/estado` numa fonte proporcional dá trabalho à toa.
 */
val PulsoTypography: Typography = Typography().let { base ->
    base.copy(
        headlineMedium = base.headlineMedium.copy(fontWeight = FontWeight.SemiBold),
        titleLarge = base.titleLarge.copy(fontWeight = FontWeight.SemiBold),
        labelSmall = base.labelSmall.copy(letterSpacing = 0.6.sp),
    )
}

/** Estilo dos tópicos e payloads no inspetor. */
val MonoStyle: TextStyle = TextStyle(
    fontFamily = FontFamily.Monospace,
    fontSize = 12.sp,
    lineHeight = 16.sp,
)

/** Números grandes de telemetria. */
val MetricStyle: TextStyle = TextStyle(
    fontSize = 34.sp,
    fontWeight = FontWeight.SemiBold,
)
