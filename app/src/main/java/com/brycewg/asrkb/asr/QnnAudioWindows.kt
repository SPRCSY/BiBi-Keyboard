package com.brycewg.asrkb.asr

/** Bounded windows: silence boundaries from the existing Ten VAD take precedence.
 * Continuous speech uses a short overlap instead of dropping everything after 5s.
 */
internal object QnnAudioWindows {
    const val SAMPLE_RATE = 16_000
    const val WINDOW_SAMPLES = 5 * SAMPLE_RATE
    const val OVERLAP_SAMPLES = SAMPLE_RATE / 4

    fun ranges(size: Int, silenceSamples: List<IntRange>): List<IntRange> {
        require(size >= 0)
        val result = mutableListOf<IntRange>()
        var start = 0
        while (start < size) {
            val limit = minOf(size, start + WINDOW_SAMPLES)
            val cut = if (limit == size) limit else silenceSamples
                .map { it.first + (it.last - it.first) / 2 }
                .filter { it >= start + SAMPLE_RATE && it <= limit }
                .maxOrNull() ?: limit
            result += start until cut
            if (cut == size) break
            start = if (cut == limit) cut - OVERLAP_SAMPLES else cut
        }
        return result
    }

    fun padded(samples: FloatArray, range: IntRange): FloatArray {
        require(range.first >= 0 && range.last < samples.size)
        require(range.count() in 1..WINDOW_SAMPLES)
        return FloatArray(WINDOW_SAMPLES).also {
            samples.copyInto(it, 0, range.first, range.last + 1)
        }
    }

    /** Remove an exact overlap only when the audio windows actually overlap. */
    fun append(previous: String, next: String, overlapping: Boolean): String {
        val left = previous.trim()
        val right = next.trim()
        if (left.isEmpty()) return right
        if (right.isEmpty()) return left
        if (overlapping) {
            for (n in minOf(48, left.length, right.length) downTo 1) {
                if (n < 3 && right.take(n).all { it.code < 128 && it.isLetterOrDigit() }) continue
                if (left.takeLast(n).equals(right.take(n), ignoreCase = true)) {
                    return left + right.drop(n)
                }
            }
        }
        val separator = if (left.last().isLetterOrDigit() && right.first().isLetterOrDigit() &&
            left.last().code < 128 && right.first().code < 128
        ) " " else ""
        return left + separator + right
    }
}
