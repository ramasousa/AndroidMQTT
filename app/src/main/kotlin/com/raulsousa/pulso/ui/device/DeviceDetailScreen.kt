package com.raulsousa.pulso.ui.device

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.raulsousa.pulso.domain.CommandTranslator
import com.raulsousa.pulso.domain.Device
import com.raulsousa.pulso.domain.DeviceKind
import com.raulsousa.pulso.domain.Power
import com.raulsousa.pulso.domain.Sample
import com.raulsousa.pulso.ui.components.Sparkline
import com.raulsousa.pulso.ui.components.TopicLine
import com.raulsousa.pulso.ui.theme.MetricStyle

/**
 * Detalhe de um dispositivo: controle grande, telemetria e — o que nenhum app
 * comercial mostra — exatamente em que tópicos ele fala. Quando algo não
 * funciona, essa é a primeira informação de que se precisa.
 */
@Composable
fun DeviceDetailScreen(
    device: Device,
    samples: List<Sample>,
    onToggle: () -> Unit,
    onBrightness: (Int) -> Unit,
    contentPadding: PaddingValues,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(contentPadding)
            .padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(device.name, style = MaterialTheme.typography.headlineSmall)
        device.room?.let { Text(it, style = MaterialTheme.typography.labelLarge) }

        Card {
            Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Text(
                    text = device.displayValue(),
                    style = MetricStyle,
                    color = if (device.state.online) {
                        MaterialTheme.colorScheme.onSurface
                    } else {
                        MaterialTheme.colorScheme.error
                    },
                )

                if (device.isControllable) {
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text("Ligado", style = MaterialTheme.typography.bodyLarge)
                        Switch(
                            checked = device.state.power == Power.ON,
                            onCheckedChange = { onToggle() },
                            enabled = device.state.online,
                        )
                    }
                }

                if (device.supportsBrightness) {
                    BrightnessControl(device, onBrightness)
                }
            }
        }

        if (device.kind == DeviceKind.SENSOR && samples.size > 1) {
            Card {
                Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("Últimas leituras", style = MaterialTheme.typography.titleMedium)
                    Sparkline(
                        samples = samples,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(120.dp),
                    )
                    val min = samples.minOf { it.value }
                    val max = samples.maxOf { it.value }
                    val average = samples.sumOf { it.value } / samples.size
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Metric("mín.", min, device.unit)
                        Metric("média", average, device.unit)
                        Metric("máx.", max, device.unit)
                    }
                }
            }
        }

        Card {
            Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Tópicos", style = MaterialTheme.typography.titleMedium)
                device.topics.state?.let { TopicLine("estado", it) }
                device.topics.command?.let { TopicLine("comando", it) }
                device.topics.brightnessState?.let { TopicLine("brilho (estado)", it) }
                device.topics.brightnessCommand?.let { TopicLine("brilho (comando)", it) }
                device.topics.availability?.let { TopicLine("disponibilidade", it) }
                HorizontalDivider()
                Text(
                    text = if (device.discovered) {
                        "Descoberto automaticamente por MQTT Discovery."
                    } else {
                        "Configurado manualmente."
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun BrightnessControl(device: Device, onBrightness: (Int) -> Unit) {
    // O slider precisa de estado local para não pular durante o arrasto: o
    // valor real só é publicado quando o dedo sai da tela.
    var dragging by remember(device.id) { mutableFloatStateOf(-1f) }
    val current = device.state.brightness ?: 0
    val value = if (dragging >= 0f) dragging else current.toFloat()

    Column {
        Text("Brilho: ${value.toInt()}", style = MaterialTheme.typography.bodyMedium)
        Slider(
            value = value,
            onValueChange = { dragging = it },
            onValueChangeFinished = {
                if (dragging >= 0f) {
                    onBrightness(dragging.toInt())
                    dragging = -1f
                }
            },
            valueRange = CommandTranslator.BRIGHTNESS_MIN.toFloat()..CommandTranslator.BRIGHTNESS_MAX.toFloat(),
            enabled = device.state.online,
        )
    }
}

@Composable
private fun Metric(label: String, value: Double, unit: String?) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(label, style = MaterialTheme.typography.labelSmall)
        val rounded = kotlin.math.round(value * 10) / 10
        Text(
            text = if (unit.isNullOrBlank()) "$rounded" else "$rounded $unit",
            style = MaterialTheme.typography.titleMedium,
        )
    }
}
