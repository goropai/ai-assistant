package com.speedy.assistant.voice

/**
 * Висота голосу (основний тон, Гц) — автокореляцією, локально. Дорослий чоловік ~85–180 Гц,
 * жінка ~165–255, дитина ~250–400: так Спіді відрізняє дитячий голос від дорослого.
 */
object Pitch {
    private const val MIN_HZ = 70f
    private const val MAX_HZ = 500f

    /** Тон шматка звуку або null — тиша / не голос (немає чіткої періодичності). */
    fun estimate(samples: ShortArray, count: Int = samples.size, rate: Int = 16000): Float? {
        if (count < rate / 25) return null // < 40 мс
        val minLag = (rate / MAX_HZ).toInt()
        val maxLag = (rate / MIN_HZ).toInt().coerceAtMost(count / 2)
        var energy = 0.0
        for (i in 0 until count) energy += samples[i].toDouble() * samples[i]
        if (energy / count < 300.0 * 300.0) return null // надто тихо
        var bestLag = -1
        var best = 0.0
        val corr = DoubleArray(maxLag + 2)
        for (lag in minLag..maxLag) {
            var sum = 0.0
            var e1 = 0.0
            var e2 = 0.0
            for (i in 0 until count - lag) {
                val a = samples[i].toDouble()
                val b = samples[i + lag].toDouble()
                sum += a * b
                e1 += a * a
                e2 += b * b
            }
            val r = if (e1 > 0 && e2 > 0) sum / kotlin.math.sqrt(e1 * e2) else 0.0
            corr[lag] = r
            if (r > best) { best = r; bestLag = lag }
        }
        if (bestLag < 0 || best < 0.5) return null // не голос
        // Октавні помилки: кратні періоду дають майже такі самі піки — беремо найкоротший
        // період (перший локальний пік), що майже не поступається найкращому.
        for (lag in minLag + 1 until bestLag) {
            if (corr[lag] >= best * 0.9 && corr[lag] >= corr[lag - 1] && corr[lag] >= corr[lag + 1]) return rate.toFloat() / lag
        }
        return rate.toFloat() / bestLag
    }

    /** Медіана тону по шматках по 40 мс (мова, а не окремий звук). */
    fun ofSpeech(frames: List<ShortArray>, rate: Int = 16000): Float? {
        val chunk = rate / 25
        val all = ShortArray(frames.sumOf { it.size })
        var pos = 0
        for (f in frames) { f.copyInto(all, pos); pos += f.size }
        val values = (0 until all.size - chunk step chunk / 2).mapNotNull { start ->
            estimate(all.copyOfRange(start, start + chunk), chunk, rate)
        }
        if (values.size < 3) return null
        return values.sorted()[values.size / 2]
    }

    /** Дитячий голос: тон вище за дорослий жіночий. */
    const val CHILD_HZ = 270f
    /** Явно дорослий: нижче дитячого діапазону. */
    const val ADULT_HZ = 230f
}
