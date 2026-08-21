package com.raulsousa.pulso.mqtt

import com.raulsousa.pulso.domain.ConnectionState
import com.raulsousa.pulso.domain.PublishIntent
import com.raulsousa.pulso.domain.Qos
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

/** Uma mensagem que chegou do broker. */
public data class MqttEnvelope(
    val topic: String,
    val payload: String,
    val qos: Qos,
    val retained: Boolean,
    val receivedAtMillis: Long,
) {
    public val payloadPreview: String
        get() = if (payload.length <= PREVIEW_LIMIT) payload else payload.take(PREVIEW_LIMIT) + "…"

    private companion object {
        const val PREVIEW_LIMIT = 240
    }
}

/**
 * A fronteira entre o app e o protocolo.
 *
 * Toda a UI, todo o domínio e todos os testes conversam com esta interface — e
 * não com HiveMQ, Paho ou sockets. É por isso que trocar a biblioteca MQTT (que
 * é exatamente o que este projeto teve de fazer quando o Paho Android Service
 * foi arquivado) passa a ser um arquivo novo, e não um refactor do app inteiro.
 */
public interface MqttEngine {

    public val state: StateFlow<ConnectionState>

    /** Fluxo único de tudo que chega. Quem filtra por tópico é o consumidor. */
    public val incoming: Flow<MqttEnvelope>

    public suspend fun connect(profile: BrokerProfile)

    public suspend fun disconnect()

    public suspend fun subscribe(filters: Collection<String>, qos: Qos = Qos.AT_LEAST_ONCE)

    public suspend fun unsubscribe(filters: Collection<String>)

    public suspend fun publish(intent: PublishIntent): Result<Unit>
}
