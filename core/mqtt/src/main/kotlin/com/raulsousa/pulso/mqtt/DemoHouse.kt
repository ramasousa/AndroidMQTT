package com.raulsousa.pulso.mqtt

import kotlin.math.sin
import kotlin.random.Random
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Uma casa fictícia que se anuncia por MQTT Discovery e vive sozinha.
 *
 * Serve ao **modo demonstração**: o app abre já povoado, com lâmpada, tomada,
 * sensor de temperatura, umidade e sensor de porta, todos reagindo a comando e
 * publicando telemetria. Nenhum broker, nenhum hardware, nenhuma rede.
 *
 * O simulador de verdade, para uso com broker real, está em `tools/simulator/`
 * e publica exatamente os mesmos payloads — o app não distingue um do outro.
 */
public class DemoHouse(
    private val engine: FakeMqttEngine,
    private val prefix: String = "homeassistant",
    private val seed: Int = 1_312,
) {

    private var job: Job? = null

    public fun start(scope: CoroutineScope) {
        job?.cancel()
        job = scope.launch {
            announceAll()
            wireFirmware()
            simulateForever()
        }
    }

    public fun stop() {
        job?.cancel()
        job = null
    }

    private suspend fun announceAll() {
        announce(
            component = "light",
            objectId = "lampada_sala",
            config = """
                {
                  "name": "Lâmpada da sala",
                  "unique_id": "demo-lampada-sala",
                  "state_topic": "casa/sala/lampada/estado",
                  "command_topic": "casa/sala/lampada/set",
                  "brightness_state_topic": "casa/sala/lampada/brilho",
                  "brightness_command_topic": "casa/sala/lampada/brilho/set",
                  "availability_topic": "casa/sala/lampada/disponivel",
                  "device": { "name": "Nó da sala", "suggested_area": "Sala" }
                }
            """.trimIndent(),
        )
        announce(
            component = "switch",
            objectId = "tomada_varanda",
            config = """
                {
                  "name": "Tomada da varanda",
                  "unique_id": "demo-tomada-varanda",
                  "stat_t": "casa/varanda/tomada/estado",
                  "cmd_t": "casa/varanda/tomada/set",
                  "pl_on": "ON",
                  "pl_off": "OFF",
                  "dev": { "name": "Nó da varanda", "sa": "Varanda" }
                }
            """.trimIndent(),
        )
        announce(
            component = "sensor",
            objectId = "temperatura_sala",
            config = """
                {
                  "name": "Temperatura",
                  "unique_id": "demo-temp-sala",
                  "state_topic": "casa/sala/clima",
                  "unit_of_measurement": "°C",
                  "value_template": "{{ value_json.temperatura }}",
                  "device": { "name": "Nó da sala", "suggested_area": "Sala" }
                }
            """.trimIndent(),
        )
        announce(
            component = "sensor",
            objectId = "umidade_sala",
            config = """
                {
                  "name": "Umidade",
                  "unique_id": "demo-umidade-sala",
                  "state_topic": "casa/sala/clima",
                  "unit_of_measurement": "%",
                  "value_template": "{{ value_json.umidade }}",
                  "device": { "name": "Nó da sala", "suggested_area": "Sala" }
                }
            """.trimIndent(),
        )
        announce(
            component = "binary_sensor",
            objectId = "porta_entrada",
            config = """
                {
                  "name": "Porta de entrada",
                  "unique_id": "demo-porta-entrada",
                  "state_topic": "casa/entrada/porta",
                  "payload_on": "aberta",
                  "payload_off": "fechada",
                  "device": { "name": "Nó da entrada", "suggested_area": "Entrada" }
                }
            """.trimIndent(),
        )

        // Estado inicial retido, como faria um firmware bem-comportado.
        engine.emit("casa/sala/lampada/disponivel", "online", retain = true)
        engine.emit("casa/sala/lampada/estado", "OFF", retain = true)
        engine.emit("casa/sala/lampada/brilho", "128", retain = true)
        engine.emit("casa/varanda/tomada/estado", "ON", retain = true)
        engine.emit("casa/entrada/porta", "fechada", retain = true)
    }

    private suspend fun announce(component: String, objectId: String, config: String) {
        engine.emit("$prefix/$component/$objectId/config", config, retain = true)
    }

    /** O firmware fictício: responde a comandos publicando o estado novo. */
    private fun wireFirmware() {
        engine.onPublish = { intent ->
            when (intent.topic) {
                "casa/sala/lampada/set" ->
                    engine.emit("casa/sala/lampada/estado", intent.payload, retain = true)

                "casa/sala/lampada/brilho/set" -> {
                    engine.emit("casa/sala/lampada/brilho", intent.payload, retain = true)
                    val on = (intent.payload.toIntOrNull() ?: 0) > 0
                    engine.emit("casa/sala/lampada/estado", if (on) "ON" else "OFF", retain = true)
                }

                "casa/varanda/tomada/set" ->
                    engine.emit("casa/varanda/tomada/estado", intent.payload, retain = true)
            }
        }
    }

    private suspend fun simulateForever() {
        val random = Random(seed)
        var step = 0
        while (currentCoroutineContext().isActive) {
            // Curva senoidal + ruído: parece medição real, sem ser aleatório puro.
            val temperature = 24.0 + 4.0 * sin(step / 18.0) + random.nextDouble(-0.3, 0.3)
            val humidity = 55.0 + 8.0 * sin(step / 27.0) + random.nextDouble(-1.0, 1.0)
            engine.emit(
                topic = "casa/sala/clima",
                payload = """{"temperatura": ${temperature.round(1)}, "umidade": ${humidity.round(0)}}""",
                retain = true,
            )

            if (step % 12 == 7) {
                engine.emit("casa/entrada/porta", "aberta", retain = true)
            } else if (step % 12 == 9) {
                engine.emit("casa/entrada/porta", "fechada", retain = true)
            }

            step++
            delay(TICK_MILLIS)
        }
    }

    private fun Double.round(decimals: Int): Double {
        var factor = 1.0
        repeat(decimals) { factor *= 10 }
        return kotlin.math.round(this * factor) / factor
    }

    public companion object {
        public const val TICK_MILLIS: Long = 2_000
    }
}
