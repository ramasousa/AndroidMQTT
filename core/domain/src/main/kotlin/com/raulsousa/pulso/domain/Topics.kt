package com.raulsousa.pulso.domain

/**
 * Regras de tópicos MQTT — nível de protocolo, sem dependência de cliente.
 *
 * O app de 2017 comparava tópicos com `topic.equals("led")`. Isso funciona
 * exatamente enquanto existir um único dispositivo e nenhum wildcard. A partir
 * do momento em que você assina `casa/+/estado` ou `homeassistant/#`, é preciso
 * o algoritmo de verdade — que é o que está aqui, com testes.
 *
 * Referência: MQTT 5.0, seção 4.7 (Topic Names and Topic Filters).
 */
public object Topics {

    public const val LEVEL_SEPARATOR: Char = '/'
    public const val SINGLE_LEVEL_WILDCARD: String = "+"
    public const val MULTI_LEVEL_WILDCARD: String = "#"

    /**
     * Retorna `true` se [topic] casa com o [filter] assinado.
     *
     * - `+` casa exatamente um nível (inclusive um nível vazio).
     * - `#` casa o nível corrente e todos os descendentes, e só pode aparecer
     *   como último nível do filtro.
     * - Tópicos que começam com cifrão (ex.: `$SYS/broker/uptime`) nunca casam
     *   com um filtro iniciado por wildcard — é o que impede uma assinatura em
     *   `#` de despejar a telemetria interna do broker no seu dashboard.
     */
    public fun matches(filter: String, topic: String): Boolean {
        if (filter.isEmpty() || topic.isEmpty()) return false
        if (filter == topic) return true

        val filterLevels = filter.split(LEVEL_SEPARATOR)
        val topicLevels = topic.split(LEVEL_SEPARATOR)

        if (topicLevels.first().startsWith('$') &&
            (filterLevels.first() == MULTI_LEVEL_WILDCARD || filterLevels.first() == SINGLE_LEVEL_WILDCARD)
        ) {
            return false
        }

        var i = 0
        while (i < filterLevels.size) {
            val level = filterLevels[i]
            if (level == MULTI_LEVEL_WILDCARD) {
                // `#` precisa ser o último nível e casa com o resto — inclusive
                // com "nada", pois `casa/#` casa com `casa`.
                return i == filterLevels.lastIndex
            }
            if (i >= topicLevels.size) return false
            if (level != SINGLE_LEVEL_WILDCARD && level != topicLevels[i]) return false
            i++
        }
        return filterLevels.size == topicLevels.size
    }

    /** Valida um *nome* de tópico para publicação (wildcards são proibidos). */
    public fun isValidTopicName(topic: String): Boolean =
        topic.isNotEmpty() &&
            topic.length <= MAX_TOPIC_BYTES &&
            !topic.contains(MULTI_LEVEL_WILDCARD) &&
            !topic.contains(SINGLE_LEVEL_WILDCARD) &&
            !topic.contains(' ')

    /** Valida um *filtro* de assinatura (wildcards permitidos, nas posições legais). */
    public fun isValidTopicFilter(filter: String): Boolean {
        if (filter.isEmpty() || filter.length > MAX_TOPIC_BYTES || filter.contains(' ')) return false
        val levels = filter.split(LEVEL_SEPARATOR)
        levels.forEachIndexed { index, level ->
            when {
                level == MULTI_LEVEL_WILDCARD && index != levels.lastIndex -> return false
                level.contains(MULTI_LEVEL_WILDCARD) && level != MULTI_LEVEL_WILDCARD -> return false
                level.contains(SINGLE_LEVEL_WILDCARD) && level != SINGLE_LEVEL_WILDCARD -> return false
            }
        }
        return true
    }

    /** `casa/sala/lampada` -> `lampada`. Útil para rótulos derivados do tópico. */
    public fun leaf(topic: String): String = topic.substringAfterLast(LEVEL_SEPARATOR)

    private const val MAX_TOPIC_BYTES = 65_535
}
