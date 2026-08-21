package com.raulsousa.pulso.mqtt

import com.raulsousa.pulso.domain.DeviceCommand
import com.raulsousa.pulso.domain.DeviceId
import com.raulsousa.pulso.domain.MutableClock
import com.raulsousa.pulso.domain.Power
import com.raulsousa.pulso.domain.automation.Comparison
import com.raulsousa.pulso.domain.automation.Rule
import com.raulsousa.pulso.domain.automation.RuleAction
import com.raulsousa.pulso.domain.automation.RuleEngine
import com.raulsousa.pulso.domain.automation.Trigger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest

@OptIn(ExperimentalCoroutinesApi::class)
class MqttSessionTest {

    private val lampConfig = """
        {
          "name": "Lâmpada da sala",
          "unique_id": "lampada-sala",
          "state_topic": "casa/sala/lampada/estado",
          "command_topic": "casa/sala/lampada/set",
          "availability_topic": "casa/sala/lampada/disponivel",
          "device": { "suggested_area": "Sala" }
        }
    """.trimIndent()

    private val sensorConfig = """
        {
          "name": "Temperatura",
          "unique_id": "temp-sala",
          "state_topic": "casa/sala/clima",
          "unit_of_measurement": "°C",
          "value_template": "{{ value_json.temperatura }}"
        }
    """.trimIndent()

    private fun fixture(rules: List<Rule> = emptyList()): Fixture {
        val clock = MutableClock(1_000)
        val engine = FakeMqttEngine(clock)
        val scope = TestScope(UnconfinedTestDispatcher())
        val session = MqttSession(engine, scope, clock, RuleEngine(rules))
        return Fixture(clock, engine, session)
    }

    private data class Fixture(
        val clock: MutableClock,
        val engine: FakeMqttEngine,
        val session: MqttSession,
    )

    @Test
    fun `descoberta popula a casa e assina os topicos do dispositivo`() = runTest {
        val f = fixture()
        f.session.start(BrokerProfile.LOCAL_SIMULATOR)

        f.engine.emit("homeassistant/light/lampada/config", lampConfig, retain = true)

        val device = f.session.home.value.device(DeviceId("lampada-sala"))
        assertNotNull(device)
        assertEquals("Sala", device.room)
        assertTrue("casa/sala/lampada/estado" in f.engine.subscriptions())
        assertTrue("casa/sala/lampada/disponivel" in f.engine.subscriptions())
    }

    @Test
    fun `estado do dispositivo chega e atualiza a casa`() = runTest {
        val f = fixture()
        f.session.start(BrokerProfile.LOCAL_SIMULATOR)
        f.engine.emit("homeassistant/light/lampada/config", lampConfig, retain = true)

        f.engine.emit("casa/sala/lampada/estado", "ON")

        assertEquals(Power.ON, f.session.home.value.device(DeviceId("lampada-sala"))?.state?.power)
    }

    @Test
    fun `comando publica no topico certo e ja reflete na UI`() = runTest {
        val f = fixture()
        f.session.start(BrokerProfile.LOCAL_SIMULATOR)
        f.engine.emit("homeassistant/light/lampada/config", lampConfig, retain = true)

        val result = f.session.execute(DeviceCommand.SetPower(DeviceId("lampada-sala"), Power.ON))

        assertTrue(result.isSuccess)
        val published = f.engine.published.single()
        assertEquals("casa/sala/lampada/set", published.topic)
        assertEquals("ON", published.payload)
        assertFalse(published.retained)
        assertEquals(Power.ON, f.session.home.value.device(DeviceId("lampada-sala"))?.state?.power)
    }

    @Test
    fun `comando para dispositivo desconhecido falha sem publicar`() = runTest {
        val f = fixture()
        f.session.start(BrokerProfile.LOCAL_SIMULATOR)

        val result = f.session.execute(DeviceCommand.Toggle(DeviceId("fantasma")))

        assertTrue(result.isFailure)
        assertTrue(f.engine.published.isEmpty())
    }

    @Test
    fun `comando nao confirmado expira no tick`() = runTest {
        val f = fixture()
        f.session.start(BrokerProfile.LOCAL_SIMULATOR)
        f.engine.emit("homeassistant/light/lampada/config", lampConfig, retain = true)
        // Sem eco do firmware: o comando some no vazio.
        f.engine.onPublish = null

        f.session.execute(DeviceCommand.SetPower(DeviceId("lampada-sala"), Power.ON))
        f.clock.advanceBy(10_000)
        f.session.tick()

        assertFalse(f.session.home.value.device(DeviceId("lampada-sala"))!!.state.online)
    }

