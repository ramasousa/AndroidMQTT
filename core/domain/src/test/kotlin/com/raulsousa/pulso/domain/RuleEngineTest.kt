package com.raulsousa.pulso.domain

import com.raulsousa.pulso.domain.automation.Comparison
import com.raulsousa.pulso.domain.automation.Rule
import com.raulsousa.pulso.domain.automation.RuleAction
import com.raulsousa.pulso.domain.automation.RuleEngine
import com.raulsousa.pulso.domain.automation.Trigger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class RuleEngineTest {

    private val sensorId = DeviceId("temp")
    private val fanId = DeviceId("ventilador")

    private val sensor = Device(
        id = sensorId,
        name = "Temperatura",
        kind = DeviceKind.SENSOR,
        unit = "°C",
        topics = DeviceTopics(state = "casa/temp"),
    )

    private val fan = Device(
        id = fanId,
        name = "Ventilador",
        kind = DeviceKind.SWITCH,
        topics = DeviceTopics(state = "casa/ventilador/estado", command = "casa/ventilador/set"),
    )

    private val base = HomeState.EMPTY.reduceAll(
        listOf(HomeEvent.DeviceAnnounced(sensor), HomeEvent.DeviceAnnounced(fan)),
        nowMillis = 0,
    )

    private fun withTemperature(state: HomeState, celsius: String, now: Long) =
        state.reduce(HomeEvent.MessageReceived("casa/temp", celsius), now)

    private val hotRule = Rule(
        id = "esfria",
        name = "Liga o ventilador quando passa de 28",
        trigger = Trigger.NumericThreshold(sensorId, Comparison.GREATER_THAN, 28.0, hysteresis = 1.0),
        action = RuleAction.SetPower(fanId, Power.ON),
        cooldownMillis = 10_000,
    )

    @Test
    fun `dispara ao cruzar o limiar`() {
        val engine = RuleEngine(listOf(hotRule))
        val before = withTemperature(base, "27.0", 1_000)
        val after = withTemperature(before, "28.5", 2_000)

        val firings = engine.evaluate(before, after, nowMillis = 2_000)
        assertEquals(1, firings.size)
        assertEquals("casa/ventilador/set", firings.single().intent.topic)
        assertEquals("ON", firings.single().intent.payload)
        assertEquals(PublishIntent.Origin.AUTOMATION, firings.single().intent.origin)
    }

    @Test
    fun `nao redispara enquanto continua acima do limiar`() {
        val engine = RuleEngine(listOf(hotRule))
        val s1 = withTemperature(base, "27.0", 1_000)
        val s2 = withTemperature(s1, "28.5", 2_000)
        engine.evaluate(s1, s2, 2_000)

        val s3 = withTemperature(s2, "29.0", 30_000)
        val firings = engine.evaluate(s2, s3, nowMillis = 30_000)
        assertTrue(firings.isEmpty(), "sem histerese isto viraria um pisca-pisca")
    }

    @Test
    fun `so rearma abaixo do limiar menos a histerese`() {
        val engine = RuleEngine(listOf(hotRule))
        val s1 = withTemperature(base, "27.0", 1_000)
        val s2 = withTemperature(s1, "28.5", 2_000)
        engine.evaluate(s1, s2, 2_000)

        // 27,5 está abaixo do limiar mas dentro da banda de histerese: não rearma.
        val s3 = withTemperature(s2, "27.5", 30_000)
        engine.evaluate(s2, s3, 30_000)
        val s4 = withTemperature(s3, "28.9", 40_000)
        assertTrue(engine.evaluate(s3, s4, 40_000).isEmpty())

        // 26,5 cruza a banda: rearma e volta a disparar.
        val s5 = withTemperature(s4, "26.5", 50_000)
        engine.evaluate(s4, s5, 50_000)
        val s6 = withTemperature(s5, "28.4", 60_000)
        assertEquals(1, engine.evaluate(s5, s6, 60_000).size)
    }

    @Test
    fun `cooldown segura disparos em sequencia`() {
        val rule = hotRule.copy(trigger = Trigger.PowerBecomes(fanId, Power.ON), cooldownMillis = 10_000)
        val engine = RuleEngine(listOf(rule))

        val off = base.reduce(HomeEvent.MessageReceived("casa/ventilador/estado", "OFF"), 1_000)
        val on = off.reduce(HomeEvent.MessageReceived("casa/ventilador/estado", "ON"), 2_000)
        assertEquals(1, engine.evaluate(off, on, 2_000).size)

        val off2 = on.reduce(HomeEvent.MessageReceived("casa/ventilador/estado", "OFF"), 3_000)
        val on2 = off2.reduce(HomeEvent.MessageReceived("casa/ventilador/estado", "ON"), 4_000)
        assertTrue(engine.evaluate(off2, on2, 4_000).isEmpty(), "dentro do cooldown")

        val off3 = on2.reduce(HomeEvent.MessageReceived("casa/ventilador/estado", "OFF"), 20_000)
        val on3 = off3.reduce(HomeEvent.MessageReceived("casa/ventilador/estado", "ON"), 21_000)
        assertEquals(1, engine.evaluate(off3, on3, 21_000).size, "cooldown expirou")
    }

    @Test
    fun `regra desabilitada nunca dispara`() {
        val engine = RuleEngine(listOf(hotRule.copy(enabled = false)))
        val before = withTemperature(base, "27.0", 1_000)
        val after = withTemperature(before, "30.0", 2_000)
        assertTrue(engine.evaluate(before, after, 2_000).isEmpty())
    }

    @Test
    fun `gatilho de indisponibilidade dispara publicacao crua`() {
        val engine = RuleEngine(
            listOf(
                Rule(
                    id = "alerta",
                    name = "Avisa quando o sensor cai",
                    trigger = Trigger.AvailabilityBecomes(sensorId, online = false),
                    action = RuleAction.PublishRaw("casa/alertas", "sensor offline", retained = true),
                    cooldownMillis = 0,
                ),
            ),
        )
        val sensorWithAvailability = sensor.copy(topics = sensor.topics.copy(availability = "casa/temp/disponivel"))
        val start = HomeState.EMPTY.reduce(HomeEvent.DeviceAnnounced(sensorWithAvailability), 0)
        val down = start.reduce(HomeEvent.MessageReceived("casa/temp/disponivel", "offline"), 1_000)

        val firings = engine.evaluate(start, down, 1_000)
        assertEquals(1, firings.size)
        assertEquals("casa/alertas", firings.single().intent.topic)
        assertTrue(firings.single().intent.retained)
    }

    @Test
    fun `setRules limpa estado de regras removidas`() {
        val engine = RuleEngine(listOf(hotRule))
        val s1 = withTemperature(base, "27.0", 1_000)
        val s2 = withTemperature(s1, "29.0", 2_000)
        engine.evaluate(s1, s2, 2_000)

        engine.setRules(listOf(hotRule))
        assertEquals(1, engine.rules().size)
    }
}
