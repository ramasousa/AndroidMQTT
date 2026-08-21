package com.raulsousa.pulso.domain.automation

import com.raulsousa.pulso.domain.CommandTranslator
import com.raulsousa.pulso.domain.DeviceCommand
import com.raulsousa.pulso.domain.DeviceId
import com.raulsousa.pulso.domain.HomeState
import com.raulsousa.pulso.domain.Power
import com.raulsousa.pulso.domain.PublishIntent
import com.raulsousa.pulso.domain.Qos
import kotlinx.serialization.Serializable

public enum class Comparison(public val symbol: String) {
    GREATER_THAN(">"),
    LESS_THAN("<"),
    ;

    public fun test(value: Double, threshold: Double): Boolean = when (this) {
        GREATER_THAN -> value > threshold
        LESS_THAN -> value < threshold
    }
}

/** O que faz a regra disparar. */
@Serializable
public sealed interface Trigger {
    /**
     * Cruzamento de limiar com histerese.
     *
     * A histerese é o detalhe que separa uma automação usável de uma que
     * transforma sua casa em pisca-pisca: com o limiar em 28 °C e histerese de
     * 1 °C, o gatilho arma em 28,0 e só rearma depois de cair abaixo de 27,0.
     */
    @Serializable
    public data class NumericThreshold(
        val deviceId: DeviceId,
        val comparison: Comparison,
        val threshold: Double,
        val hysteresis: Double = 0.0,
    ) : Trigger

    @Serializable
    public data class PowerBecomes(val deviceId: DeviceId, val power: Power) : Trigger

    @Serializable
    public data class AvailabilityBecomes(val deviceId: DeviceId, val online: Boolean) : Trigger
}

/** O que a regra faz quando dispara. */
@Serializable
public sealed interface RuleAction {
    @Serializable
    public data class SetPower(val deviceId: DeviceId, val power: Power) : RuleAction

    @Serializable
    public data class SetBrightness(val deviceId: DeviceId, val brightness: Int) : RuleAction

    @Serializable
    public data class PublishRaw(
        val topic: String,
        val payload: String,
        val retained: Boolean = false,
    ) : RuleAction
}

@Serializable
public data class Rule(
    val id: String,
    val name: String,
    val trigger: Trigger,
    val action: RuleAction,
    val enabled: Boolean = true,
    /** Silêncio mínimo entre dois disparos da mesma regra. */
    val cooldownMillis: Long = 60_000,
)

/** Registro de um disparo, para a trilha de auditoria da UI. */
public data class RuleFiring(
    val rule: Rule,
    val intent: PublishIntent,
    val atMillis: Long,
    val reason: String,
)

/**
 * Motor de automações local — roda no telefone, sem nuvem, sem conta, sem
 * assinatura.
 *
 * Guarda apenas o mínimo de estado mutável necessário para histerese e
 * cooldown; todo o resto vem do par (estado anterior, estado novo). Isso torna
 * cada decisão reproduzível em teste com um [com.raulsousa.pulso.domain.MutableClock].
 */
public class RuleEngine(rules: List<Rule> = emptyList()) {

    private var rules: List<Rule> = rules
    private val armed: MutableMap<String, Boolean> = mutableMapOf()
    private val lastFiredAt: MutableMap<String, Long> = mutableMapOf()

    public fun setRules(newRules: List<Rule>) {
        rules = newRules
        val ids = newRules.mapTo(mutableSetOf()) { it.id }
        armed.keys.retainAll(ids)
        lastFiredAt.keys.retainAll(ids)
    }

    public fun rules(): List<Rule> = rules

    /**
     * Avalia a transição `before -> after` e devolve o que deve ser publicado.
     *
     * Retorna lista (e não uma única publicação) porque uma mesma mudança de
     * estado pode legitimamente acionar várias regras.
     */
    public fun evaluate(before: HomeState, after: HomeState, nowMillis: Long): List<RuleFiring> {
        val firings = mutableListOf<RuleFiring>()
        for (rule in rules) {
            if (!rule.enabled) continue
            val reason = rule.trigger.firedReason(before, after) ?: continue
            if (!rule.passesCooldown(nowMillis)) continue

            val intent = rule.action.toIntent(after) ?: continue
            lastFiredAt[rule.id] = nowMillis
            firings += RuleFiring(rule, intent, nowMillis, reason)
        }
        return firings
    }

    private fun Rule.passesCooldown(nowMillis: Long): Boolean {
        val last = lastFiredAt[id] ?: return true
        return nowMillis - last >= cooldownMillis
    }

    private fun Trigger.firedReason(before: HomeState, after: HomeState): String? = when (this) {
        is Trigger.NumericThreshold -> numericFired(before, after)

        is Trigger.PowerBecomes -> {
            val old = before.device(deviceId)?.state?.power
            val new = after.device(deviceId)?.state?.power
            if (old != new && new == power) "${after.device(deviceId)?.name} ficou $power" else null
        }

        is Trigger.AvailabilityBecomes -> {
            val old = before.device(deviceId)?.state?.online
            val new = after.device(deviceId)?.state?.online
            if (old != new && new == online) {
                "${after.device(deviceId)?.name} ficou ${if (online) "online" else "offline"}"
            } else {
                null
            }
        }
    }

    private fun Trigger.NumericThreshold.numericFired(before: HomeState, after: HomeState): String? {
        val ruleKey = "$deviceId:${comparison.symbol}:$threshold"
        val value = after.device(deviceId)?.state?.numeric ?: return null
        val previous = before.device(deviceId)?.state?.numeric
        if (previous == value) return null

        val isTriggered = comparison.test(value, threshold)
        val rearmThreshold = when (comparison) {
            Comparison.GREATER_THAN -> threshold - hysteresis
            Comparison.LESS_THAN -> threshold + hysteresis
        }
        val isRearmed = when (comparison) {
            Comparison.GREATER_THAN -> value < rearmThreshold
            Comparison.LESS_THAN -> value > rearmThreshold
        }

        val wasArmed = armed[ruleKey] ?: true
        return when {
            isTriggered && wasArmed -> {
                armed[ruleKey] = false
                "valor $value ${comparison.symbol} $threshold"
            }

            isRearmed -> {
                armed[ruleKey] = true
                null
            }

            else -> null
        }
    }

    private fun RuleAction.toIntent(state: HomeState): PublishIntent? = when (this) {
        is RuleAction.SetPower -> {
            val device = state.device(deviceId)
            device?.let {
                CommandTranslator.translate(it, DeviceCommand.SetPower(deviceId, power))
                    ?.copy(origin = PublishIntent.Origin.AUTOMATION)
            }
        }

        is RuleAction.SetBrightness -> {
            val device = state.device(deviceId)
            device?.let {
                CommandTranslator.translate(it, DeviceCommand.SetBrightness(deviceId, brightness))
                    ?.copy(origin = PublishIntent.Origin.AUTOMATION)
            }
        }

        is RuleAction.PublishRaw -> PublishIntent(
            topic = topic,
            payload = payload,
            qos = Qos.AT_LEAST_ONCE,
            retained = retained,
            origin = PublishIntent.Origin.AUTOMATION,
        )
    }
}
