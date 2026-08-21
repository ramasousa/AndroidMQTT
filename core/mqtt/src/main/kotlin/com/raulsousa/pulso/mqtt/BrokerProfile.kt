package com.raulsousa.pulso.mqtt

import kotlinx.serialization.Serializable

/**
 * Configuração de um broker.
 *
 * O app de 2017 tinha isto: `"tcp://187.191.113.52:80"`, constante, em texto
 * puro, dentro do APK, na porta 80. Três problemas em uma linha só — endereço
 * fixo, sem TLS e numa porta que atravessa proxy corporativo justamente porque
 * finge ser HTTP. Aqui é dado de configuração, com TLS por padrão.
 */
@Serializable
public data class BrokerProfile(
    val name: String = "Local",
    val host: String = "10.0.2.2",
    val port: Int = DEFAULT_TLS_PORT,
    val useTls: Boolean = true,
    val username: String? = null,
    /**
     * Nunca serialize isto em texto puro no disco. Na camada Android este campo
     * é preenchido a partir do EncryptedSharedPreferences/Keystore.
     */
    @kotlinx.serialization.Transient
    val password: String? = null,
    val clientId: String = "",
    /**
     * `false` mantém a sessão no broker: mensagens QoS 1/2 publicadas enquanto o
     * telefone estava sem rede são entregues na volta. É o que transforma o app
     * de "controle remoto" em "cliente de verdade".
     */
    val cleanStart: Boolean = false,
    val keepAliveSeconds: Int = 60,
    val sessionExpirySeconds: Long = 3_600,
    /** Prefixo do MQTT Discovery (padrão do Home Assistant). */
    val discoveryPrefix: String = "homeassistant",
    val autoDiscovery: Boolean = true,
    /** Filtros extras assinados além dos dispositivos conhecidos. */
    val extraSubscriptions: List<String> = emptyList(),
) {
    public val label: String get() = "${if (useTls) "mqtts" else "mqtt"}://$host:$port"

    /** Última vontade: o broker avisa a casa quando este cliente cai de forma suja. */
    public val lastWillTopic: String get() = "pulso/clientes/${clientId.ifBlank { "anonimo" }}/estado"

    public fun validate(): List<String> = buildList {
        if (host.isBlank()) add("Informe o endereço do broker.")
        if (port !in 1..65_535) add("Porta inválida: $port.")
        if (!useTls && !isLoopbackOrPrivate(host)) {
            add("Sem TLS, credenciais e comandos trafegam em texto puro fora da sua rede.")
        }
        if (username.isNullOrBlank() && !password.isNullOrBlank()) {
            add("Senha sem usuário não é aceita pelo broker.")
        }
        if (discoveryPrefix.isBlank()) add("Prefixo de discovery não pode ser vazio.")
    }

    public companion object {
        public const val DEFAULT_TLS_PORT: Int = 8883
        public const val DEFAULT_PLAIN_PORT: Int = 1883

        /**
         * Perfil que aponta para o `docker compose` de `tools/simulator`.
         * `10.0.2.2` é o host visto de dentro do emulador Android.
         */
        public val LOCAL_SIMULATOR: BrokerProfile = BrokerProfile(
            name = "Simulador local",
            host = "10.0.2.2",
            port = DEFAULT_PLAIN_PORT,
            useTls = false,
        )

        private val privatePrefixes = listOf("10.", "192.168.", "172.16.", "127.", "localhost")

        internal fun isLoopbackOrPrivate(host: String): Boolean =
            privatePrefixes.any { host.startsWith(it) } || host.endsWith(".local")
    }
}
