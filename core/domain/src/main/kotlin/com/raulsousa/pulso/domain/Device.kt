package com.raulsousa.pulso.domain

import kotlinx.serialization.Serializable

/** Identidade estável de um dispositivo, independente do tópico em que ele fala. */
@JvmInline
@Serializable
public value class DeviceId(public val value: String) {
    override fun toString(): String = value
}

/**
 * O que o dispositivo *é*, do ponto de vista da interface.
 *
 * O app de 2017 tinha um tipo implícito e único: lâmpada. Aqui o tipo é dado,
 * não código — o que permite a mesma tela renderizar qualquer coisa que o
 * broker anuncie.
 */
public enum class DeviceKind {
    LIGHT,
    SWITCH,
    SENSOR,
    BINARY_SENSOR,
    UNKNOWN,
    ;

    public val isActuator: Boolean get() = this == LIGHT || this == SWITCH
}

public enum class Power { ON, OFF }

/**
 * Vocabulário de payload do dispositivo. Nem todo firmware fala `ON`/`OFF`:
 * o ESP8266 do projeto original falava `"1"` e `"0"`, Tasmota fala `ON`/`OFF`,
 * Zigbee2MQTT manda JSON. Em vez de espalhar `if (payload == "1")` pelo código,
 * cada dispositivo carrega seu próprio dicionário.
 */
@Serializable
public data class PayloadVocabulary(
    val on: String = "ON",
    val off: String = "OFF",
    val available: String = "online",
    val unavailable: String = "offline",
) {
    public fun parsePower(payload: String): Power? = when (payload.trim()) {
        on -> Power.ON
        off -> Power.OFF
        else -> null
    }

    public fun encode(power: Power): String = when (power) {
        Power.ON -> on
        Power.OFF -> off
    }

    public companion object {
        /** O dialeto do protótipo de 2017: `"1"` liga, `"0"` desliga. */
        public val NUMERIC: PayloadVocabulary = PayloadVocabulary(on = "1", off = "0")
    }
}

/**
 * Onde o dispositivo fala e escuta. Separar tópicos de comando e de estado é o
 * que permite *optimistic UI* com reconciliação: a gente publica no comando,
 * mostra o estado otimista, e o estado real chega pelo tópico de estado.
 */
@Serializable
public data class DeviceTopics(
    val state: String? = null,
    val command: String? = null,
    val brightnessState: String? = null,
    val brightnessCommand: String? = null,
    val availability: String? = null,
    /**
     * Se o payload for JSON, a chave de onde extrair o valor.
     * Equivalente simplificado ao `value_template` do Home Assistant — cobre o
     * caso comum `{"temperature": 22.4}` sem embarcar um motor de templates.
     */
    val valueKey: String? = null,
) {
    /** Todos os filtros que precisam ser assinados para este dispositivo funcionar. */
    public fun subscriptions(): List<String> =
        listOfNotNull(state, brightnessState, availability).distinct()
}

/** O que sabemos sobre o dispositivo agora. Imutável — trocado a cada evento. */
@Serializable
public data class DeviceState(
    val power: Power? = null,
    /** 0..255, convenção do Home Assistant para brilho. */
    val brightness: Int? = null,
    val numeric: Double? = null,
    val text: String? = null,
    val online: Boolean = true,
    val updatedAtMillis: Long? = null,
) {
    public val hasValue: Boolean
        get() = power != null || brightness != null || numeric != null || text != null
}

/**
 * Um dispositivo controlável ou observável. É um *dado*, não uma classe com
 * comportamento: quem decide o que publicar é [DeviceCommand], quem decide como
 * o estado evolui é [HomeState]. Isso mantém tudo testável e serializável.
 */
@Serializable
public data class Device(
    val id: DeviceId,
    val name: String,
    val kind: DeviceKind,
    val topics: DeviceTopics,
    val room: String? = null,
    val vocabulary: PayloadVocabulary = PayloadVocabulary(),
    val unit: String? = null,
    val icon: String? = null,
    val supportsBrightness: Boolean = false,
    val state: DeviceState = DeviceState(),
    /** `true` quando veio do MQTT Discovery, `false` quando foi criado à mão. */
    val discovered: Boolean = false,
) {
    public val isControllable: Boolean
        get() = kind.isActuator && topics.command != null

    /** Rótulo pronto para a UI: `22.4 °C`, `Ligada`, `Aberta`, ou `—`. */
    public fun displayValue(): String = when {
        !state.online -> "indisponível"
        state.numeric != null -> formatNumeric(state.numeric, unit)
        state.power == Power.ON -> if (kind == DeviceKind.BINARY_SENSOR) "aberto" else "ligado"
        state.power == Power.OFF -> if (kind == DeviceKind.BINARY_SENSOR) "fechado" else "desligado"
        state.text != null -> state.text
        else -> "—"
    }

    private fun formatNumeric(value: Double, unit: String?): String {
        val rounded = kotlin.math.round(value * 10) / 10
        val text = if (rounded % 1.0 == 0.0) rounded.toInt().toString() else rounded.toString()
        return if (unit.isNullOrBlank()) text else "$text $unit"
    }
}
