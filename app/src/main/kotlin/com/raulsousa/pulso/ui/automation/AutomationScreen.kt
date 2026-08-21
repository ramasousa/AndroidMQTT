package com.raulsousa.pulso.ui.automation

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Card
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.raulsousa.pulso.domain.HomeState
import com.raulsousa.pulso.domain.automation.Comparison
import com.raulsousa.pulso.domain.automation.Rule
import com.raulsousa.pulso.domain.automation.RuleAction
import com.raulsousa.pulso.domain.automation.RuleFiring
import com.raulsousa.pulso.domain.automation.Trigger
import com.raulsousa.pulso.ui.theme.MonoStyle

/**
 * Automações locais.
 *
 * A regra roda no aparelho: sem conta, sem nuvem, sem assinatura mensal, e
 * continua funcionando com a internet caída — desde que o telefone e o broker
 * estejam na mesma rede. É o argumento inteiro do MQTT doméstico.
 */
@Composable
fun AutomationScreen(
    rules: List<Rule>,
    firings: List<RuleFiring>,
    home: HomeState,
    onToggleRule: (Rule, Boolean) -> Unit,
    contentPadding: PaddingValues,
    modifier: Modifier = Modifier,
) {
    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp),
        contentPadding = PaddingValues(
            top = contentPadding.calculateTopPadding(),
            bottom = contentPadding.calculateBottomPadding() + 24.dp,
        ),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (rules.isEmpty()) {
            item {
                Text(
                    text = "Nenhuma automação. As regras rodam no aparelho, sem nuvem.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        items(rules, key = { it.id }) { rule ->
            Card {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Row(
                        Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Text(rule.name, style = MaterialTheme.typography.titleMedium)
                        Switch(
                            checked = rule.enabled,
                            onCheckedChange = { onToggleRule(rule, it) },
                        )
                    }
                    Text(
                        text = rule.trigger.describe(home) + " → " + rule.action.describe(home),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        text = "silêncio mínimo de ${rule.cooldownMillis / 1_000}s entre disparos",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.outline,
                    )
                }
            }
        }

        if (firings.isNotEmpty()) {
            item { HorizontalDivider(Modifier.padding(vertical = 8.dp)) }
            item {
                Text("Últimos disparos", style = MaterialTheme.typography.titleMedium)
            }
            items(firings.take(MAX_HISTORY), key = { "${it.rule.id}-${it.atMillis}" }) { firing ->
                Column(Modifier.fillMaxWidth()) {
                    Text(firing.rule.name, style = MaterialTheme.typography.bodyMedium)
                    Text(
                        text = firing.reason,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        text = "${firing.intent.topic} ← ${firing.intent.payload}",
                        style = MonoStyle,
                        color = MaterialTheme.colorScheme.tertiary,
                    )
                }
            }
        }
    }
}

private fun Trigger.describe(home: HomeState): String = when (this) {
    is Trigger.NumericThreshold -> {
        val name = home.device(deviceId)?.name ?: deviceId.value
        val comparison = if (this.comparison == Comparison.GREATER_THAN) "acima de" else "abaixo de"
        "$name $comparison $threshold"
    }

    is Trigger.PowerBecomes -> "${home.device(deviceId)?.name ?: deviceId.value} fica $power"
    is Trigger.AvailabilityBecomes ->
        "${home.device(deviceId)?.name ?: deviceId.value} fica ${if (online) "online" else "offline"}"
}

private fun RuleAction.describe(home: HomeState): String = when (this) {
    is RuleAction.SetPower -> "${home.device(deviceId)?.name ?: deviceId.value} vai para $power"
    is RuleAction.SetBrightness -> "brilho de ${home.device(deviceId)?.name ?: deviceId.value} em $brightness"
    is RuleAction.PublishRaw -> "publica \"$payload\" em $topic"
}

private const val MAX_HISTORY = 20
