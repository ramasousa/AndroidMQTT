package com.raulsousa.pulso.mqtt

import com.raulsousa.pulso.domain.DeviceKind
import com.raulsousa.pulso.domain.HomeEvent
import com.raulsousa.pulso.domain.HomeState
import com.raulsousa.pulso.domain.Power
import com.raulsousa.pulso.mqtt.discovery.HomeAssistantDiscovery
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Teste de contrato entre o simulador Python e o app.
 *
 * A fixture `simulator-discovery.json` é gerada pelo próprio simulador:
 *
 *     python tools/simulator/devices.py --dump-discovery \
 *         > core/mqtt/src/test/resources/simulator-discovery.json
 *
 * A CI regenera e compara — se alguém mudar um payload em `devices.py` sem
 * regenerar, o build acusa. Um contrato entre dois processos escritos em
 * linguagens diferentes precisa ser verificado, não combinado de boca.
 */
class SimulatorContractTest {

    private val announcements: List<Pair<String, String>> by lazy {
        val stream = javaClass.classLoader.getResourceAsStream(FIXTURE)
            ?: error("Fixture $FIXTURE ausente. Rode o comando descrito no KDoc.")
        val text = stream.bufferedReader().use { it.readText() }
        (Json.parseToJsonElement(text) as JsonArray).map { element ->
            val obj = element as JsonObject
            (obj["topic"] as JsonPrimitive).content to (obj["payload"] as JsonPrimitive).content
        }
    }

    @Test
    fun `o app entende tudo o que o simulador anuncia`() {
        assertTrue(announcements.isNotEmpty(), "a fixture não pode estar vazia")

        announcements.forEach { (topic, payload) ->
            val parsed = HomeAssistantDiscovery.parse("homeassistant", topic, payload)
            assertNotNull(parsed, "tópico não reconhecido como discovery: $topic")
            val device = parsed.device
            assertNotNull(device, "payload não virou dispositivo: $topic")
            assertTrue(device.name.isNotBlank(), "dispositivo sem nome: $topic")
            assertTrue(device.kind != DeviceKind.UNKNOWN, "tipo desconhecido em $topic")
            assertTrue(device.topics.state != null, "dispositivo sem tópico de estado: $topic")
        }
    }

    @Test
    fun `a casa simulada monta o dashboard esperado`() {
        val house = announcements.fold(HomeState.EMPTY) { state, (topic, payload) ->
            val device = HomeAssistantDiscovery.parse("homeassistant", topic, payload)?.device
            if (device == null) state else state.reduce(HomeEvent.DeviceAnnounced(device), nowMillis = 0)
        }

        assertEquals(5, house.devices.size)
        assertEquals(listOf("Entrada", "Sala", "Varanda"), house.rooms)
        assertEquals(2, house.orderedDevices.count { it.isControllable })
        assertEquals(2, house.orderedDevices.count { it.kind == DeviceKind.SENSOR })
    }

    @Test
    fun `o fluxo completo do simulador funciona ponta a ponta`() {
        var house = announcements.fold(HomeState.EMPTY) { state, (topic, payload) ->
            val device = HomeAssistantDiscovery.parse("homeassistant", topic, payload)!!.device!!
            state.reduce(HomeEvent.DeviceAnnounced(device), nowMillis = 0)
        }

        // Telemetria no formato exato que devices.py publica.
        house = house.reduce(
            HomeEvent.MessageReceived("casa/sala/clima", """{"temperatura": 24.7, "umidade": 58}"""),
            nowMillis = 1_000,
        )
        val temperature = house.orderedDevices.first { it.name == "Temperatura" }
        val humidity = house.orderedDevices.first { it.name == "Umidade" }
        assertEquals(24.7, temperature.state.numeric)
        assertEquals(58.0, humidity.state.numeric)
        assertEquals("24.7 °C", temperature.displayValue())

        // Os dois sensores compartilham o mesmo tópico e são desambiguados
        // apenas pelo value_template — se o parser errar isso, os dois mostram
        // o mesmo número.
        assertTrue(temperature.state.numeric != humidity.state.numeric)

        house = house.reduce(HomeEvent.MessageReceived("casa/entrada/porta", "aberta"), nowMillis = 2_000)
        val door = house.orderedDevices.first { it.kind == DeviceKind.BINARY_SENSOR }
        assertEquals(Power.ON, door.state.power)
        assertEquals("aberto", door.displayValue())
    }

    private companion object {
        const val FIXTURE = "simulator-discovery.json"
    }
}
