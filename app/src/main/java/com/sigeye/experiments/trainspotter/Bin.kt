package com.sigeye.experiments.trainspotter

/** One closed time bucket. This is exactly what a CSV row holds. */
data class Bin(
    val startMs: Long,
    val newCount: Int,
    val activeUnique: Int,
    val baseline: Double,
    val spike: Boolean,
    val label: String = "",
)
