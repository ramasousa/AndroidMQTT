package com.raulsousa.pulso.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.DoorFront
import androidx.compose.material.icons.filled.Lightbulb
import androidx.compose.material.icons.filled.Power
import androidx.compose.material.icons.filled.Thermostat
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.Canvas
import com.raulsousa.pulso.domain.ConnectionState
import com.raulsousa.pulso.domain.Device
import com.raulsousa.pulso.domain.DeviceKind
import com.raulsousa.pulso.domain.DeviceState
import com.raulsousa.pulso.domain.DeviceTopics
import com.raulsousa.pulso.domain.DeviceId
import com.raulsousa.pulso.domain.Power
import com.raulsousa.pulso.domain.Sample
import com.raulsousa.pulso.ui.theme.MonoStyle
import com.raulsousa.pulso.ui.theme.PulsoTheme

/**
 * Faixa de estado da conexão.
 *
 * No app de 2017 este componente não existia — `connectionLost()` era um método
 * vazio e o usuário descobria que estava offline pelo silêncio. Estado de
 * conexão é a informação mais importante de um app de tempo real e por isso
 * mora no topo, sempre visível.
 */
@Composable
fun ConnectionPill(
    state: ConnectionState,
    demoMode: Boolean,
    modifier: Modifier = Modifier,
) {
    val (label, tone) = when {
        demoMode -> "Modo demonstração" to MaterialTheme.colorScheme.tertiary
        state is ConnectionState.Connected -> state.brokerLabel to MaterialTheme.colorScheme.primary
        state is ConnectionState.Connecting -> "Conectando…" to MaterialTheme.colorScheme.secondary
        state is ConnectionState.Reconnecting ->
            "Reconectando (${state.attempt})…" to MaterialTheme.colorScheme.secondary

        state is ConnectionState.Failed -> state.reason to MaterialTheme.colorScheme.error
        else -> "Desconectado" to MaterialTheme.colorScheme.outline
    }
    val color by animateColorAsState(tone, label = "connection-tone")

    Row(
        modifier = modifier
            .clip(RoundedCornerShape(50))
            .background(color.copy(alpha = 0.12f))
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Box(
            Modifier
                .size(8.dp)
                .clip(CircleShape)
                .background(color),
        )
        Text(
            text = label,
            style = MaterialTheme.typography.labelLarge,
            color = color,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/**
 * Cartão de dispositivo.
 *
 * Um cartão serve para qualquer tipo porque o tipo é dado, não código: lâmpada
 * e tomada ganham um interruptor, sensor ganha o valor grande e uma sparkline,
 * sensor binário ganha só o estado.
 */
@Composable
fun DeviceCard(
    device: Device,
    samples: List<Sample>,
    onToggle: () -> Unit,
    onOpen: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val enabled = device.state.online
    Card(
        modifier = modifier
            .fillMaxWidth()
            .clickable(onClick = onOpen),
        colors = CardDefaults.cardColors(
            containerColor = if (device.state.power == Power.ON && device.kind.isActuator) {
                MaterialTheme.colorScheme.primaryContainer
            } else {
                MaterialTheme.colorScheme.surfaceVariant
            },
        ),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = device.icon(),
                    contentDescription = null,
                    tint = if (enabled) {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    } else {
                        MaterialTheme.colorScheme.outline
                    },
                )
                Text(
                    text = device.name,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier
                        .weight(1f)
                        .padding(start = 8.dp),
                )
                if (device.isControllable) {
                    Switch(
                        checked = device.state.power == Power.ON,
                        onCheckedChange = { onToggle() },
                        enabled = enabled,
                    )
                }
            }

            device.room?.let {
                Text(it, style = MaterialTheme.typography.labelSmall)
            }

            Text(
                text = device.displayValue(),
                style = MaterialTheme.typography.headlineSmall,
                color = if (enabled) {
                    MaterialTheme.colorScheme.onSurface
                } else {
                    MaterialTheme.colorScheme.outline
                },
            )

            if (device.kind == DeviceKind.SENSOR && samples.size > 1) {
                Sparkline(
                    samples = samples,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(44.dp),
                )
            }
        }
    }
}

