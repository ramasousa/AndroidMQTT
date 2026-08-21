package com.raulsousa.pulso.mqtt

import com.raulsousa.pulso.domain.Clock
import com.raulsousa.pulso.domain.ConnectionState
import com.raulsousa.pulso.domain.PublishIntent
import com.raulsousa.pulso.domain.Qos
import com.raulsousa.pulso.domain.Topics
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Um broker MQTT em memória.
 *
 * Faz duas coisas de uma vez:
 *
 * 1. É o dublê dos testes — nenhum teste desta base sobe container ou toca rede.
 * 2. É o **modo demonstração** do app. Instalar e abrir mostra uma casa
 *    funcionando, com dispositivos, telemetria e automações, sem broker, sem
 *    hardware e sem Wi-Fi. Um app de IoT que abre numa tela vazia perde o
 *    usuário nos primeiros dez segundos.
 *
 * Implementa o essencial do protocolo que o app depende: casamento de
 * wildcards, entrega só a quem assinou e mensagens retidas reentregues no
 * momento da assinatura.
 */
public class FakeMqttEngine(
    private val clock: Clock = Clock.System,
) : MqttEngine {

    private val _state = MutableStateFlow<ConnectionState>(ConnectionState.Disconnected)
    override val state: StateFlow<ConnectionState> = _state.asStateFlow()

    private val _incoming = MutableSharedFlow<MqttEnvelope>(
        replay = 0,
        extraBufferCapacity = 512,
        onBufferOverflow = BufferOverflow.SUSPEND,
    )
    override val incoming: Flow<MqttEnvelope> = _incoming.asSharedFlow()

    private val subscriptions = linkedSetOf<String>()
    private val retained = linkedMapOf<String, MqttEnvelope>()

    /** Tudo que o app publicou — a asserção preferida dos testes. */
    public val published: MutableList<PublishIntent> = mutableListOf()

    /** Quando `true`, [publish] falha; para exercitar caminhos de erro. */
    public var failPublish: Boolean = false

    /**
     * O "outro lado do fio": um firmware fictício que escuta o que o app
     * publica. É o que permite ao modo demonstração responder a um comando com
     * uma mudança de estado, como um dispositivo real faria.
     */
    public var onPublish: (suspend (PublishIntent) -> Unit)? = null

    override suspend fun connect(profile: BrokerProfile) {
        _state.value = ConnectionState.Connecting
        _state.value = ConnectionState.Connected(clock.nowMillis(), profile.label)
    }

    override suspend fun disconnect() {
        subscriptions.clear()
        _state.value = ConnectionState.Disconnected
    }

    override suspend fun subscribe(filters: Collection<String>, qos: Qos) {
        subscriptions += filters
        // Como um broker de verdade: quem assina recebe na hora o que está retido.
        //
        // O `awaitCollector()` fecha uma corrida real: um SharedFlow sem
        // assinantes descarta o que é emitido, e num broker de verdade a
        // reentrega dos retidos só acontece depois do round-trip de rede do
        // SUBSCRIBE — tempo de sobra para o coletor se registrar. Aqui não há
        // rede nenhuma, então a espera é explícita, sob pena de o modo
        // demonstração abrir vazio de vez em quando.
        awaitCollector()
        retained.values
            .filter { envelope -> filters.any { Topics.matches(it, envelope.topic) } }
            .forEach { _incoming.emit(it.copy(receivedAtMillis = clock.nowMillis())) }
    }

    private suspend fun awaitCollector() {
        if (_incoming.subscriptionCount.value > 0) return
        // Com prazo: se ninguém está coletando (um teste que só publica, um
        // pump cancelado), seguir em frente é melhor do que travar a chamada.
        withTimeoutOrNull(COLLECTOR_WAIT_MILLIS) {
            _incoming.subscriptionCount.first { it > 0 }
        }
    }

    override suspend fun unsubscribe(filters: Collection<String>) {
        subscriptions -= filters.toSet()
    }

    override suspend fun publish(intent: PublishIntent): Result<Unit> {
        if (failPublish) return Result.failure(IllegalStateException("broker indisponível"))
        published += intent
        // Um broker ecoa a publicação para os assinantes — inclusive o autor.
        emit(intent.topic, intent.payload, intent.qos, intent.retained)
        onPublish?.invoke(intent)
        return Result.success(Unit)
    }

    /** Simula um dispositivo publicando algo no broker. */
    public suspend fun emit(
        topic: String,
        payload: String,
        qos: Qos = Qos.AT_LEAST_ONCE,
        retain: Boolean = false,
    ) {
        val envelope = MqttEnvelope(topic, payload, qos, retain, clock.nowMillis())
        if (retain) {
            if (payload.isEmpty()) retained.remove(topic) else retained[topic] = envelope
        }
        if (subscriptions.any { Topics.matches(it, topic) }) {
            _incoming.emit(envelope)
        }
    }

    public fun subscriptions(): Set<String> = subscriptions.toSet()

    public fun retainedTopics(): Set<String> = retained.keys.toSet()

    public fun forceState(newState: ConnectionState) {
        _state.value = newState
    }

    private companion object {
        const val COLLECTOR_WAIT_MILLIS = 500L
    }
}
