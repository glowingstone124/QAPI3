package org.qo.metrics

import org.springframework.stereotype.Component
import org.springframework.web.reactive.HandlerMapping
import org.springframework.web.server.ServerWebExchange
import org.springframework.web.server.WebFilter
import org.springframework.web.server.WebFilterChain
import reactor.core.publisher.Mono

/** Records the processing time of every WebFlux request by HTTP method and route. */
@Component
class ApiEndpointMetricFilter(
	private val metric: GenericMetric,
) : WebFilter {
	override fun filter(exchange: ServerWebExchange, chain: WebFilterChain): Mono<Void> {
		val startNanos = System.nanoTime()
		return chain.filter(exchange).doFinally {
			metric.record(endpointName(exchange), (System.nanoTime() - startNanos).coerceAtLeast(0))
		}
	}

	private fun endpointName(exchange: ServerWebExchange): String {
		val route = exchange.getAttribute<String>(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE)
			?.takeIf { it.isNotBlank() }
			?: exchange.request.path.pathWithinApplication().value()
		val method = exchange.request.method?.name() ?: "UNKNOWN"
		return "$method $route"
	}
}
