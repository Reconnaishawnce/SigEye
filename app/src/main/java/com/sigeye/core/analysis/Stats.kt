package com.sigeye.core.analysis

/** Small numeric helpers that more than one analysis needs. */
object Stats {

    /**
     * A percentile by linear interpolation between the two neighbouring samples.
     *
     * Interpolated rather than nearest-rank because these run on short series - a minute of
     * advertisements is a few dozen readings - and nearest-rank on a short series moves in
     * visible steps as one more packet arrives, which reads as the measurement jumping when
     * nothing has happened.
     *
     * @param sorted ascending. Sorting is the caller's job, because callers usually sort
     *   once and then ask for several percentiles.
     */
    fun percentile(sorted: List<Double>, fraction: Double): Double {
        if (sorted.isEmpty()) return 0.0
        if (sorted.size == 1) return sorted[0]
        val position = fraction.coerceIn(0.0, 1.0) * (sorted.size - 1)
        val low = position.toInt()
        val high = (low + 1).coerceAtMost(sorted.lastIndex)
        return sorted[low] + (sorted[high] - sorted[low]) * (position - low)
    }
}
