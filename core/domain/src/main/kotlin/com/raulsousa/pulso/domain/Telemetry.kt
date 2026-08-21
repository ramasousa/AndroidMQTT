package com.raulsousa.pulso.domain

/** Uma leitura no tempo. */
public data class Sample(val atMillis: Long, val value: Double)

/**
 * Série temporal de tamanho fixo (ring buffer).
 *
 * Um app de IoT que guarda telemetria em `ArrayList` cresce até o OOM — e num
 * sensor publicando a cada segundo isso leva horas, não meses, o que é
 * exatamente tempo suficiente para passar na sua bateria de testes e morrer na
 * casa do usuário. Aqui a memória é limitada por construção.
 */
public class TelemetrySeries(public val capacity: Int = DEFAULT_CAPACITY) {

    init {
        require(capacity > 0) { "capacity deve ser positiva" }
    }

    private val buffer = ArrayDeque<Sample>(capacity)

    public val size: Int get() = buffer.size
    public val isEmpty: Boolean get() = buffer.isEmpty()

    public fun record(sample: Sample) {
        if (buffer.size == capacity) buffer.removeFirst()
        buffer.addLast(sample)
    }

    public fun record(atMillis: Long, value: Double): Unit = record(Sample(atMillis, value))

    public fun samples(): List<Sample> = buffer.toList()

    public fun last(): Sample? = buffer.lastOrNull()

    /** Descarta o que for mais antigo que [windowMillis} contado a partir de [nowMillis]. */
    public fun trimTo(nowMillis: Long, windowMillis: Long) {
        while (buffer.isNotEmpty() && nowMillis - buffer.first().atMillis > windowMillis) {
            buffer.removeFirst()
        }
    }

    public fun stats(): Stats? {
        if (buffer.isEmpty()) return null
        var min = Double.MAX_VALUE
        var max = -Double.MAX_VALUE
        var sum = 0.0
        for (sample in buffer) {
            if (sample.value < min) min = sample.value
            if (sample.value > max) max = sample.value
            sum += sample.value
        }
        return Stats(min = min, max = max, average = sum / buffer.size, last = buffer.last().value, count = buffer.size)
    }

    /**
     * Reduz a série a no máximo [targetPoints] pontos preservando os extremos de
     * cada bucket. Uma média simples achataria justamente os picos — que são a
     * única parte do gráfico que importa em telemetria de sensor.
     */
    public fun downsample(targetPoints: Int): List<Sample> {
        require(targetPoints > 0) { "targetPoints deve ser positivo" }
        val all = buffer.toList()
        if (all.size <= targetPoints) return all

        val bucketSize = all.size.toDouble() / targetPoints
        val result = ArrayList<Sample>(targetPoints)
        var index = 0
        while (index < targetPoints) {
            val from = (index * bucketSize).toInt()
            val to = minOf(((index + 1) * bucketSize).toInt(), all.size)
            if (from >= to) {
                index++
                continue
            }
            val bucket = all.subList(from, to)
            // Preserva o extremo mais distante da média do bucket.
            val mean = bucket.sumOf { it.value } / bucket.size
            result += bucket.maxByOrNull { kotlin.math.abs(it.value - mean) } ?: bucket.first()
            index++
        }
        return result
    }

    public fun clear(): Unit = buffer.clear()

    public data class Stats(
        val min: Double,
        val max: Double,
        val average: Double,
        val last: Double,
        val count: Int,
    ) {
        val range: Double get() = max - min
    }

    public companion object {
        public const val DEFAULT_CAPACITY: Int = 720 // 1 ponto/5s por 1 hora
    }
}
