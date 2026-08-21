package com.raulsousa.pulso.domain

/**
 * Relógio injetável. Existe por um motivo só: tornar cooldown de automação,
 * timeout de disponibilidade e janelas de telemetria determinísticos em teste,
 * sem `Thread.sleep`.
 */
public fun interface Clock {
    public fun nowMillis(): Long

    public companion object {
        public val System: Clock = Clock { java.lang.System.currentTimeMillis() }
    }
}

/** Relógio controlado manualmente — para testes. */
public class MutableClock(private var now: Long = 0L) : Clock {
    override fun nowMillis(): Long = now

    public fun advanceBy(millis: Long) {
        now += millis
    }

    public fun set(millis: Long) {
        now = millis
    }
}
