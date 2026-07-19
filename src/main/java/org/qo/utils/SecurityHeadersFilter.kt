package org.qo.utils

import org.springframework.stereotype.Component
import org.springframework.web.server.ServerWebExchange
import org.springframework.web.server.WebFilter
import org.springframework.web.server.WebFilterChain
import reactor.core.publisher.Mono

@Component
class SecurityHeadersFilter : WebFilter {
	override fun filter(exchange: ServerWebExchange, chain: WebFilterChain): Mono<Void> {
		exchange.response.headers.apply {
			set("X-Content-Type-Options", "nosniff")
			set("X-Frame-Options", "DENY")
			set("Referrer-Policy", "no-referrer")
			set("Permissions-Policy", "camera=(), microphone=(), geolocation=()")
		}
		return chain.filter(exchange)
	}
}
