package com.raulsousa.pulso.domain

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** Tudo que pode alterar o estado da casa. */
public sealed interface HomeEvent {
    /** Um dispositivo se anunciou (via MQTT Discovery ou configuração manual). */
    public data class DeviceAnnounced(val device: Device) : HomeEvent

    /** Discovery com payload vazio = dispositivo retirado do ar. */
    public data class DeviceRemoved(val id: DeviceId) : HomeEvent

    /** Chegou uma mensagem qualquer do broker. */
    public data class MessageReceived(val topic: String, val payload: String) : HomeEvent

    /** O usuário mandou um comando; assumimos o resultado até o broker confirmar. */
    public data class OptimisticCommand(val command: DeviceCommand) : HomeEvent

    /** Passagem de tempo: expira estados otimistas não confirmados. */
    public data object Tick : HomeEvent
}

/**
 * O estado imutável da casa e sua função de transição.
 *
 * Este é o coração do app e ele é uma função pura: `(estado, evento) -> estado`.
 * Nenhuma view, nenhum socket, nenhum `Context`. É por isso que dá para testar
 * o comportamento inteiro — reconexão, reconciliação otimista, disponibilidade —
 * em milissegundos, sem emulador.
 */
public data class HomeState(
    val devices: Map<DeviceId, Device> = emptyMap(),
    /** Comandos aguardando confirmação pelo tópico de estado. */
    val pending: Map<DeviceId, PendingCommand> = emptyMap(),
) {

    public data class PendingCommand(val expected: DeviceState, val sentAtMillis: Long)

    public val orderedDevices: List<Device>
        get() = devices.values.sortedWith(compareBy({ it.room ?: "￿" }, { it.name }))

    public val rooms: List<String>
        get() = devices.values.mapNotNull { it.room }.distinct().sorted()

    public fun device(id: DeviceId): Device? = devices[id]

    /** Filtros MQTT que precisam estar assinados para este estado se manter vivo. */
    public fun requiredSubscriptions(): Set<String> =
        devices.values.flatMap { it.topics.subscriptions() }.toSet()

    public fun reduce(event: HomeEvent, nowMillis: Long): HomeState = when (event) {
        is HomeEvent.DeviceAnnounced -> announce(event.device, nowMillis)
        is HomeEvent.DeviceRemoved -> copy(devices = devices - event.id, pending = pending - event.id)
        is HomeEvent.MessageReceived -> applyMessage(event.topic, event.payload, nowMillis)
        is HomeEvent.OptimisticCommand -> applyOptimistic(event.command, nowMillis)
        HomeEvent.Tick -> expirePending(nowMillis)
    }

    public fun reduceAll(events: Iterable<HomeEvent>, nowMillis: Long): HomeState =
        events.fold(this) { acc, event -> acc.reduce(event, nowMillis) }

    // -- transições ---------------------------------------------------------

    private fun announce(incoming: Device, nowMillis: Long): HomeState {
        val existing = devices[incoming.id]
        // Um re-anúncio (o broker reenvia configs retidas a cada reconexão) não
        // pode apagar o estado que já conhecemos do dispositivo.
        val merged = if (existing == null) {
            incoming.copy(state = incoming.state.copy(updatedAtMillis = nowMillis))
        } else {
            incoming.copy(state = existing.state)
        }
        return copy(devices = devices + (merged.id to merged))
    }

    private fun applyMessage(topic: String, payload: String, nowMillis: Long): HomeState {
        var changed = false
        val updated = devices.mapValues { (_, device) ->
            val next = device.applyPayload(topic, payload, nowMillis)
            if (next !== device) changed = true
            next
        }

        // Confirmação chegou: derruba o pendente de quem foi reconciliado.
        //
        // Isto precisa acontecer mesmo quando o estado não mudou: se o
        // dispositivo confirma exatamente o que a UI já estava mostrando de
        // forma otimista — o caso normal, quando tudo funciona — não há
        // diferença a aplicar, mas o comando *foi* confirmado. Sair antes daqui
        // deixaria o pendente vivo até expirar, e o aparelho seria marcado como
        // indisponível justamente por ter respondido certo.
        val stillPending = pending.filterKeys { id ->
            val expected = pending[id]?.expected ?: return@filterKeys false
            val device = updated[id] ?: return@filterKeys false
            val fromThisDevice = topic == device.topics.state || topic == device.topics.brightnessState
            if (!fromThisDevice) return@filterKeys true
            !expected.matchesConfirmation(device.state)
        }

        if (!changed && stillPending.size == pending.size) return this
        return copy(devices = updated, pending = stillPending)
    }

    private fun applyOptimistic(command: DeviceCommand, nowMillis: Long): HomeState {
        val device = devices[command.deviceId] ?: return this
        val expected = CommandTranslator.optimisticState(device, command, nowMillis) ?: return this
        return copy(
            devices = devices + (device.id to device.copy(state = expected)),
            pending = pending + (device.id to PendingCommand(expected, nowMillis)),
        )
    }

    /**
     * Se o dispositivo não confirmou dentro de [OPTIMISTIC_TIMEOUT_MILLIS], a UI
     * para de mentir: marcamos como indisponível em vez de deixar o usuário
     * olhando uma lâmpada "acesa" que nunca acendeu. Este é literalmente o bug
     * de UX do app de 2017, onde o `ViewSwitcher` trocava de imagem antes de
     * qualquer confirmação.
     */
    private fun expirePending(nowMillis: Long): HomeState {
        if (pending.isEmpty()) return this
        val expired = pending.filterValues { nowMillis - it.sentAtMillis >= OPTIMISTIC_TIMEOUT_MILLIS }
        if (expired.isEmpty()) return this
        val updated = devices.mapValues { (id, device) ->
            if (id in expired) device.copy(state = device.state.copy(online = false)) else device
        }
        return copy(devices = updated, pending = pending - expired.keys)
    }

    public companion object {
        public const val OPTIMISTIC_TIMEOUT_MILLIS: Long = 5_000

        public val EMPTY: HomeState = HomeState()

        private val lenientJson = Json { ignoreUnknownKeys = true; isLenient = true }

        /** Extrai um valor de um payload JSON simples, se [key] for informada. */
        internal fun extract(payload: String, key: String?): String? {
            if (key.isNullOrBlank()) return payload
            val trimmed = payload.trim()
            if (!trimmed.startsWith("{")) return null
            return runCatching {
                val element = lenientJson.parseToJsonElement(trimmed) as? JsonObject ?: return null
                (element[key] as? JsonPrimitive)?.content
            }.getOrNull()
        }
    }
}

