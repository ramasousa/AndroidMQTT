package com.raulsousa.pulso.domain

import kotlin.random.Random

/**
 * Estado da conexão com o broker.
 *
 * No app de 2017, `connectionLost()` era um método vazio. O usuário ficava
 * olhando uma tela que parecia funcionar enquanto nada mais chegava. Estado de
 * conexão é informação de primeira classe numa UI de IoT.
 */
public sealed interface ConnectionState {
    public data object Disconnected : ConnectionState
    public data object Connecting : ConnectionState
    public data class Connected(val sinceMillis: Long, val brokerLabel: String) : ConnectionState
    public data class Reconnecting(val attempt: Int, val nextAttemptInMillis: Long) : ConnectionState
    public data class Failed(val reason: String, val recoverable: Boolean) : ConnectionState

    public val isUsable: Boolean get() = this is Connected
}

/**
 * Backoff exponencial com *jitter*.
 *
 * Sem jitter, cinquenta telefones que perderam o mesmo Wi-Fi voltam todos
 * juntos no segundo 1, no 2, no 4 — e derrubam o broker que acabou de subir
 * (efeito manada). O jitter espalha as tentativas.
 */
public object Backoff {

    public const val DEFAULT_BASE_MILLIS: Long = 1_000
    public const val DEFAULT_MAX_MILLIS: Long = 60_000
    public const val DEFAULT_JITTER: Double = 0.25

    public fun delayFor(
        attempt: Int,
        baseMillis: Long = DEFAULT_BASE_MILLIS,
        maxMillis: Long = DEFAULT_MAX_MILLIS,
        jitter: Double = DEFAULT_JITTER,
        random: Random = Random.Default,
    ): Long {
        require(attempt >= 0) { "attempt não pode ser negativo" }
        require(jitter in 0.0..1.0) { "jitter deve estar entre 0 e 1" }

        val exponential = if (attempt >= EXPONENT_CEILING) {
            maxMillis
        } else {
            (baseMillis shl attempt).coerceAtMost(maxMillis)
        }
        if (jitter == 0.0) return exponential

        val spread = (exponential * jitter)
        val offset = random.nextDouble(-spread, spread)
        return (exponential + offset).toLong().coerceIn(baseMillis / 2, maxMillis)
    }

    // 2^30 * base já ultrapassa qualquer teto realista; evita overflow no shl.
    private const val EXPONENT_CEILING = 30
}