/**
 * Gráfico de telemetria desenhado à mão no Canvas.
 *
 * Sem biblioteca de gráficos: são 40 linhas, o app não ganha 2 MB e a curva faz
 * exatamente o que precisa — normalizar a janela visível e destacar o último
 * ponto. Bibliotecas de chart entram quando houver eixo, zoom e legenda.
 */
@Composable
fun Sparkline(
    samples: List<Sample>,
    modifier: Modifier = Modifier,
    color: Color = MaterialTheme.colorScheme.primary,
) {
    val progress by animateFloatAsState(
        targetValue = 1f,
        animationSpec = tween(durationMillis = 400),
        label = "sparkline",
    )

    Canvas(modifier) {
        if (samples.size < 2) return@Canvas

        val values = samples.map { it.value }
        val min = values.min()
        val max = values.max()
        // Série constante não tem amplitude: desenha no meio em vez de dividir por zero.
        val span = (max - min).takeIf { it > 0.0001 } ?: 1.0
        val stepX = size.width / (samples.size - 1)

        val path = Path()
        samples.forEachIndexed { index, sample ->
            val x = index * stepX
            val normalized = ((sample.value - min) / span).toFloat()
            val y = size.height - (normalized * size.height * 0.9f) - size.height * 0.05f
            if (index == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }

        drawPath(
            path = path,
            brush = Brush.horizontalGradient(listOf(color.copy(alpha = 0.35f), color)),
            style = Stroke(width = 2.5f * progress + 0.5f, cap = StrokeCap.Round),
        )

        val lastNormalized = ((values.last() - min) / span).toFloat()
        val lastY = size.height - (lastNormalized * size.height * 0.9f) - size.height * 0.05f
        drawCircle(color = color, radius = 3.5f, center = Offset(size.width, lastY))
    }
}

/** Linha monoespaçada de tópico + payload, usada no inspetor e no detalhe. */
@Composable
fun TopicLine(
    topic: String,
    payload: String,
    modifier: Modifier = Modifier,
    accent: Color = MaterialTheme.colorScheme.primary,
) {
    Column(modifier) {
        Text(text = topic, style = MonoStyle, color = accent, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(
            text = payload,
            style = MonoStyle,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 3,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

private fun Device.icon(): ImageVector = when {
    !state.online -> Icons.Filled.CloudOff
    kind == DeviceKind.LIGHT -> Icons.Filled.Lightbulb
    kind == DeviceKind.SWITCH -> Icons.Filled.Power
    kind == DeviceKind.SENSOR -> Icons.Filled.Thermostat
    kind == DeviceKind.BINARY_SENSOR -> Icons.Filled.DoorFront
    else -> Icons.Filled.Bolt
}

@Preview(showBackground = true)
@Composable
private fun DeviceCardPreview() {
    PulsoTheme {
        Column(
            Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            DeviceCard(
                device = Device(
                    id = DeviceId("lampada"),
                    name = "Lâmpada da sala",
                    kind = DeviceKind.LIGHT,
                    room = "Sala",
                    topics = DeviceTopics(state = "casa/sala/lampada/estado", command = "casa/sala/lampada/set"),
                    state = DeviceState(power = Power.ON),
                ),
                samples = emptyList(),
                onToggle = {},
                onOpen = {},
            )
            DeviceCard(
                device = Device(
                    id = DeviceId("temp"),
                    name = "Temperatura",
                    kind = DeviceKind.SENSOR,
                    room = "Sala",
                    unit = "°C",
                    topics = DeviceTopics(state = "casa/sala/clima"),
                    state = DeviceState(numeric = 23.4),
                ),
                samples = List(24) { Sample(it * 1_000L, 20.0 + it % 7) },
                onToggle = {},
                onOpen = {},
            )
        }
    }
}
