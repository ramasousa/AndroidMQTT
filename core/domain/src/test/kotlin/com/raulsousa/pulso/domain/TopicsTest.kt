package com.raulsousa.pulso.domain

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TopicsTest {

    @Test
    fun `casamento exato`() {
        assertTrue(Topics.matches("casa/sala/lampada", "casa/sala/lampada"))
        assertFalse(Topics.matches("casa/sala/lampada", "casa/sala/tomada"))
    }

    @Test
    fun `wildcard de nivel unico casa exatamente um nivel`() {
        assertTrue(Topics.matches("casa/+/lampada", "casa/sala/lampada"))
        assertTrue(Topics.matches("casa/+/lampada", "casa/quarto/lampada"))
        assertFalse(Topics.matches("casa/+/lampada", "casa/sala/teto/lampada"))
        assertFalse(Topics.matches("casa/+", "casa"))
    }

    @Test
    fun `wildcard multinivel casa o resto e tambem o pai`() {
        assertTrue(Topics.matches("casa/#", "casa/sala/lampada/estado"))
        assertTrue(Topics.matches("casa/#", "casa/sala"))
        assertTrue(Topics.matches("#", "qualquer/coisa"))
        assertFalse(Topics.matches("casa/#", "predio/sala"))
    }

    @Test
    fun `wildcard nao alcanca topicos de sistema`() {
        assertFalse(Topics.matches("#", "\$SYS/broker/uptime"))
        assertFalse(Topics.matches("+/broker/uptime", "\$SYS/broker/uptime"))
        assertTrue(Topics.matches("\$SYS/#", "\$SYS/broker/uptime"))
    }

    @Test
    fun `validacao de nome e filtro`() {
        assertTrue(Topics.isValidTopicName("casa/sala/lampada/set"))
        assertFalse(Topics.isValidTopicName("casa/+/set"))
        assertFalse(Topics.isValidTopicName("casa/#"))
        assertFalse(Topics.isValidTopicName(""))

        assertTrue(Topics.isValidTopicFilter("casa/+/estado"))
        assertTrue(Topics.isValidTopicFilter("casa/#"))
        assertFalse(Topics.isValidTopicFilter("casa/#/estado"))
        assertFalse(Topics.isValidTopicFilter("casa/sa#la"))
        assertFalse(Topics.isValidTopicFilter("casa/sa+la"))
    }

    @Test
    fun `leaf devolve o ultimo nivel`() {
        kotlin.test.assertEquals("estado", Topics.leaf("casa/sala/lampada/estado"))
        kotlin.test.assertEquals("casa", Topics.leaf("casa"))
    }
}
