package org.qo.metrics

import org.junit.jupiter.api.Test
import org.springframework.mock.http.server.reactive.MockServerHttpRequest
import org.springframework.mock.web.server.MockServerWebExchange
import org.springframework.web.reactive.HandlerMapping
import org.springframework.web.server.WebFilterChain
import reactor.core.publisher.Mono
import kotlin.test.assertEquals

class ApiEndpointMetricFilterTest {
	@Test
	fun recordsMethodAndResolvedRouteTemplate() {
		val metric = InMemoryGenericMetric(10)
		val filter = ApiEndpointMetricFilter(metric)
		val exchange = MockServerWebExchange.from(MockServerHttpRequest.get("/items/42").build())
		exchange.attributes[HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE] = "/items/{id}"
		val chain = WebFilterChain { Mono.empty() }

		filter.filter(exchange, chain).block()

		val snapshot = metric.snapshot().single()
		assertEquals("GET /items/{id}", snapshot.name)
		assertEquals(1L, snapshot.count)
	}
}
