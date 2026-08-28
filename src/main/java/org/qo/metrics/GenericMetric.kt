package org.qo.metrics

import java.time.Duration

/**
 * Records duration samples under a named metric and exposes a snapshot of the
 * aggregated values.
 *
 * Durations are recorded in nanoseconds so callers can use [System.nanoTime]
 * without losing precision. Implementations decide how samples are retained
 * for percentile calculation.
 */
interface GenericMetric {
	fun record(name: String, durationNanos: Long)

	fun record(name: String, duration: Duration) {
		record(name, duration.toNanos())
	}

	fun snapshot(): List<MetricSnapshot>

	fun clear()
}

data class MetricSnapshot(
	val name: String,
	val count: Long,
	val avgMs: Double,
	val p90Ms: Double,
	val p99Ms: Double,
)
