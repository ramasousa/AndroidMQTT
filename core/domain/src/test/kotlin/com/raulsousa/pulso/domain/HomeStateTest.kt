package com.raulsousa.pulso.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class HomeStateTest {

    private val lampId = DeviceId("lampada-sala")
    private val sensorId = DeviceId("temp-sala")

    private val lamp = Device(
        id = lampId,
        name = "Lâmpada da sala",
        kind = DeviceKind.LIGHT,
        room = "Sala",
        supportsBrightness = true,
        topics = DeviceTopics(
            state = "casa/sala/lampada/estado",
            command = "casa/sala/lampada/set",
            brightnessState = "casa/sala/lampada/brilho",
            brightnessCommand = "casa/sala/lampada/brilho/set",
            availability = "casa/sala/lampada/disponivel",
        ),
    )

    private val sensor = Device(
        id = sensorId,
        name = "Temperatura da sala",
        kind = DeviceKind.SENSOR,
        room = "Sala",
        unit = "°C",
        topics = DeviceTopics(state = "casa/sala/clima", valueKey = "temperatura"),
    )

    private fun house(): HomeState = HomeState.EMPTY.reduceAll(
        listOf(HomeEvent.DeviceAnnounced(lamp), HomeEvent.DeviceAnnounced(sensor)),
        nowMillis = 0,
    )

    @Test
    fun `mensagem de estado atualiza o dispositivo certo`() {
        val state = house().reduce(
            HomeEvent.MessageReceived("casa/sala/lampada/estado", "ON"),
            nowMillis = 100,
        )
        assertEquals(Power.ON, state.device(lampId)?.state?.power)
        assertNull(state.device(sensorId)?.state?.power)
    }

    @Test
    fun `payload desconhecido no topico de estado nao vira potencia`() {
        val state = house().reduce(
            HomeEvent.MessageReceived("casa/sala/lampada/estado", "TALVEZ"),
            nowMillis = 100,
        )
        assertNull(state.device(lampId)?.state?.power)
        assertEquals("TALVEZ", state.device(lampId)?.state?.text)
    }

    @Test
    fun `vocabulario numerico do prototipo de 2017 continua funcionando`() {
        val legacy = lamp.copy(
            id = DeviceId("led"),
            topics = DeviceTopics(state = "led", command = "led"),
            vocabulary = PayloadVocabulary.NUMERIC,
        )
        val state = HomeState.EMPTY
            .reduce(HomeEvent.DeviceAnnounced(legacy), 0)
            .reduce(HomeEvent.MessageReceived("led", "1"), 10)

        assertEquals(Power.ON, state.device(DeviceId("led"))?.state?.power)
    }

    @Test
    fun `sensor extrai valor de payload json`() {
        val state = house().reduce(
            HomeEvent.MessageReceived("casa/sala/clima", """{"temperatura": 22.4, "umidade": 61}"""),
            nowMillis = 100,
        )
        assertEquals(22.4, state.device(sensorId)?.state?.numeric)
        assertEquals("22.4 °C", state.device(sensorId)?.displayValue())
    }

    @Test
    fun `topico de disponibilidade derruba o dispositivo`() {
        val state = house().reduce(
            HomeEvent.MessageReceived("casa/sala/lampada/disponivel", "offline"),
            nowMillis = 100,
        )
        assertFalse(state.device(lampId)!!.state.online)
        assertEquals("indisponível", state.device(lampId)!!.displayValue())
    }

    @Test
    fun `comando otimista aparece na hora e some quando confirmado`() {
        val commanded = house().reduce(
            HomeEvent.OptimisticCommand(DeviceCommand.SetPower(lampId, Power.ON)),
            nowMillis = 1_000,
        )
        assertEquals(Power.ON, commanded.device(lampId)?.state?.power)
        assertTrue(commanded.pending.containsKey(lampId))

        val confirmed = commanded.reduce(
            HomeEvent.MessageReceived("casa/sala/lampada/estado", "ON"),
            nowMillis = 1_200,
        )
        assertTrue(confirmed.pending.isEmpty())
        assertEquals(Power.ON, confirmed.device(lampId)?.state?.power)
    }

    @Test
    fun `confirmacao identica ao estado otimista tambem encerra o pendente`() {
        // O caso feliz: o dispositivo responde exatamente o que a UI ja mostrava.
        // Nao ha diferenca de estado a aplicar — mas o comando foi confirmado, e
        // deixar o pendente vivo marcaria como indisponivel quem respondeu certo.
        val commanded = house().reduce(
            HomeEvent.OptimisticCommand(DeviceCommand.SetPower(lampId, Power.ON)),
            nowMillis = 1_000,
        )
        val confirmed = commanded.reduce(
            HomeEvent.MessageReceived("casa/sala/lampada/estado", "ON"),
            nowMillis = 1_000,
        )

        assertTrue(confirmed.pending.isEmpty())
        assertTrue(
            confirmed.reduce(HomeEvent.Tick, nowMillis = 60_000).device(lampId)!!.state.online,
            "nao pode cair como indisponivel depois de ter confirmado",
        )
    }

    @Test
    fun `mensagem de outro dispositivo nao encerra o pendente`() {
        val commanded = house().reduce(
            HomeEvent.OptimisticCommand(DeviceCommand.SetPower(lampId, Power.ON)),
            nowMillis = 1_000,
        )
        val other = commanded.reduce(
            HomeEvent.MessageReceived("casa/sala/clima", """{"temperatura": 21.0}"""),
            nowMillis = 1_100,
        )

        assertTrue(other.pending.containsKey(lampId), "confirmacao tem que vir do proprio dispositivo")
    }

    @Test
    fun `comando otimista nao confirmado expira e marca indisponivel`() {
        val commanded = house().reduce(
            HomeEvent.OptimisticCommand(DeviceCommand.SetPower(lampId, Power.ON)),
            nowMillis = 1_000,
        )
        val stillWaiting = commanded.reduce(HomeEvent.Tick, nowMillis = 3_000)
        assertTrue(stillWaiting.device(lampId)!!.state.online, "ainda dentro da janela de tolerância")

        val expired = commanded.reduce(
            HomeEvent.Tick,
            nowMillis = 1_000 + HomeState.OPTIMISTIC_TIMEOUT_MILLIS,
        )
        assertFalse(expired.device(lampId)!!.state.online)
        assertTrue(expired.pending.isEmpty())
    }

    @Test
    fun `reanuncio de discovery preserva o estado conhecido`() {
        val withState = house().reduce(
            HomeEvent.MessageReceived("casa/sala/lampada/estado", "ON"),
            nowMillis = 100,
        )
        val reannounced = withState.reduce(
            HomeEvent.DeviceAnnounced(lamp.copy(name = "Lâmpada da sala (v2)")),
            nowMillis = 200,
        )
        assertEquals("Lâmpada da sala (v2)", reannounced.device(lampId)?.name)
        assertEquals(Power.ON, reannounced.device(lampId)?.state?.power, "estado não pode ser perdido")
    }

    @Test
    fun `remocao via discovery vazio tira o dispositivo do mapa`() {
        val state = house().reduce(HomeEvent.DeviceRemoved(lampId), nowMillis = 100)
        assertNull(state.device(lampId))
        assertEquals(1, state.devices.size)
    }

    @Test
    fun `assinaturas necessarias cobrem todos os topicos de leitura`() {
        assertEquals(
            setOf(
                "casa/sala/lampada/estado",
                "casa/sala/lampada/brilho",
                "casa/sala/lampada/disponivel",
                "casa/sala/clima",
            ),
            house().requiredSubscriptions(),
        )
    }

    @Test
    fun `mensagem em topico desconhecido nao aloca um novo estado`() {
        val before = house()
        val after = before.reduce(HomeEvent.MessageReceived("outra/coisa", "42"), nowMillis = 100)
        assertTrue(before === after, "reduce deve devolver a mesma instância quando nada muda")
    }
}
