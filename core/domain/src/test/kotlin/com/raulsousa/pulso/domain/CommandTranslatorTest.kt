package com.raulsousa.pulso.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull

class CommandTranslatorTest {

    private val lamp = Device(
        id = DeviceId("lampada"),
        name = "Lâmpada",
        kind = DeviceKind.LIGHT,
        supportsBrightness = true,
        topics = DeviceTopics(
            state = "casa/lampada/estado",
            command = "casa/lampada/set",
            brightnessCommand = "casa/lampada/brilho/set",
        ),
    )

    @Test
    fun `toggle inverte o estado atual`() {
        val ligada = lamp.copy(state = DeviceState(power = Power.ON))
        val intent = CommandTranslator.translate(ligada, DeviceCommand.Toggle(lamp.id))!!
        assertEquals("OFF", intent.payload)
        assertEquals("casa/lampada/set", intent.topic)
    }

    @Test
    fun `toggle de estado desconhecido liga`() {
        val intent = CommandTranslator.translate(lamp, DeviceCommand.Toggle(lamp.id))!!
        assertEquals("ON", intent.payload)
    }

    @Test
    fun `comando nunca sai retido`() {
        val intent = CommandTranslator.translate(lamp, DeviceCommand.SetPower(lamp.id, Power.ON))!!
        assertFalse(
            intent.retained,
            "comando retido reaplica sozinho a cada reconexão do dispositivo — ver docs/ANALISE.md",
        )
    }

    @Test
    fun `sensor nao aceita comando`() {
        val sensor = lamp.copy(kind = DeviceKind.SENSOR)
        assertNull(CommandTranslator.translate(sensor, DeviceCommand.Toggle(sensor.id)))
        assertNull(CommandTranslator.optimisticState(sensor, DeviceCommand.Toggle(sensor.id), 0))
    }

    @Test
    fun `dispositivo sem topico de comando nao gera publicacao`() {
        val readOnly = lamp.copy(topics = lamp.topics.copy(command = null))
        assertNull(CommandTranslator.translate(readOnly, DeviceCommand.SetPower(readOnly.id, Power.ON)))
    }

    @Test
    fun `brilho e limitado ao intervalo valido`() {
        val intent = CommandTranslator.translate(lamp, DeviceCommand.SetBrightness(lamp.id, 900))!!
        assertEquals("255", intent.payload)

        val negativo = CommandTranslator.translate(lamp, DeviceCommand.SetBrightness(lamp.id, -5))!!
        assertEquals("0", negativo.payload)
    }

    @Test
    fun `brilho em dispositivo que nao suporta e ignorado`() {
        val simple = lamp.copy(supportsBrightness = false)
        assertNull(CommandTranslator.translate(simple, DeviceCommand.SetBrightness(simple.id, 100)))
    }

    @Test
    fun `brilho zero desliga no estado otimista`() {
        val state = CommandTranslator.optimisticState(lamp, DeviceCommand.SetBrightness(lamp.id, 0), 10)!!
        assertEquals(Power.OFF, state.power)
        assertEquals(0, state.brightness)
    }

    @Test
    fun `vocabulario do dispositivo define o payload`() {
        val legacy = lamp.copy(vocabulary = PayloadVocabulary.NUMERIC)
        val intent = CommandTranslator.translate(legacy, DeviceCommand.SetPower(legacy.id, Power.ON))!!
        assertEquals("1", intent.payload)
    }
}
