package com.raulsousa.pulso.domain

/** Qualidade de serviço MQTT. */
public enum class Qos(public val level: Int) {
    AT_MOST_ONCE(0),
    AT_LEAST_ONCE(1),
    EXACTLY_ONCE(2),
    ;

    public companion object {
        public fun of(level: Int): Qos = entries.firstOrNull { it.level == level } ?: AT_LEAST_ONCE
    }
}

/**
 * Uma publicação pronta para ir ao broker. É o *único* jeito de o domínio pedir
 * uma escrita: nada aqui conhece HiveMQ, Paho ou sockets. A camada de transporte
 * recebe isto e publica.
 */
public data class PublishIntent(
    val topic: String,
    val payload: String,
    val qos: Qos = Qos.AT_LEAST_ONCE,
    val retained: Boolean = false,
    /** Só para trilha/telemetria: quem originou esta publicação. */
    val origin: Origin = Origin.USER,
) {
    public enum class Origin { USER, AUTOMATION, SYSTEM }
}

/** Intenção do usuário sobre um dispositivo, antes de virar bytes. */
public sealed interface DeviceCommand {
    public val deviceId: DeviceId

    public data class SetPower(override val deviceId: DeviceId, val power: Power) : DeviceCommand
    public data class Toggle(override val deviceId: DeviceId) : DeviceCommand
    public data class SetBrightness(override val deviceId: DeviceId, val brightness: Int) : DeviceCommand
}

/**
 * Traduz um [DeviceCommand] em [PublishIntent].
 *
 * Retorna `null` quando o comando não faz sentido para aquele dispositivo
 * (sensor não liga, dispositivo sem tópico de comando não recebe ordem). Erros
 * de modelagem viram `null` aqui, e não exceção em runtime na thread de rede —
 * que é exatamente onde o app de 2017 estourava.
 */
public object CommandTranslator {

    public fun translate(device: Device, command: DeviceCommand): PublishIntent? {
        if (!device.kind.isActuator) return null
        return when (command) {
            is DeviceCommand.SetPower -> powerIntent(device, command.power)
            is DeviceCommand.Toggle -> powerIntent(device, device.state.power.toggled())
            is DeviceCommand.SetBrightness -> brightnessIntent(device, command.brightness)
        }
    }

    /**
     * Estado otimista: o que a UI deve mostrar imediatamente após enviar o
     * comando, antes de o broker confirmar pelo tópico de estado. Se a
     * confirmação não vier, [HomeState] reverte no timeout.
     */
    public fun optimisticState(device: Device, command: DeviceCommand, nowMillis: Long): DeviceState? {
        if (!device.kind.isActuator) return null
        return when (command) {
            is DeviceCommand.SetPower ->
                device.state.copy(power = command.power, updatedAtMillis = nowMillis)

            is DeviceCommand.Toggle ->
                device.state.copy(power = device.state.power.toggled(), updatedAtMillis = nowMillis)

            is DeviceCommand.SetBrightness -> device.state.copy(
                brightness = command.brightness.coerceIn(BRIGHTNESS_MIN, BRIGHTNESS_MAX),
                power = if (command.brightness > 0) Power.ON else Power.OFF,
                updatedAtMillis = nowMillis,
            )
        }
    }

    private fun powerIntent(device: Device, power: Power): PublishIntent? {
        val topic = device.topics.command ?: return null
        return PublishIntent(
            topic = topic,
            payload = device.vocabulary.encode(power),
            qos = Qos.AT_LEAST_ONCE,
            // Retained em comando é uma armadilha clássica (ver docs/ANALISE.md):
            // o estado desejado fica preso no broker e reaplica a cada boot.
            retained = false,
        )
    }

    private fun brightnessIntent(device: Device, brightness: Int): PublishIntent? {
        if (!device.supportsBrightness) return null
        val topic = device.topics.brightnessCommand ?: return null
        return PublishIntent(
            topic = topic,
            payload = brightness.coerceIn(BRIGHTNESS_MIN, BRIGHTNESS_MAX).toString(),
            qos = Qos.AT_LEAST_ONCE,
            retained = false,
        )
    }

    private fun Power?.toggled(): Power = if (this == Power.ON) Power.OFF else Power.ON

    public const val BRIGHTNESS_MIN: Int = 0
    public const val BRIGHTNESS_MAX: Int = 255
}
