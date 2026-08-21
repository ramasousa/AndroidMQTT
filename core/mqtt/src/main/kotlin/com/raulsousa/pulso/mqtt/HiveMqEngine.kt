package com.raulsousa.pulso.mqtt

import com.hivemq.client.mqtt.MqttClientSslConfig
import com.hivemq.client.mqtt.MqttGlobalPublishFilter
import com.hivemq.client.mqtt.datatypes.MqttQos
import com.hivemq.client.mqtt.mqtt5.Mqtt5AsyncClient
import com.hivemq.client.mqtt.mqtt5.Mqtt5Client
import com.raulsousa.pulso.domain.Clock
import com.raulsousa.pulso.domain.ConnectionState
import com.raulsousa.pulso.domain.PublishIntent
import com.raulsousa.pulso.domain.Qos
import java.nio.charset.StandardCharsets
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.suspendCancellableCoroutine

/**
 * Implementação sobre o **HiveMQ MQTT Client**.
 *
 * Por que não o Paho: o `org.eclipse.paho.android.service` usado no projeto
 * original foi arquivado pela Eclipse Foundation e nunca ganhou MQTT 5. Ele
 * dependia de um `Service` com IPC próprio que quebrou com as restrições de
 * background do Android 8+ e com o `targetSdk` moderno. O HiveMQ é mantido,
 * fala MQTT 5, tem reconexão automática com backoff embutido e é uma
 * biblioteca comum — quem cuida do ciclo de vida Android é o app, não ela.
 */
