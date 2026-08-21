package com.raulsousa.pulso.domain

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TelemetrySeriesTest {

    @Test
    fun `nunca cresce alem da capacidade`() {
        val series = TelemetrySeries(capacity = 3)
        repeat(100) { series.record(it.toLong(), it.toDouble()) }

        assertEquals(3, series.size)
        assertEquals(listOf(97.0, 98.0, 99.0), series.samples().map { it.value })
    }

    @Test
    fun `estatisticas refletem apenas a janela retida`() {
        val series = TelemetrySeries(capacity = 3)
        listOf(100.0, 1.0, 2.0, 3.0).forEachIndexed { i, v -> series.record(i.toLong(), v) }

        val stats = series.stats()!!
        assertEquals(1.0, stats.min)
        assertEquals(3.0, stats.max)
        assertEquals(2.0, stats.average)
        assertEquals(3.0, stats.last)
        assertEquals(2.0, stats.range)
    }

    @Test
    fun `serie vazia nao tem estatisticas`() {
        assertNull(TelemetrySeries().stats())
    }

    @Test
    fun `trimTo descarta amostras fora da janela`() {
        val series = TelemetrySeries(capacity = 10)
        repeat(10) { series.record(it * 1_000L, it.toDouble()) }
        series.trimTo(nowMillis = 9_000, windowMillis = 3_000)

        assertEquals(listOf(6.0, 7.0, 8.0, 9.0), series.samples().map { it.value })
    }

    @Test
    fun `downsample preserva os picos`() {
        val series = TelemetrySeries(capacity = 100)
        repeat(100) { series.record(it.toLong(), 20.0) }
        series.record(100, 95.0) // pico

        val reduced = series.downsample(10)
        assertTrue(reduced.size <= 10)
        assertTrue(reduced.any { it.value == 95.0 }, "o pico é a única parte interessante do gráfico")
    }

    @Test
    fun `downsample devolve tudo quando ja cabe`() {
        val series = TelemetrySeries(capacity = 10)
        repeat(4) { series.record(it.toLong(), it.toDouble()) }
        assertEquals(4, series.downsample(10).size)
    }
}

class BackoffTest {

    @Test
    fun `cresce exponencialmente e satura no teto`() {
        val delays = (0..10).map { Backoff.delayFor(it, jitter = 0.0) }
        assertEquals(listOf(1_000L, 2_000L, 4_000L, 8_000L, 16_000L, 32_000L), delays.take(6))
        assertTrue(delays.all { it <= Backoff.DEFAULT_MAX_MILLIS })
        assertEquals(Backoff.DEFAULT_MAX_MILLIS, delays.last())
    }

    @Test
    fun `jitter espalha as tentativas sem estourar o teto`() {
        val random = Random(seed = 42)
        val samples = List(200) { Backoff.delayFor(attempt = 3, jitter = 0.25, random = random) }

        assertTrue(samples.distinct().size > 1, "sem variação não há jitter")
        assertTrue(samples.all { it in 6_000L..10_000L }, "8s ± 25% mais arredondamento")
    }

    @Test
    fun `expoente altissimo nao estoura o Long`() {
        assertEquals(Backoff.DEFAULT_MAX_MILLIS, Backoff.delayFor(attempt = 62, jitter = 0.0))
        assertEquals(Backoff.DEFAULT_MAX_MILLIS, Backoff.delayFor(attempt = 1_000, jitter = 0.0))
    }
}

class ClockTest {

    @Test
    fun `relogio mutavel avanca de forma deterministica`() {
        val clock = MutableClock(1_000)
        assertEquals(1_000, clock.nowMillis())
        clock.advanceBy(500)
        assertEquals(1_500, clock.nowMillis())
        clock.set(0)
        assertEquals(0, clock.nowMillis())
    }
}
