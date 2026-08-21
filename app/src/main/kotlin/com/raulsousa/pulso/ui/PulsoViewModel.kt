package com.raulsousa.pulso.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.raulsousa.pulso.AppContainer
import com.raulsousa.pulso.PulsoApplication
import com.raulsousa.pulso.data.AppSettings
import com.raulsousa.pulso.domain.DeviceCommand
import com.raulsousa.pulso.domain.DeviceId
import com.raulsousa.pulso.domain.PublishIntent
import com.raulsousa.pulso.domain.Sample
import com.raulsousa.pulso.domain.automation.Rule
import com.raulsousa.pulso.mqtt.BrokerProfile
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * Único ViewModel do app.
 *
 * Todas as telas leem os mesmos fluxos da [com.raulsousa.pulso.mqtt.MqttSession].
 * Um ViewModel por tela criaria quatro cópias do mesmo estado e quatro
 * oportunidades de elas discordarem entre si — num app de tempo real, isso
 * aparece na cara do usuário.
 */
class PulsoViewModel(private val container: AppContainer) : ViewModel() {

    private val session = container.session

    val home = session.home
    val connection = session.connection
    val trace = session.trace
    val firings = session.firings

    val settings: StateFlow<AppSettings?> = container.settingsRepository.settings
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS), null)

    private val _messages = MutableStateFlow<String?>(null)

    /** Mensagem efêmera para a snackbar. */
    val messages: StateFlow<String?> = _messages.asStateFlow()

    fun consumeMessage() {
        _messages.value = null
    }

    // -- ações sobre dispositivos -------------------------------------------

    fun toggle(id: DeviceId) = execute(DeviceCommand.Toggle(id))

    fun setBrightness(id: DeviceId, value: Int) = execute(DeviceCommand.SetBrightness(id, value))

    private fun execute(command: DeviceCommand) {
        viewModelScope.launch {
            session.execute(command).onFailure { error ->
                _messages.value = error.message ?: "Não foi possível enviar o comando."
            }
        }
    }

    fun telemetry(id: DeviceId): List<Sample> = session.telemetryFor(id).samples()

    // -- inspetor ------------------------------------------------------------

    fun watch(filter: String) {
        viewModelScope.launch {
            session.watch(filter)
                .onSuccess { _messages.value = "Assinado: $filter" }
                .onFailure { _messages.value = it.message ?: "Filtro inválido." }
        }
    }

    fun unwatch(filter: String) {
        viewModelScope.launch { session.unwatch(filter) }
    }

    fun manualSubscriptions(): Set<String> = session.manualSubscriptions()

    fun publish(topic: String, payload: String, retained: Boolean) {
        viewModelScope.launch {
            session.publish(PublishIntent(topic = topic, payload = payload, retained = retained))
                .onSuccess { _messages.value = "Publicado em $topic" }
                .onFailure { _messages.value = it.message ?: "Falha ao publicar." }
        }
    }

    fun clearTrace() = session.clearTrace()

    // -- configuração --------------------------------------------------------

    fun saveProfile(profile: BrokerProfile) {
        viewModelScope.launch {
            val problems = profile.validate()
            if (problems.isNotEmpty()) {
                _messages.value = problems.first()
                return@launch
            }
            container.settingsRepository.saveProfile(profile)
            container.settingsRepository.setDemoMode(false)
            reconnect()
            _messages.value = "Perfil salvo. Conectando a ${profile.label}."
        }
    }

    fun setDemoMode(enabled: Boolean) {
        viewModelScope.launch {
            container.settingsRepository.setDemoMode(enabled)
            reconnect()
        }
    }

    fun setKeepAlive(enabled: Boolean) {
        viewModelScope.launch { container.settingsRepository.setKeepAlive(enabled) }
    }

    fun saveRules(rules: List<Rule>) {
        viewModelScope.launch {
            container.settingsRepository.saveRules(rules)
            reconnect()
        }
    }

    private suspend fun reconnect() {
        val current = container.settingsRepository.settings.first()
        container.apply(current)
    }

    companion object {
        private const val STOP_TIMEOUT_MILLIS = 5_000L

        val Factory: ViewModelProvider.Factory = viewModelFactory {
            initializer {
                val app = this[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY] as PulsoApplication
                PulsoViewModel(app.container)
            }
        }
    }
}
