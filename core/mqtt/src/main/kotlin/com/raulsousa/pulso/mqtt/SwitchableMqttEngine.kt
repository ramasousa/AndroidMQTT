package com.raulsousa.pulso.mqtt

import com.raulsousa.pulso.domain.ConnectionState
import com.raulsousa.pulso.domain.PublishIntent
import com.raulsousa.pulso.domain.Qos
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn

/**
 * Um [MqttEngine] que troca de implementação em tempo de execução.
 *
 * É o que permite alternar entre broker real e modo demonstração sem recriar a
 * [MqttSession] — e, portanto, sem que a UI precise saber que a troca
 * aconteceu. A sessão, os fluxos observados pela tela e o estado da casa
 * continuam sendo os mesmos objetos.
 */
@OptIn(ExperimentalCoroutinesApi::class)
public class SwitchableMqttEngine(
    initial: MqttEngine,
    scope: CoroutineScope,
) : MqttEngine {

    private val delegate = MutableStateFlow(initial)

    public val current: MqttEngine get() = delegate.value

    override val state: StateFlow<ConnectionState> = delegate
        .flatMapLatest { it.state }
        .stateIn(scope, SharingStarted.Eagerly, ConnectionState.Disconnected)

    override val incoming: Flow<MqttEnvelope> = delegate.flatMapLatest { it.incoming }

    /** Desliga o motor atual antes de assumir o novo. */
    public suspend fun switchTo(engine: MqttEngine) {
        if (engine === delegate.value) return
        delegate.value.disconnect()
        delegate.value = engine
    }

    override suspend fun connect(profile: BrokerProfile): Unit = delegate.value.connect(profile)

    override suspend fun disconnect(): Unit = delegate.value.disconnect()

    override suspend fun subscribe(filters: Collection<String>, qos: Qos): Unit =
        delegate.value.subscribe(filters, qos)

    override suspend fun unsubscribe(filters: Collection<String>): Unit =
        delegate.value.unsubscribe(filters)

    override suspend fun publish(intent: PublishIntent): Result<Unit> = delegate.value.publish(intent)
}
