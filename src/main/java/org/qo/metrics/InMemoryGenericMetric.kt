package org.qo.metrics

import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.ceil

/**
 * Thread-safe in-memory implementation of [GenericMetric].
 *
 * The count and average include every sample since startup. Percentiles use a
 * bounded ring buffer containing the most recent samples for each metric, so a
 * busy endpoint cannot grow the process indefinitely.
 */
@Component
class InMemoryGenericMetric(
	@Value("\${qapi.metrics.max-samples-per-endpoint:4096}") maxSamplesPerEndpoint: Int,
) : GenericMetric {
	private val maxSamplesPerEndpoint = maxSamplesPerEndpoint.also {
		require(it > 0) { "qapi.metrics.max-samples-per-endpoint must be greater than zero" }
	}
	private val metrics = ConcurrentHashMap<String, EndpointMetric>()

	override fun record(name: String, durationNanos: Long) {
		if (name.isBlank() || durationNanos < 0) return
		metrics.computeIfAbsent(name) { EndpointMetric(maxSamplesPerEndpoint) }
			.record(durationNanos)
	}

	override fun snapshot(): List<MetricSnapshot> = metrics.entries
		.map { (name, metric) -> metric.snapshot(name) }
		.sortedBy { it.name }

	override fun clear() {
		metrics.clear()
	}

	private class EndpointMetric(private val maxSamples: Int) {
		private var count = 0L
		private var totalNanos = 0L
		private var sampleCount = 0
		private var nextSampleIndex = 0
		private val samples = LongArray(maxSamples)

		@Synchronized
		fun record(durationNanos: Long) {
			count++
			totalNanos += durationNanos
			samples[nextSampleIndex] = durationNanos
			nextSampleIndex = (nextSampleIndex + 1) % maxSamples
			if (sampleCount < maxSamples) sampleCount++
		}

		@Synchronized
		fun snapshot(name: String): MetricSnapshot {
			val sortedSamples = samples.copyOf(sampleCount).apply { sort() }
			return MetricSnapshot(
				name = name,
				count = count,
				avgMs = totalNanos.toDouble() / count / NANOS_PER_MILLISECOND,
				p90Ms = percentileMs(sortedSamples, 0.90),
				p99Ms = percentileMs(sortedSamples, 0.99),
			)
		}

		private fun percentileMs(sortedSamples: LongArray, percentile: Double): Double {
			if (sortedSamples.isEmpty()) return 0.0
			val rank = ceil(percentile * sortedSamples.size).toInt().coerceAtLeast(1)
			return sortedSamples[rank - 1].toDouble() / NANOS_PER_MILLISECOND
		}
	}

	private companion object {
		const val NANOS_PER_MILLISECOND = 1_000_000.0
	}
}
