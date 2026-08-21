package com.raulsousa.pulso.mqtt

import com.raulsousa.pulso.domain.Clock
import com.raulsousa.pulso.domain.CommandTranslator
import com.raulsousa.pulso.domain.ConnectionState
import com.raulsousa.pulso.domain.Device
import com.raulsousa.pulso.domain.DeviceCommand
import com.raulsousa.pulso.domain.DeviceId
import com.raulsousa.pulso.domain.HomeEvent
import com.raulsousa.pulso.domain.HomeState
import com.raulsousa.pulso.domain.PublishIntent
import com.raulsousa.pulso.domain.Qos
import com.raulsousa.pulso.domain.Sample
import com.raulsousa.pulso.domain.TelemetrySeries
import com.raulsousa.pulso.domain.Topics
import com.raulsousa.pulso.domain.automation.Rule
import com.raulsousa.pulso.domain.automation.RuleEngine
import com.raulsousa.pulso.domain.automation.RuleFiring
import com.raulsousa.pulso.mqtt.discovery.HomeAssistantDiscovery
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Uma linha da trilha do inspetor. */
public data class TraceEntry(
    val direction: Direction,
    val topic: String,
    val payload: String,
    val qos: Qos,
    val retained: Boolean,
    val atMillis: Long,
    val origin: PublishIntent.Origin? = null,
) {
    public enum class Direction { IN, OUT }
}

/**
 * Costura tudo: transporte, descoberta, estado da casa, automações, telemetria
 * e trilha de auditoria.
 *
 * Toda a lógica vive aqui e no `:core:domain` — nada disso conhece Android, e é
 * por isso que a sessão inteira roda em teste contra um [MqttEngine] falso, com
 * relógio virtual, em milissegundos.
 */
