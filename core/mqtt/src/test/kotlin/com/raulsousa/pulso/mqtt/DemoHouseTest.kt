package com.raulsousa.pulso.mqtt

import com.raulsousa.pulso.domain.DeviceCommand
import com.raulsousa.pulso.domain.DeviceKind
import com.raulsousa.pulso.domain.MutableClock
import com.raulsousa.pulso.domain.Power
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest

/**
 * O modo demonstração ponta a ponta.
 *
 * Este é o caminho que todo mundo percorre ao instalar o app pela primeira vez.
 * Se ele abrir vazio, o app perdeu o usuário — então vale um teste.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class DemoHouseTest {

    @Test
    fun `a casa simulada povoa a sessao sem broker nenhum`() = runTest {
        val clock = MutableClock(0)
        val engine = FakeMqttEngine(clock)
        val session = MqttSession(engine, backgroundScope, clock)
        val house = DemoHouse(engine)

        session.start(BrokerProfile(name = "Demonstração", autoDiscovery = true))
        house.start(backgroundScope)
        // `advanceTimeBy` só move o relógio virtual; quem drena as tarefas
        // imediatas — a entrega das mensagens ao coletor — é `runCurrent`.
        testScheduler.advanceTimeBy(DemoHouse.TICK_MILLIS + 1)
        testScheduler.runCurrent()

        val home = session.home.value
        assertEquals(5, home.devices.size, "a demonstração precisa abrir povoada")
        assertEquals(listOf("Entrada", "Sala", "Varanda"), home.rooms)
        assertTrue(home.orderedDevices.any { it.kind == DeviceKind.LIGHT })
        assertTrue(home.orderedDevices.any { it.kind == DeviceKind.BINARY_SENSOR })

        house.stop()
    }

    @Test
    fun `o firmware ficticio confirma o comando pelo topico de estado`() = runTest {
        val clock = MutableClock(0)
        val engine = FakeMqttEngine(clock)
        val session = MqttSession(engine, backgroundScope, clock)
        val house = DemoHouse(engine)

        session.start(BrokerProfile(name = "Demonstração", autoDiscovery = true))
        house.start(backgroundScope)
        testScheduler.runCurrent()

        val lamp = session.home.value.orderedDevices.first { it.kind == DeviceKind.LIGHT }
        assertEquals(Power.OFF, lamp.state.power)

        session.execute(DeviceCommand.Toggle(lamp.id))
        testScheduler.runCurrent()

        assertEquals(Power.ON, session.home.value.device(lamp.id)?.state?.power)
        assertTrue(
            session.home.value.pending.isEmpty(),
            "o eco do firmware deve reconciliar o comando otimista",
        )

        house.stop()
    }

    @Test
    fun `a telemetria simulada alimenta a serie temporal`() = runTest {
        val clock = MutableClock(0)
        val engine = FakeMqttEngine(clock)
        val session = MqttSession(engine, backgroundScope, clock)
        val house = DemoHouse(engine)

        session.start(BrokerProfile(name = "Demonstração", autoDiscovery = true))
        house.start(backgroundScope)

        repeat(5) {
            clock.advanceBy(DemoHouse.TICK_MILLIS)
            testScheduler.advanceTimeBy(DemoHouse.TICK_MILLIS)
            testScheduler.runCurrent()
        }

        val sensor = session.home.value.orderedDevices.first { it.kind == DeviceKind.SENSOR }
        val series = session.telemetryFor(sensor.id)
        assertTrue(series.size >= 3, "esperava várias amostras, veio ${series.size}")
        assertTrue(series.stats()!!.last in 15.0..100.0)

        house.stop()
    }
}
