package org.qo.utils

import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.server.reactive.ServerHttpRequest
import org.springframework.stereotype.Component
import org.springframework.web.server.ServerWebExchange
import org.springframework.web.server.WebFilter
import org.springframework.web.server.WebFilterChain
import reactor.core.publisher.Mono

@Component
class RateLimitFilter : WebFilter {
	private val limiter = RateLimiter()

	override fun filter(exchange: ServerWebExchange, chain: WebFilterChain): Mono<Void> {
		val request = exchange.request
		val path = request.path.pathWithinApplication().value()
		val clientKey = resolveClientKey(request)
		if (!limiter.allow(path, clientKey)) {
			val response = exchange.response
			response.statusCode = HttpStatus.TOO_MANY_REQUESTS
			response.headers.contentType = MediaType.APPLICATION_JSON
			val body = response.bufferFactory()
				.wrap("{\"code\":429,\"message\":\"Rate limit exceeded\"}".toByteArray())
			return response.writeWith(Mono.just(body))
		}
		return chain.filter(exchange)
	}

	private fun resolveClientKey(request: ServerHttpRequest): String {
		return IPUtil.getIpAddr(request)
	}
}