public class MqttSession(
    private val engine: MqttEngine,
    private val scope: CoroutineScope,
    private val clock: Clock = Clock.System,
    private val ruleEngine: RuleEngine = RuleEngine(),
    private val traceCapacity: Int = DEFAULT_TRACE_CAPACITY,
) {

    private val _home = MutableStateFlow(HomeState.EMPTY)
    public val home: StateFlow<HomeState> = _home.asStateFlow()

    private val _trace = MutableStateFlow<List<TraceEntry>>(emptyList())
    public val trace: StateFlow<List<TraceEntry>> = _trace.asStateFlow()

    private val _firings = MutableStateFlow<List<RuleFiring>>(emptyList())
    public val firings: StateFlow<List<RuleFiring>> = _firings.asStateFlow()

    public val connection: StateFlow<ConnectionState> get() = engine.state

    private val stateGuard = Any()

    /** tópico de discovery -> id do dispositivo que ele criou. */
    private val discoveryIndex = ConcurrentHashMap<String, DeviceId>()
    private val telemetry = ConcurrentHashMap<DeviceId, TelemetrySeries>()
    private val subscribed = mutableSetOf<String>()
    private val subscriptionLock = Mutex()

    private var profile: BrokerProfile = BrokerProfile()
    private var pump: Job? = null

    /** Assinaturas manuais do inspetor, mantidas separadas das dos dispositivos. */
    private val manualFilters = mutableSetOf<String>()

    public fun start(profile: BrokerProfile) {
        this.profile = profile
        pump?.cancel()
        pump = scope.launch {
            engine.incoming.collect { envelope -> onMessage(envelope) }
        }
        scope.launch {
            engine.connect(profile)
            resubscribeAll()
        }
    }

    public suspend fun stop() {
        pump?.cancel()
        pump = null
        subscriptionLock.withLock { subscribed.clear() }
        engine.disconnect()
    }

    public fun setRules(rules: List<Rule>) {
        ruleEngine.setRules(rules)
    }

    public fun telemetryFor(id: DeviceId): TelemetrySeries =
        telemetry.getOrPut(id) { TelemetrySeries() }

    /** Registra um dispositivo configurado à mão (sem discovery). */
    public fun addManualDevice(device: Device) {
        applyEvents(listOf(HomeEvent.DeviceAnnounced(device)))
        scope.launch { resubscribeAll() }
    }

    public fun removeDevice(id: DeviceId) {
        applyEvents(listOf(HomeEvent.DeviceRemoved(id)))
    }

    /** Executa uma intenção do usuário: publica e assume o estado otimista. */
    public suspend fun execute(command: DeviceCommand): Result<Unit> {
        val device = _home.value.device(command.deviceId)
            ?: return Result.failure(IllegalArgumentException("Dispositivo desconhecido."))
        val intent = CommandTranslator.translate(device, command)
            ?: return Result.failure(IllegalStateException("${device.name} não aceita este comando."))

        applyEvents(listOf(HomeEvent.OptimisticCommand(command)))
        return publish(intent)
    }

    public suspend fun publish(intent: PublishIntent): Result<Unit> {
        val result = engine.publish(intent)
        if (result.isSuccess) {
            appendTrace(
                TraceEntry(
                    direction = TraceEntry.Direction.OUT,
                    topic = intent.topic,
                    payload = intent.payload,
                    qos = intent.qos,
                    retained = intent.retained,
                    atMillis = clock.nowMillis(),
                    origin = intent.origin,
                ),
            )
        }
        return result
    }

    /** Assinatura ad-hoc feita pelo inspetor. */
    public suspend fun watch(filter: String): Result<Unit> {
        if (!Topics.isValidTopicFilter(filter)) {
            return Result.failure(IllegalArgumentException("Filtro inválido: $filter"))
        }
        subscriptionLock.withLock { manualFilters += filter }
        return runCatching { ensureSubscribed(setOf(filter)) }
    }

    public suspend fun unwatch(filter: String) {
        subscriptionLock.withLock {
            manualFilters -= filter
            subscribed -= filter
        }
        engine.unsubscribe(listOf(filter))
    }

    public fun manualSubscriptions(): Set<String> = manualFilters.toSet()

    /** Expira comandos otimistas não confirmados. Chamado por um timer da UI. */
    public fun tick() {
        applyEvents(listOf(HomeEvent.Tick))
    }

    public fun clearTrace() {
        _trace.value = emptyList()
    }

    // -- interno ------------------------------------------------------------

    private suspend fun onMessage(envelope: MqttEnvelope) {
        appendTrace(
            TraceEntry(
                direction = TraceEntry.Direction.IN,
                topic = envelope.topic,
                payload = envelope.payload,
                qos = envelope.qos,
                retained = envelope.retained,
                atMillis = envelope.receivedAtMillis,
            ),
        )

        val events = mutableListOf<HomeEvent>()
        val announcement = if (profile.autoDiscovery) {
            HomeAssistantDiscovery.parse(profile.discoveryPrefix, envelope.topic, envelope.payload)
        } else {
            null
        }

        if (announcement != null) {
            val device = announcement.device
            events += if (device == null) {
                // Remoção só chega com payload vazio, sem `unique_id` para
                // consultar — por isso guardamos qual id cada tópico de
                // discovery criou. Sem esse índice, um dispositivo retirado do
                // ar ficaria para sempre no dashboard.
                val known = discoveryIndex.remove(envelope.topic) ?: announcement.topic.deviceId
                HomeEvent.DeviceRemoved(known)
            } else {
                discoveryIndex[envelope.topic] = device.id
                HomeEvent.DeviceAnnounced(device)
            }
        } else {
            events += HomeEvent.MessageReceived(envelope.topic, envelope.payload)
        }

        val after = applyEvents(events)
        recordTelemetry(after, envelope.receivedAtMillis)

        if (announcement?.device != null) resubscribeAll()
    }

    /**
     * Aplica eventos, roda as automações sobre a transição e publica o que elas
     * pedirem. Retorna o estado resultante.
     */
    private fun applyEvents(events: List<HomeEvent>): HomeState {
        val now = clock.nowMillis()
        val before: HomeState
        val after: HomeState
        // A redução é barata e precisa ser atômica: mensagens chegam da thread
        // de rede do Netty enquanto a UI publica comandos.
        synchronized(stateGuard) {
            before = _home.value
            after = before.reduceAll(events, now)
            _home.value = after
        }
        if (before === after) return after

        val fired = ruleEngine.evaluate(before, after, now)
        if (fired.isNotEmpty()) {
            _firings.update { (fired + it).take(MAX_FIRINGS) }
            scope.launch { fired.forEach { firing -> publish(firing.intent) } }
        }
        return after
    }

    private fun recordTelemetry(state: HomeState, atMillis: Long) {
        for (device in state.devices.values) {
            val value = device.state.numeric ?: continue
            val series = telemetryFor(device.id)
            if (series.last()?.value != value || series.last()?.atMillis != atMillis) {
                series.record(Sample(atMillis, value))
            }
        }
    }

    private suspend fun resubscribeAll() {
        val wanted = buildSet {
            addAll(_home.value.requiredSubscriptions())
            addAll(manualFilters)
            addAll(profile.extraSubscriptions)
            if (profile.autoDiscovery) {
                add(HomeAssistantDiscovery.subscriptionFilter(profile.discoveryPrefix))
                add(HomeAssistantDiscovery.nodeSubscriptionFilter(profile.discoveryPrefix))
            }
        }
        ensureSubscribed(wanted)
    }

    /** Assina apenas o que ainda não está assinado — reassinar tudo a cada
     *  mensagem de discovery geraria uma tempestade de SUBSCRIBE. */
    private suspend fun ensureSubscribed(wanted: Set<String>) {
        val missing = subscriptionLock.withLock {
            val delta = wanted.filter { it.isNotBlank() && it !in subscribed }
            subscribed += delta
            delta
        }
        if (missing.isEmpty()) return
        runCatching { engine.subscribe(missing) }
            .onFailure { subscriptionLock.withLock { subscribed -= missing.toSet() } }
    }

    private fun appendTrace(entry: TraceEntry) {
        _trace.update { current ->
            val next = ArrayList<TraceEntry>(minOf(current.size + 1, traceCapacity))
            next += entry
            next += current.take(traceCapacity - 1)
            next
        }
    }

    public companion object {
        public const val DEFAULT_TRACE_CAPACITY: Int = 500
        private const val MAX_FIRINGS = 100
    }
}
