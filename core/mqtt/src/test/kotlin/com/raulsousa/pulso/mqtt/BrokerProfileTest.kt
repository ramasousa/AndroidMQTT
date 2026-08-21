package com.raulsousa.pulso.mqtt

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class BrokerProfileTest {

    @Test
    fun `perfil padrao usa TLS`() {
        val profile = BrokerProfile()
        assertTrue(profile.useTls)
        assertEquals(8883, profile.port)
    }

    @Test
    fun `alerta quando sai da rede local sem TLS`() {
        val problems = BrokerProfile(host = "broker.exemplo.com", port = 1883, useTls = false).validate()
        assertTrue(problems.any { it.contains("TLS") })
    }

    @Test
    fun `nao alerta em rede privada sem TLS`() {
        assertTrue(BrokerProfile(host = "192.168.0.10", port = 1883, useTls = false).validate().isEmpty())
        assertTrue(BrokerProfile(host = "raspberry.local", port = 1883, useTls = false).validate().isEmpty())
    }

    @Test
    fun `porta fora do intervalo e host vazio sao rejeitados`() {
        val problems = BrokerProfile(host = "", port = 70_000).validate()
        assertEquals(2, problems.size)
    }

    @Test
    fun `senha sem usuario e rejeitada`() {
        val problems = BrokerProfile(host = "10.0.0.1", useTls = false, password = "s3nha").validate()
        assertTrue(problems.any { it.contains("usuário") })
    }

    @Test
    fun `label reflete o esquema`() {
        assertEquals("mqtts://casa.local:8883", BrokerProfile(host = "casa.local").label)
        assertEquals(
            "mqtt://10.0.2.2:1883",
            BrokerProfile(host = "10.0.2.2", port = 1883, useTls = false).label,
        )
    }

    @Test
    fun `senha nao e serializada`() {
        val json = kotlinx.serialization.json.Json.encodeToString(
            BrokerProfile.serializer(),
            BrokerProfile(host = "casa.local", username = "raul", password = "s3nha"),
        )
        assertTrue("s3nha" !in json, "senha jamais pode vazar para o disco em texto puro")
        assertTrue("raul" in json)
    }
}