    @Test
    fun `telemetria de sensor e acumulada na serie`() = runTest {
        val f = fixture()
        f.session.start(BrokerProfile.LOCAL_SIMULATOR)
        f.engine.emit("homeassistant/sensor/temp/config", sensorConfig, retain = true)

        listOf(21.0, 22.5, 23.1).forEachIndexed { index, value ->
            f.clock.advanceBy(1_000L * (index + 1))
            f.engine.emit("casa/sala/clima", """{"temperatura": $value}""")
        }

        val series = f.session.telemetryFor(DeviceId("temp-sala"))
        assertEquals(3, series.size)
        assertEquals(23.1, series.stats()!!.last)
    }

    @Test
    fun `automacao dispara e publica sozinha ao cruzar o limiar`() = runTest {
        val rule = Rule(
            id = "ventilador",
            name = "Liga a lâmpada quando esquenta",
            trigger = Trigger.NumericThreshold(DeviceId("temp-sala"), Comparison.GREATER_THAN, 28.0, 1.0),
            action = RuleAction.SetPower(DeviceId("lampada-sala"), Power.ON),
            cooldownMillis = 0,
        )
        val f = fixture(listOf(rule))
        f.session.start(BrokerProfile.LOCAL_SIMULATOR)
        f.engine.emit("homeassistant/light/lampada/config", lampConfig, retain = true)
        f.engine.emit("homeassistant/sensor/temp/config", sensorConfig, retain = true)

        f.engine.emit("casa/sala/clima", """{"temperatura": 26.0}""")
        assertTrue(f.engine.published.isEmpty(), "abaixo do limiar não dispara")

        f.engine.emit("casa/sala/clima", """{"temperatura": 29.0}""")

        val fired = f.engine.published.singleOrNull()
        assertNotNull(fired, "a automação deveria ter publicado")
        assertEquals("casa/sala/lampada/set", fired.topic)
        assertEquals("ON", fired.payload)
        assertEquals(1, f.session.firings.value.size)
    }

    @Test
    fun `trilha registra entrada e saida`() = runTest {
        val f = fixture()
        f.session.start(BrokerProfile.LOCAL_SIMULATOR)
        f.engine.emit("homeassistant/light/lampada/config", lampConfig, retain = true)
        f.session.execute(DeviceCommand.SetPower(DeviceId("lampada-sala"), Power.ON))

        val trace = f.session.trace.value
        assertTrue(trace.any { it.direction == TraceEntry.Direction.OUT && it.topic == "casa/sala/lampada/set" })
        assertTrue(trace.any { it.direction == TraceEntry.Direction.IN })
        assertEquals(trace.sortedByDescending { it.atMillis }.first().topic, trace.first().topic)
    }

    @Test
    fun `trilha nao cresce alem da capacidade`() = runTest {
        val clock = MutableClock(0)
        val engine = FakeMqttEngine(clock)
        val session = MqttSession(engine, TestScope(UnconfinedTestDispatcher()), clock, traceCapacity = 10)
        session.start(BrokerProfile.LOCAL_SIMULATOR)
        session.watch("casa/#")

        repeat(50) { engine.emit("casa/ruido", it.toString()) }

        assertEquals(10, session.trace.value.size)
    }

    @Test
    fun `assinatura manual invalida e recusada`() = runTest {
        val f = fixture()
        f.session.start(BrokerProfile.LOCAL_SIMULATOR)

        assertTrue(f.session.watch("casa/#/estado").isFailure)
        assertTrue(f.session.watch("casa/+/estado").isSuccess)
        assertTrue("casa/+/estado" in f.session.manualSubscriptions())
    }

    @Test
    fun `remocao via discovery vazio tira o dispositivo da casa`() = runTest {
        val f = fixture()
        f.session.start(BrokerProfile.LOCAL_SIMULATOR)
        f.engine.emit("homeassistant/light/lampada/config", lampConfig, retain = true)
        assertEquals(1, f.session.home.value.devices.size)

        f.engine.emit("homeassistant/light/lampada/config", "", retain = true)

        assertTrue(f.session.home.value.devices.isEmpty())
    }

    @Test
    fun `nao reassina o mesmo filtro a cada mensagem`() = runTest {
        val f = fixture()
        f.session.start(BrokerProfile.LOCAL_SIMULATOR)
        f.engine.emit("homeassistant/light/lampada/config", lampConfig, retain = true)
        val afterFirst = f.engine.subscriptions().size

        repeat(20) { f.engine.emit("homeassistant/light/lampada/config", lampConfig, retain = true) }

        assertEquals(afterFirst, f.engine.subscriptions().size)
    }

    @Test
    fun `falha de publicacao nao entra na trilha de saida`() = runTest {
        val f = fixture()
        f.session.start(BrokerProfile.LOCAL_SIMULATOR)
        f.engine.emit("homeassistant/light/lampada/config", lampConfig, retain = true)
        f.engine.failPublish = true

        val result = f.session.execute(DeviceCommand.SetPower(DeviceId("lampada-sala"), Power.ON))

        assertTrue(result.isFailure)
        assertTrue(f.session.trace.value.none { it.direction == TraceEntry.Direction.OUT })
    }
}