/** Reconciliação: o estado real confirma o otimista? */
private fun DeviceState.matchesConfirmation(actual: DeviceState): Boolean {
    val powerOk = power == null || power == actual.power
    val brightnessOk = brightness == null || brightness == actual.brightness
    return powerOk && brightnessOk
}

private fun Device.applyPayload(topic: String, payload: String, nowMillis: Long): Device {
    val topics = this.topics
    return when (topic) {
        topics.availability -> {
            val online = payload.trim() == vocabulary.available
            if (online == state.online) this
            else copy(state = state.copy(online = online, updatedAtMillis = nowMillis))
        }

        topics.brightnessState -> {
            val value = payload.trim().toIntOrNull() ?: return this
            copy(state = state.copy(brightness = value, updatedAtMillis = nowMillis, online = true))
        }

        topics.state -> {
            val raw = HomeState.extract(payload, topics.valueKey) ?: return this
            val next = when (kind) {
                DeviceKind.SENSOR -> {
                    val number = raw.trim().toDoubleOrNull()
                    if (number != null) {
                        state.copy(numeric = number, text = null, updatedAtMillis = nowMillis, online = true)
                    } else {
                        state.copy(text = raw.trim(), updatedAtMillis = nowMillis, online = true)
                    }
                }

                else -> {
                    val power = vocabulary.parsePower(raw)
                    if (power == null) {
                        state.copy(text = raw.trim(), updatedAtMillis = nowMillis, online = true)
                    } else {
                        state.copy(power = power, text = null, updatedAtMillis = nowMillis, online = true)
                    }
                }
            }
            if (next == state) this else copy(state = next)
        }

        else -> this
    }
}
