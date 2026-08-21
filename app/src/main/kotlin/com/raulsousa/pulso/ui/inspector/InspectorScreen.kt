package com.raulsousa.pulso.ui.inspector

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.raulsousa.pulso.domain.PublishIntent
import com.raulsousa.pulso.mqtt.TraceEntry
import com.raulsousa.pulso.ui.theme.MonoStyle

/**
 * Um MQTT Explorer dentro do app.
 *
 * Esta é a ferramenta que faltava em 2017 e que qualquer um que já depurou IoT
 * conhece: ver o tráfego cru, assinar um filtro na hora e publicar um payload à
 * mão para testar um firmware. Antes disso a resposta a "por que a lâmpada não
 * acende?" era recompilar o app com um `Log.i`.
 */
@Composable
fun InspectorScreen(
    trace: List<TraceEntry>,
    subscriptions: Set<String>,
    onWatch: (String) -> Unit,
    onUnwatch: (String) -> Unit,
    onPublish: (String, String, Boolean) -> Unit,
    onClear: () -> Unit,
    contentPadding: PaddingValues,
    modifier: Modifier = Modifier,
) {
    var filter by remember { mutableStateOf("casa/#") }
    var topic by remember { mutableStateOf("") }
    var payload by remember { mutableStateOf("") }
    var retained by remember { mutableStateOf(false) }

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(contentPadding)
            .padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            OutlinedTextField(
                value = filter,
                onValueChange = { filter = it },
                label = { Text("Filtro") },
                singleLine = true,
                textStyle = MonoStyle,
                modifier = Modifier.weight(1f),
            )
            Button(onClick = { onWatch(filter) }) { Text("Assinar") }
        }

        if (subscriptions.isNotEmpty()) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                subscriptions.take(MAX_CHIPS).forEach { subscription ->
                    AssistChip(
                        onClick = { onUnwatch(subscription) },
                        label = { Text(subscription, style = MonoStyle, maxLines = 1) },
                    )
                }
            }
        }

        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlinedTextField(
                    value = topic,
                    onValueChange = { topic = it },
                    label = { Text("Tópico") },
                    singleLine = true,
                    textStyle = MonoStyle,
                    modifier = Modifier.weight(1f),
                )
                OutlinedTextField(
                    value = payload,
                    onValueChange = { payload = it },
                    label = { Text("Payload") },
                    singleLine = true,
                    textStyle = MonoStyle,
                    modifier = Modifier.weight(1f),
                )
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(checked = retained, onCheckedChange = { retained = it })
                Text("retido", style = MaterialTheme.typography.bodySmall)
                Button(
                    onClick = { onPublish(topic, payload, retained) },
                    enabled = topic.isNotBlank(),
                    modifier = Modifier.padding(start = 12.dp),
                ) { Text("Publicar") }
                TextButton(onClick = onClear, modifier = Modifier.padding(start = 8.dp)) {
                    Text("Limpar")
                }
            }
        }

        HorizontalDivider()

        if (trace.isEmpty()) {
            Text(
                text = "Nada capturado ainda. Assine um filtro para ver o tráfego.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            LazyColumn(
                verticalArrangement = Arrangement.spacedBy(6.dp),
                contentPadding = PaddingValues(bottom = 24.dp),
            ) {
                items(trace, key = { "${it.atMillis}-${it.topic}-${it.direction}-${it.payload.hashCode()}" }) {
                    TraceRow(it)
                }
            }
        }
    }
}

@Composable
private fun TraceRow(entry: TraceEntry) {
    val incoming = entry.direction == TraceEntry.Direction.IN
    val accent = when {
        incoming -> MaterialTheme.colorScheme.primary
        entry.origin == PublishIntent.Origin.AUTOMATION -> MaterialTheme.colorScheme.tertiary
        else -> MaterialTheme.colorScheme.secondary
    }

    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(accent.copy(alpha = 0.07f))
            .padding(10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = if (incoming) "◀ recebido" else "▶ enviado",
                style = MaterialTheme.typography.labelSmall,
                color = accent,
            )
            if (entry.retained) {
                Text(
                    text = "  retido",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.outline,
                )
            }
            Text(
                text = "  QoS ${entry.qos.level}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.outline,
            )
        }
        Text(entry.topic, style = MonoStyle, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(
            text = entry.payload.take(PAYLOAD_LIMIT),
            style = MonoStyle,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 4,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

private const val MAX_CHIPS = 4
private const val PAYLOAD_LIMIT = 400
