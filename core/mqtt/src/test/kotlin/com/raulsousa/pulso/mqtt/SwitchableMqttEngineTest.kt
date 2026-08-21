package com.raulsousa.pulso.mqtt

import com.raulsousa.pulso.domain.MutableClock
import com.raulsousa.pulso.domain.PublishIntent
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest

@OptIn(ExperimentalCoroutinesApi::class)
class SwitchableMqttEngineTest {

    @Test
    fun `publicacao vai para o motor ativo`() = runTest {
        val clock = MutableClock(0)
        val first = FakeMqttEngine(clock)
        val second = FakeMqttEngine(clock)
        val engine = SwitchableMqttEngine(first, TestScope(UnconfinedTestDispatcher()))

        engine.connect(BrokerProfile.LOCAL_SIMULATOR)
        engine.publish(PublishIntent("a", "1"))
        assertEquals(1, first.published.size)

        engine.switchTo(second)
        engine.connect(BrokerProfile.LOCAL_SIMULATOR)
        engine.publish(PublishIntent("b", "2"))

        assertEquals(1, first.published.size, "o motor antigo não pode continuar recebendo")
        assertEquals(1, second.published.size)
    }

    @Test
    fun `troca desconecta o motor anterior`() = runTest {
        val clock = MutableClock(0)
        val first = FakeMqttEngine(clock)
        val engine = SwitchableMqttEngine(first, TestScope(UnconfinedTestDispatcher()))

        engine.connect(BrokerProfile.LOCAL_SIMULATOR)
        engine.subscribe(listOf("casa/#"))
        assertTrue(first.subscriptions().isNotEmpty())

        engine.switchTo(FakeMqttEngine(clock))

        assertTrue(first.subscriptions().isEmpty(), "desconectar limpa as assinaturas do motor antigo")
    }

    @Test
    fun `estado observado segue o motor ativo`() = runTest {
        val clock = MutableClock(0)
        val first = FakeMqttEngine(clock)
        val second = FakeMqttEngine(clock)
        val engine = SwitchableMqttEngine(first, TestScope(UnconfinedTestDispatcher()))

        engine.connect(BrokerProfile.LOCAL_SIMULATOR)
        assertTrue(engine.state.value.isUsable)

        engine.switchTo(second)
        assertTrue(!engine.state.value.isUsable, "motor novo começa desconectado")

        engine.connect(BrokerProfile(name = "outro", host = "casa.local"))
        assertTrue(engine.state.value.isUsable)
    }
}
