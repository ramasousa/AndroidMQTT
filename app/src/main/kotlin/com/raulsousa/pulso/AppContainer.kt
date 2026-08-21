package com.raulsousa.pulso

import android.content.Context
import com.raulsousa.pulso.data.AppSettings
import com.raulsousa.pulso.data.SecretStore
import com.raulsousa.pulso.data.SettingsRepository
import com.raulsousa.pulso.domain.Clock
import com.raulsousa.pulso.domain.automation.RuleEngine
import com.raulsousa.pulso.mqtt.BrokerProfile
import com.raulsousa.pulso.mqtt.DemoHouse
import com.raulsousa.pulso.mqtt.FakeMqttEngine
import com.raulsousa.pulso.mqtt.HiveMqEngine
import com.raulsousa.pulso.mqtt.MqttSession
import com.raulsousa.pulso.mqtt.SwitchableMqttEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Injeção de dependência à mão.
 *
 * Um app deste tamanho não precisa de Hilt: o grafo é pequeno, explícito e cabe
 * numa tela. Trocar isto por um framework depois é mecânico; começar com um
 * framework é carregar processador de anotação, tempo de build e indireção
 * antes de existir problema para resolver.
 */
class AppContainer(context: Context) {

    val applicationScope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val secretStore = SecretStore(context.applicationContext)
    val settingsRepository: SettingsRepository = SettingsRepository(context.applicationContext, secretStore)

    private val liveEngine = HiveMqEngine()
    private val demoEngine = FakeMqttEngine()
    private val demoHouse = DemoHouse(demoEngine)

    private val engine = SwitchableMqttEngine(demoEngine, applicationScope)
    private val ruleEngine = RuleEngine()

    val session: MqttSession = MqttSession(
        engine = engine,
        scope = applicationScope,
        clock = Clock.System,
        ruleEngine = ruleEngine,
    )

    private val _activeSettings = MutableStateFlow<AppSettings?>(null)
    val activeSettings: StateFlow<AppSettings?> = _activeSettings.asStateFlow()

    init {
        // Expira comandos otimistas que nunca foram confirmados. Um timer só,
        // no escopo do app, em vez de um por tela.
        applicationScope.launch {
            while (isActive) {
                delay(TICK_INTERVAL_MILLIS)
                session.tick()
            }
        }
    }

    /** Aplica a configuração: escolhe o motor, conecta e carrega as regras. */
    suspend fun apply(settings: AppSettings) {
        _activeSettings.value = settings
        session.setRules(settings.rules)

        if (settings.demoMode) {
            demoHouse.stop()
            engine.switchTo(demoEngine)
            session.start(BrokerProfile(name = "Demonstração", autoDiscovery = true))
            demoHouse.start(applicationScope)
        } else {
            demoHouse.stop()
            engine.switchTo(liveEngine)
            session.start(settings.profile)
        }
    }

    suspend fun disconnect() {
        demoHouse.stop()
        session.stop()
    }

    private companion object {
        const val TICK_INTERVAL_MILLIS = 1_000L
    }
}
