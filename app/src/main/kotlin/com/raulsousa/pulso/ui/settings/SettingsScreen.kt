package com.raulsousa.pulso.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.raulsousa.pulso.data.AppSettings
import com.raulsousa.pulso.mqtt.BrokerProfile

/**
 * Ajustes.
 *
 * Tudo o que aqui é editável era constante compilada em 2017. O aviso de TLS
 * aparece antes de salvar, e não numa auditoria seis meses depois.
 */
@Composable
fun SettingsScreen(
    settings: AppSettings,
    onSaveProfile: (BrokerProfile) -> Unit,
    onDemoMode: (Boolean) -> Unit,
    onKeepAlive: (Boolean) -> Unit,
    contentPadding: PaddingValues,
    modifier: Modifier = Modifier,
) {
    val profile = settings.profile
    var host by remember(profile.host) { mutableStateOf(profile.host) }
    var port by remember(profile.port) { mutableStateOf(profile.port.toString()) }
    var tls by remember(profile.useTls) { mutableStateOf(profile.useTls) }
    var user by remember(profile.username) { mutableStateOf(profile.username.orEmpty()) }
    var password by remember { mutableStateOf(profile.password.orEmpty()) }
    var prefix by remember(profile.discoveryPrefix) { mutableStateOf(profile.discoveryPrefix) }
    var autoDiscovery by remember(profile.autoDiscovery) { mutableStateOf(profile.autoDiscovery) }

    val edited = profile.copy(
        host = host.trim(),
        port = port.toIntOrNull() ?: profile.port,
        useTls = tls,
        username = user.trim().ifBlank { null },
        password = password.ifBlank { null },
        discoveryPrefix = prefix.trim(),
        autoDiscovery = autoDiscovery,
    )
    val warnings = edited.validate()

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(contentPadding)
            .padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Card {
            Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                SettingSwitch(
                    title = "Modo demonstração",
                    subtitle = "Simula uma casa completa dentro do app. Nada sai do aparelho.",
                    checked = settings.demoMode,
                    onCheckedChange = onDemoMode,
                )
                SettingSwitch(
                    title = "Manter conexão em segundo plano",
                    subtitle = "Sobe um serviço em primeiro plano com notificação persistente.",
                    checked = settings.keepAlive,
                    onCheckedChange = onKeepAlive,
                )
            }
        }

        Card {
            Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Broker", style = MaterialTheme.typography.titleMedium)

                OutlinedTextField(
                    value = host,
                    onValueChange = { host = it },
                    label = { Text("Endereço") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    OutlinedTextField(
                        value = port,
                        onValueChange = { port = it.filter(Char::isDigit).take(5) },
                        label = { Text("Porta") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.weight(1f),
                    )
                    Row(
                        Modifier.weight(1f),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Switch(
                            checked = tls,
                            onCheckedChange = { enabled ->
                                tls = enabled
                                // Porta segue o protocolo, a menos que já tenha
                                // sido customizada.
                                if (port == "1883" && enabled) port = "8883"
                                if (port == "8883" && !enabled) port = "1883"
                            },
                        )
                        Text("TLS", style = MaterialTheme.typography.bodyMedium)
                    }
                }
                OutlinedTextField(
                    value = user,
                    onValueChange = { user = it },
                    label = { Text("Usuário") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = password,
                    onValueChange = { password = it },
                    label = { Text("Senha") },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    modifier = Modifier.fillMaxWidth(),
                )

                warnings.forEach { warning ->
                    Text(
                        text = "⚠ $warning",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }

                Button(
                    onClick = { onSaveProfile(edited) },
                    enabled = host.isNotBlank(),
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Salvar e conectar") }
            }
        }

        Card {
            Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Descoberta", style = MaterialTheme.typography.titleMedium)
                SettingSwitch(
                    title = "Descoberta automática",
                    subtitle = "Assina o padrão de discovery e monta o dashboard sozinho.",
                    checked = autoDiscovery,
                    onCheckedChange = { autoDiscovery = it },
                )
                OutlinedTextField(
                    value = prefix,
                    onValueChange = { prefix = it },
                    label = { Text("Prefixo") },
                    singleLine = true,
                    enabled = autoDiscovery,
                    modifier = Modifier.fillMaxWidth(),
                )
                Text(
                    text = "Cliente: ${profile.clientId}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun SettingSwitch(
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Column(Modifier.weight(1f).padding(end = 12.dp)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(
                subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}
