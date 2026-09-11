package com.sigeye.experiments.trainspotter

/** Which stage of the run a bin belongs to. Recorded so analysis can exclude the ramp-up. */
enum class Phase {
    /** Recording the standing population. Counts are deliberately zero. */
    ENROLL,

    /** Counting for real, but the baseline is not trustworthy yet, so alerts stay off. */
    WARMUP,

    /** Fully live. */
    ARMED,
    ;

    fun csv(): String = name.lowercase()
}

/** One closed time bucket. This is exactly what a CSV row holds. */
data class Bin(
    val startMs: Long,
    val newCount: Int,
    val activeUnique: Int,
    val baseline: Double,
    val spike: Boolean,
    val phase: Phase,
    val label: String = "",
) {
    /** Enrollment bins are bookkeeping, not measurement - keep them off the chart scale. */
    val countsTowardScale: Boolean get() = phase != Phase.ENROLL
}