public class HiveMqEngine(
    private val clock: Clock = Clock.System,
) : MqttEngine {

    private val _state = MutableStateFlow<ConnectionState>(ConnectionState.Disconnected)
    override val state: StateFlow<ConnectionState> = _state.asStateFlow()

    private val _incoming = MutableSharedFlow<MqttEnvelope>(
        replay = 0,
        extraBufferCapacity = INCOMING_BUFFER,
        // Telemetria em rajada não pode travar a thread de rede do Netty:
        // é preferível perder a amostra mais antiga a segurar o socket.
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    override val incoming: Flow<MqttEnvelope> = _incoming.asSharedFlow()

    private val reconnectAttempts = AtomicInteger(0)

    @Volatile
    private var client: Mqtt5AsyncClient? = null

    @Volatile
    private var profile: BrokerProfile? = null

    override suspend fun connect(profile: BrokerProfile) {
        disconnect()
        this.profile = profile
        _state.value = ConnectionState.Connecting

        val built = buildClient(profile)
        client = built

        built.publishes(MqttGlobalPublishFilter.ALL) { publish ->
            _incoming.tryEmit(
                MqttEnvelope(
                    topic = publish.topic.toString(),
                    payload = String(publish.payloadAsBytes, StandardCharsets.UTF_8),
                    qos = Qos.of(publish.qos.code),
                    retained = publish.isRetain,
                    receivedAtMillis = clock.nowMillis(),
                ),
            )
        }

        try {
            built.connectWith()
                .cleanStart(profile.cleanStart)
                .sessionExpiryInterval(profile.sessionExpirySeconds)
                .keepAlive(profile.keepAliveSeconds)
                .apply {
                    val user = profile.username
                    if (!user.isNullOrBlank()) {
                        simpleAuth()
                            .username(user)
                            .password((profile.password ?: "").toByteArray(StandardCharsets.UTF_8))
                            .applySimpleAuth()
                    }
                }
                .willPublish()
                .topic(profile.lastWillTopic)
                .payload("offline".toByteArray(StandardCharsets.UTF_8))
                .retain(true)
                .applyWillPublish()
                .send()
                .await()

            reconnectAttempts.set(0)
            _state.value = ConnectionState.Connected(clock.nowMillis(), profile.label)
            // Presença: avisa a casa que este cliente está de pé. O last will
            // cuida do caso em que ele some sem se despedir.
            publish(
                PublishIntent(
                    topic = profile.lastWillTopic,
                    payload = "online",
                    retained = true,
                    origin = PublishIntent.Origin.SYSTEM,
                ),
            )
        } catch (error: Throwable) {
            _state.value = ConnectionState.Failed(
                reason = error.readableReason(),
                recoverable = error.isRecoverable(),
            )
            throw error
        }
    }

    override suspend fun disconnect() {
        val current = client ?: return
        client = null
        runCatching { current.disconnect().await() }
        _state.value = ConnectionState.Disconnected
    }

    override suspend fun subscribe(filters: Collection<String>, qos: Qos) {
        val current = client ?: return
        val distinct = filters.filter { it.isNotBlank() }.distinct()
        if (distinct.isEmpty()) return

        // Os builders do HiveMQ são estagiados (cada chamada devolve um tipo
        // novo), então um SUBSCRIBE por filtro sai mais simples do que montar a
        // mensagem composta — e o custo é nulo, porque quem chama aqui já manda
        // apenas o delta do que ainda não está assinado.
        distinct.forEach { filter ->
            current.subscribeWith()
                .topicFilter(filter)
                .qos(qos.toHiveMq())
                .send()
                .await()
        }
    }

    override suspend fun unsubscribe(filters: Collection<String>) {
        val current = client ?: return
        val distinct = filters.filter { it.isNotBlank() }.distinct()
        if (distinct.isEmpty()) return

        distinct.forEach { filter ->
            current.unsubscribeWith().topicFilter(filter).send().await()
        }
    }

    override suspend fun publish(intent: PublishIntent): Result<Unit> {
        val current = client ?: return Result.failure(IllegalStateException("Sem conexão com o broker."))
        return runCatching {
            current.publishWith()
                .topic(intent.topic)
                .payload(intent.payload.toByteArray(StandardCharsets.UTF_8))
                .qos(intent.qos.toHiveMq())
                .retain(intent.retained)
                .send()
                .await()
            Unit
        }
    }

    private fun buildClient(profile: BrokerProfile): Mqtt5AsyncClient =
        Mqtt5Client.builder()
            .identifier(profile.clientId.ifBlank { generateClientId() })
            .serverHost(profile.host)
            .serverPort(profile.port)
            .apply {
                if (profile.useTls) {
                    sslConfig(MqttClientSslConfig.builder().handshakeTimeout(10, TimeUnit.SECONDS).build())
                }
            }
            .automaticReconnect()
            .initialDelay(INITIAL_RECONNECT_SECONDS, TimeUnit.SECONDS)
            .maxDelay(MAX_RECONNECT_SECONDS, TimeUnit.SECONDS)
            .applyAutomaticReconnect()
            .addConnectedListener {
                reconnectAttempts.set(0)
                _state.value = ConnectionState.Connected(clock.nowMillis(), profile.label)
            }
            .addDisconnectedListener { context ->
                val attempt = reconnectAttempts.incrementAndGet()
                _state.value = if (context.reconnector.isReconnect) {
                    ConnectionState.Reconnecting(
                        attempt = attempt,
                        nextAttemptInMillis = context.reconnector.getDelay(TimeUnit.MILLISECONDS),
                    )
                } else {
                    ConnectionState.Disconnected
                }
            }
            .buildAsync()

    public companion object {
        private const val INCOMING_BUFFER = 256
        private const val INITIAL_RECONNECT_SECONDS = 1L
        private const val MAX_RECONNECT_SECONDS = 60L

        /** Id estável o bastante para uma sessão persistente, único por instalação. */
        public fun generateClientId(): String =
            "pulso-" + java.util.UUID.randomUUID().toString().take(12)
    }
}

private fun Qos.toHiveMq(): MqttQos = when (this) {
    Qos.AT_MOST_ONCE -> MqttQos.AT_MOST_ONCE
    Qos.AT_LEAST_ONCE -> MqttQos.AT_LEAST_ONCE
    Qos.EXACTLY_ONCE -> MqttQos.EXACTLY_ONCE
}

private fun Throwable.readableReason(): String = when {
    message.isNullOrBlank() -> this::class.simpleName ?: "Falha desconhecida"
    else -> message!!
}

private fun Throwable.isRecoverable(): Boolean {
    val text = (message ?: "").lowercase()
    // Credencial errada e "não autorizado" não melhoram com nova tentativa;
    // insistir só queima bateria e, em broker sério, bloqueia o cliente.
    return !text.contains("not authorized") &&
        !text.contains("bad user name") &&
        !text.contains("banned")
}

/** Ponte mínima entre `CompletableFuture` e corrotina, sem dependência extra. */
private suspend fun <T> CompletableFuture<T>.await(): T =
    suspendCancellableCoroutine { continuation ->
        whenComplete { value, error ->
            when {
                error != null -> continuation.resumeWithException(error)
                else -> continuation.resume(value)
            }
        }
        continuation.invokeOnCancellation { cancel(true) }
    }
