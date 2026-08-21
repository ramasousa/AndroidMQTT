package com.raulsousa.pulso.mqtt.discovery

import com.raulsousa.pulso.domain.Device
import com.raulsousa.pulso.domain.DeviceId
import com.raulsousa.pulso.domain.DeviceKind
import com.raulsousa.pulso.domain.DeviceTopics
import com.raulsousa.pulso.domain.PayloadVocabulary
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject

/**
 * MQTT Discovery no formato do Home Assistant.
 *
 * Esta é a diferença conceitual entre o app de 2017 e este: lá, o dispositivo
 * conhecido era uma constante compilada (`TOPICO_LAMPADA = "led"`). Aqui, o app
 * não sabe nada de antemão — ele assina `homeassistant/+/+/config` e a casa se
 * descreve sozinha. Qualquer coisa que já fale esse dialeto (Tasmota, ESPHome,
 * Zigbee2MQTT, Shelly, Sonoff, Z-Wave JS UI, ou o simulador em
 * `tools/simulator/`) aparece na tela sem uma linha de código nova.
 *
 * Formato do tópico: `<prefixo>/<componente>/[<no>/]<objeto>/config`
 * Payload vazio no mesmo tópico significa "removi este dispositivo".
 *
 * Suporta tanto as chaves longas (`state_topic`) quanto as abreviadas
 * (`stat_t`) que Tasmota e ESPHome usam para caber no MTU dos firmwares.
 */
public object HomeAssistantDiscovery {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    /** `{{ value_json.temperature }}` -> `temperature` */
    private val valueTemplateKey = Regex("""value_json\.([A-Za-z0-9_]+)""")

    public data class Announcement(val topic: DiscoveryTopic, val device: Device?)

    public data class DiscoveryTopic(
        val prefix: String,
        val component: String,
        val nodeId: String?,
        val objectId: String,
    ) {
        public val deviceId: DeviceId
            get() = DeviceId(listOfNotNull(component, nodeId, objectId).joinToString("/"))
    }

    /** O filtro a assinar para receber todos os anúncios. */
    public fun subscriptionFilter(prefix: String): String = "$prefix/+/+/config"

    /** Firmwares com `node_id` publicam um nível a mais. */
    public fun nodeSubscriptionFilter(prefix: String): String = "$prefix/+/+/+/config"

    public fun isDiscoveryTopic(prefix: String, topic: String): Boolean =
        topic.startsWith("$prefix/") && topic.endsWith("/config")

    /**
     * Interpreta uma mensagem de discovery.
     *
     * Devolve `null` se o tópico não for de discovery. Devolve um
     * [Announcement] com `device == null` quando o payload é vazio — o
     * protocolo usa isso para dizer "apague este dispositivo".
     */
    public fun parse(prefix: String, topic: String, payload: String): Announcement? {
        val parsed = parseTopic(prefix, topic) ?: return null
        if (payload.isBlank()) return Announcement(parsed, null)

        val root = runCatching { json.parseToJsonElement(payload).jsonObject }.getOrNull() ?: return null
        val kind = parsed.component.toKind()

        val deviceBlock = (root["device"] ?: root["dev"]) as? JsonObject
        val stateTopic = root.str("state_topic", "stat_t")
        val commandTopic = root.str("command_topic", "cmd_t")
        val brightnessState = root.str("brightness_state_topic", "bri_stat_t")
        val brightnessCommand = root.str("brightness_command_topic", "bri_cmd_t")

        val device = Device(
            id = DeviceId(root.str("unique_id", "uniq_id") ?: parsed.deviceId.value),
            name = root.str("name") ?: deviceBlock?.str("name") ?: parsed.objectId.humanize(),
            kind = kind,
            room = deviceBlock?.str("suggested_area", "sa"),
            topics = DeviceTopics(
                state = stateTopic,
                command = commandTopic,
                brightnessState = brightnessState,
                brightnessCommand = brightnessCommand,
                availability = root.str("availability_topic", "avty_t")
                    ?: root.firstAvailabilityFromList(),
                valueKey = root.str("value_template", "val_tpl")?.let(::extractValueKey),
            ),
            vocabulary = PayloadVocabulary(
                on = root.str("payload_on", "pl_on") ?: "ON",
                off = root.str("payload_off", "pl_off") ?: "OFF",
                available = root.str("payload_available", "pl_avail") ?: "online",
                unavailable = root.str("payload_not_available", "pl_not_avail") ?: "offline",
            ),
            unit = root.str("unit_of_measurement", "unit_of_meas"),
            icon = root.str("icon", "ic"),
            supportsBrightness = brightnessCommand != null,
            discovered = true,
        )
        return Announcement(parsed, device)
    }

    internal fun parseTopic(prefix: String, topic: String): DiscoveryTopic? {
        if (!isDiscoveryTopic(prefix, topic)) return null
        val parts = topic.split('/')
        return when (parts.size) {
            // prefixo/componente/objeto/config
            4 -> DiscoveryTopic(parts[0], parts[1], null, parts[2])
            // prefixo/componente/no/objeto/config
            5 -> DiscoveryTopic(parts[0], parts[1], parts[2], parts[3])
            else -> null
        }
    }

    internal fun extractValueKey(template: String): String? =
        valueTemplateKey.find(template)?.groupValues?.getOrNull(1)

    private fun String.toKind(): DeviceKind = when (this) {
        "light" -> DeviceKind.LIGHT
        "switch", "siren", "fan" -> DeviceKind.SWITCH
        "sensor" -> DeviceKind.SENSOR
        "binary_sensor" -> DeviceKind.BINARY_SENSOR
        else -> DeviceKind.UNKNOWN
    }

    private fun String.humanize(): String =
        split('_', '-').filter { it.isNotBlank() }.joinToString(" ") { part ->
            part.replaceFirstChar { it.uppercase() }
        }

    private fun JsonObject.str(vararg keys: String): String? {
        for (key in keys) {
            val value = (this[key] as? JsonPrimitive)?.content
            if (!value.isNullOrBlank()) return value
        }
        return null
    }

    /** HA também aceita `availability: [{topic: ...}]`. Pegamos o primeiro. */
    private fun JsonObject.firstAvailabilityFromList(): String? = runCatching {
        val list = (this["availability"] ?: this["avty"])?.jsonArray ?: return null
        (list.firstOrNull() as? JsonObject)?.let { (it["topic"] as? JsonPrimitive)?.content }
    }.getOrNull()
}
