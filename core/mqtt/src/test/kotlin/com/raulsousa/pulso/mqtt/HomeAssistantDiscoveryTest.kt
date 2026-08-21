package com.raulsousa.pulso.mqtt

import com.raulsousa.pulso.domain.DeviceKind
import com.raulsousa.pulso.mqtt.discovery.HomeAssistantDiscovery
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class HomeAssistantDiscoveryTest {

    private val prefix = "homeassistant"

    @Test
    fun `interpreta um anuncio com chaves longas`() {
        val payload = """
            {
              "name": "Lâmpada da sala",
              "unique_id": "esp-sala-01",
              "state_topic": "casa/sala/lampada/estado",
              "command_topic": "casa/sala/lampada/set",
              "brightness_command_topic": "casa/sala/lampada/brilho/set",
              "availability_topic": "casa/sala/lampada/disponivel",
              "device": { "name": "ESP da sala", "suggested_area": "Sala" }
            }
        """.trimIndent()

        val device = HomeAssistantDiscovery
            .parse(prefix, "$prefix/light/lampada_sala/config", payload)
            ?.device

        assertNotNull(device)
        assertEquals("esp-sala-01", device.id.value)
        assertEquals("Lâmpada da sala", device.name)
        assertEquals(DeviceKind.LIGHT, device.kind)
        assertEquals("Sala", device.room)
        assertEquals("casa/sala/lampada/set", device.topics.command)
        assertTrue(device.supportsBrightness)
        assertTrue(device.discovered)
    }

    @Test
    fun `interpreta as chaves abreviadas do Tasmota e ESPHome`() {
        val payload = """
            {
              "name": "Tomada",
              "uniq_id": "tasmota-01",
              "stat_t": "tele/tomada/ESTADO",
              "cmd_t": "cmnd/tomada/POWER",
              "pl_on": "ON",
              "pl_off": "OFF",
              "avty_t": "tele/tomada/LWT",
              "pl_avail": "Online",
              "pl_not_avail": "Offline",
              "dev": { "sa": "Cozinha" }
            }
        """.trimIndent()

        val device = HomeAssistantDiscovery
            .parse(prefix, "$prefix/switch/tomada/config", payload)!!
            .device!!

        assertEquals(DeviceKind.SWITCH, device.kind)
        assertEquals("cmnd/tomada/POWER", device.topics.command)
        assertEquals("tele/tomada/LWT", device.topics.availability)
        assertEquals("Online", device.vocabulary.available)
        assertEquals("Cozinha", device.room)
    }

    @Test
    fun `extrai a chave do value_template`() {
        val payload = """
            {
              "name": "Temperatura",
              "state_topic": "casa/sala/clima",
              "unit_of_measurement": "°C",
              "value_template": "{{ value_json.temperatura | round(1) }}"
            }
        """.trimIndent()

        val device = HomeAssistantDiscovery
            .parse(prefix, "$prefix/sensor/temp/config", payload)!!
            .device!!

        assertEquals("temperatura", device.topics.valueKey)
        assertEquals("°C", device.unit)
        assertEquals(DeviceKind.SENSOR, device.kind)
    }

    @Test
    fun `payload vazio significa remocao`() {
        val announcement = HomeAssistantDiscovery.parse(prefix, "$prefix/light/lampada/config", "")
        assertNotNull(announcement)
        assertNull(announcement.device, "config vazia remove o dispositivo")
        assertEquals("light/lampada", announcement.topic.deviceId.value)
    }

    @Test
    fun `topico com node_id e aceito`() {
        val parsed = HomeAssistantDiscovery.parseTopic(prefix, "$prefix/sensor/no01/temp/config")
        assertNotNull(parsed)
        assertEquals("no01", parsed.nodeId)
        assertEquals("temp", parsed.objectId)
    }

    @Test
    fun `topico que nao e de discovery e ignorado`() {
        assertNull(HomeAssistantDiscovery.parse(prefix, "casa/sala/lampada/estado", "ON"))
        assertFalse(HomeAssistantDiscovery.isDiscoveryTopic(prefix, "casa/sala/lampada/config"))
    }

    @Test
    fun `json invalido nao derruba o parser`() {
        assertNull(HomeAssistantDiscovery.parse(prefix, "$prefix/light/x/config", "{isso não é json"))
    }

    @Test
    fun `sem nome usa o object_id humanizado`() {
        val device = HomeAssistantDiscovery
            .parse(prefix, "$prefix/switch/tomada_da_varanda/config", """{"stat_t": "a", "cmd_t": "b"}""")!!
            .device!!
        assertEquals("Tomada Da Varanda", device.name)
    }

    @Test
    fun `availability em lista tambem e lida`() {
        val device = HomeAssistantDiscovery.parse(
            prefix,
            "$prefix/sensor/x/config",
            """{"state_topic": "a", "availability": [{"topic": "casa/x/lwt"}]}""",
        )!!.device!!
        assertEquals("casa/x/lwt", device.topics.availability)
    }

    @Test
    fun `filtros de assinatura cobrem as duas formas de topico`() {
        assertEquals("homeassistant/+/+/config", HomeAssistantDiscovery.subscriptionFilter(prefix))
        assertEquals("homeassistant/+/+/+/config", HomeAssistantDiscovery.nodeSubscriptionFilter(prefix))
    }
}
