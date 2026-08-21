package com.raulsousa.pulso.ui.dashboard

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.raulsousa.pulso.domain.Device
import com.raulsousa.pulso.domain.DeviceId
import com.raulsousa.pulso.domain.HomeState
import com.raulsousa.pulso.domain.Sample
import com.raulsousa.pulso.ui.components.DeviceCard

/**
 * A casa inteira numa tela.
 *
 * O app de 2017 tinha uma imagem e dois botões para um dispositivo fixo. Aqui a
 * tela é uma função do que a casa anunciou — zero, um ou trinta dispositivos,
 * agrupados por cômodo, sem código específico para nenhum deles.
 */
@Composable
fun DashboardScreen(
    home: HomeState,
    discoveryPrefix: String,
    telemetry: (DeviceId) -> List<Sample>,
    onToggle: (DeviceId) -> Unit,
    onOpen: (DeviceId) -> Unit,
    contentPadding: PaddingValues,
    modifier: Modifier = Modifier,
) {
    if (home.devices.isEmpty()) {
        EmptyHouse(discoveryPrefix, contentPadding, modifier)
        return
    }

    val grouped: List<Pair<String, List<Device>>> = home.orderedDevices
        .groupBy { it.room ?: "Sem cômodo" }
        .toList()

    LazyVerticalGrid(
        columns = GridCells.Adaptive(minSize = 168.dp),
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(
            start = 16.dp,
            end = 16.dp,
            top = contentPadding.calculateTopPadding() + 8.dp,
            bottom = contentPadding.calculateBottomPadding() + 24.dp,
        ),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        grouped.forEach { (room, devices) ->
            item(span = { androidx.compose.foundation.lazy.grid.GridItemSpan(maxLineSpan) }, key = "room-$room") {
                Text(
                    text = room,
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
            items(devices, key = { it.id.value }) { device ->
                DeviceCard(
                    device = device,
                    samples = telemetry(device.id),
                    onToggle = { onToggle(device.id) },
                    onOpen = { onOpen(device.id) },
                )
            }
        }
    }
}

@Composable
private fun EmptyHouse(
    discoveryPrefix: String,
    contentPadding: PaddingValues,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(contentPadding)
            .padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = "Nenhum dispositivo ainda",
            style = MaterialTheme.typography.headlineSmall,
            textAlign = TextAlign.Center,
        )
        Text(
            text = "Assim que algo se anunciar em $discoveryPrefix/+/+/config, ele aparece aqui. " +
                "Sem broker à mão? Ligue o modo demonstração nos ajustes.",
            style = MaterialTheme.typography.bodyMedium,
            textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 12.dp),
        )
    }
}
