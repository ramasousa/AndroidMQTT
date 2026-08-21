package com.raulsousa.pulso.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.raulsousa.pulso.domain.automation.Rule
import com.raulsousa.pulso.mqtt.BrokerProfile
import com.raulsousa.pulso.mqtt.HiveMqEngine
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

/** O que o app precisa lembrar entre execuções. */
data class AppSettings(
    val profile: BrokerProfile,
    val demoMode: Boolean,
    val keepAlive: Boolean,
    val rules: List<Rule>,
)

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "pulso")

/**
 * Configuração persistida.
 *
 * O endereço do broker era uma constante compilada no app de 2017 — trocar de
 * casa exigia recompilar. Aqui é dado, e o app abre em modo demonstração até
 * que alguém informe um broker de verdade.
 *
 * A senha **não** vive aqui. O DataStore de preferências é um arquivo comum no
 * diretório do app; num aparelho com root ou num backup mal configurado, ele é
 * legível. Credencial fica em [SecretStore], sobre o Keystore do Android.
 */
class SettingsRepository(private val context: Context, private val secrets: SecretStore) {

    private val json = Json { ignoreUnknownKeys = true }
    private val rulesSerializer = ListSerializer(Rule.serializer())

    val settings: Flow<AppSettings> = context.dataStore.data.map { prefs ->
        val profile = BrokerProfile(
            name = prefs[Keys.NAME] ?: "Meu broker",
            host = prefs[Keys.HOST] ?: BrokerProfile.LOCAL_SIMULATOR.host,
            port = prefs[Keys.PORT] ?: BrokerProfile.DEFAULT_PLAIN_PORT,
            useTls = prefs[Keys.TLS] ?: false,
            username = prefs[Keys.USER],
            password = secrets.readPassword(),
            clientId = prefs[Keys.CLIENT_ID] ?: newClientId(),
            cleanStart = prefs[Keys.CLEAN_START] ?: false,
            discoveryPrefix = prefs[Keys.DISCOVERY_PREFIX] ?: "homeassistant",
            autoDiscovery = prefs[Keys.AUTO_DISCOVERY] ?: true,
        )
        AppSettings(
            profile = profile,
            // Primeira execução cai no modo demonstração: o app abre com uma
            // casa funcionando em vez de uma tela vazia pedindo um IP.
            demoMode = prefs[Keys.DEMO] ?: true,
            keepAlive = prefs[Keys.KEEP_ALIVE] ?: false,
            rules = prefs[Keys.RULES]?.let { stored ->
                runCatching { json.decodeFromString(rulesSerializer, stored) }.getOrDefault(emptyList())
            } ?: emptyList(),
        )
    }

    suspend fun saveProfile(profile: BrokerProfile) {
        secrets.writePassword(profile.password)
        context.dataStore.edit { prefs ->
            prefs[Keys.NAME] = profile.name
            prefs[Keys.HOST] = profile.host
            prefs[Keys.PORT] = profile.port
            prefs[Keys.TLS] = profile.useTls
            prefs[Keys.CLEAN_START] = profile.cleanStart
            prefs[Keys.DISCOVERY_PREFIX] = profile.discoveryPrefix
            prefs[Keys.AUTO_DISCOVERY] = profile.autoDiscovery
            prefs[Keys.CLIENT_ID] = profile.clientId.ifBlank { newClientId() }
            val user = profile.username
            if (user.isNullOrBlank()) prefs.remove(Keys.USER) else prefs[Keys.USER] = user
        }
    }

    suspend fun setDemoMode(enabled: Boolean) {
        context.dataStore.edit { it[Keys.DEMO] = enabled }
    }

    suspend fun setKeepAlive(enabled: Boolean) {
        context.dataStore.edit { it[Keys.KEEP_ALIVE] = enabled }
    }

    suspend fun saveRules(rules: List<Rule>) {
        context.dataStore.edit { it[Keys.RULES] = json.encodeToString(rulesSerializer, rules) }
    }

    private fun newClientId(): String = HiveMqEngine.generateClientId()

    private object Keys {
        val NAME = stringPreferencesKey("broker_name")
        val HOST = stringPreferencesKey("broker_host")
        val PORT = intPreferencesKey("broker_port")
        val TLS = booleanPreferencesKey("broker_tls")
        val USER = stringPreferencesKey("broker_user")
        val CLIENT_ID = stringPreferencesKey("client_id")
        val CLEAN_START = booleanPreferencesKey("clean_start")
        val DISCOVERY_PREFIX = stringPreferencesKey("discovery_prefix")
        val AUTO_DISCOVERY = booleanPreferencesKey("auto_discovery")
        val DEMO = booleanPreferencesKey("demo_mode")
        val KEEP_ALIVE = booleanPreferencesKey("keep_alive")
        val RULES = stringPreferencesKey("rules_json")
    }
}
