package org.qo.metrics

import kotlin.test.Test
import kotlin.test.assertEquals

class InMemoryGenericMetricTest {
	@Test
	fun calculatesAverageAndPercentiles() {
		val metric = InMemoryGenericMetric(100)

		(1L..100L).forEach { metric.record("GET /items", it * NANOS_PER_MILLISECOND) }

		val snapshot = metric.snapshot().single()
		assertEquals(100L, snapshot.count)
		assertEquals(50.5, snapshot.avgMs, 0.0001)
		assertEquals(90.0, snapshot.p90Ms, 0.0001)
		assertEquals(99.0, snapshot.p99Ms, 0.0001)
	}

	@Test
	fun keepsTheMostRecentSamplesForPercentiles() {
		val metric = InMemoryGenericMetric(2)

		metric.record("GET /items", 1 * NANOS_PER_MILLISECOND)
		metric.record("GET /items", 2 * NANOS_PER_MILLISECOND)
		metric.record("GET /items", 3 * NANOS_PER_MILLISECOND)

		val snapshot = metric.snapshot().single()
		assertEquals(3L, snapshot.count)
		assertEquals(2.0, snapshot.avgMs, 0.0001)
		assertEquals(3.0, snapshot.p90Ms, 0.0001)
		assertEquals(3.0, snapshot.p99Ms, 0.0001)
	}

	@Test
	fun clearRemovesAllMetricSnapshots() {
		val metric = InMemoryGenericMetric(10)
		metric.record("GET /items", NANOS_PER_MILLISECOND)

		metric.clear()

		assertEquals(emptyList(), metric.snapshot())
	}

	private companion object {
		const val NANOS_PER_MILLISECOND = 1_000_000L
	}
}
