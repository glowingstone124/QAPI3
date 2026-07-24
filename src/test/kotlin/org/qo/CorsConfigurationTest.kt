package org.qo

import org.junit.jupiter.api.Test
import org.springframework.http.HttpHeaders
import org.springframework.mock.http.server.reactive.MockServerHttpRequest
import org.springframework.mock.web.server.MockServerWebExchange
import reactor.core.publisher.Mono
import kotlin.test.assertEquals

class CorsConfigurationTest {
	@Test
	fun `preflight requests allow every origin`() {
		val exchange = MockServerWebExchange.from(
			MockServerHttpRequest.options("/qo/download/avatar?name=steve")
				.header(HttpHeaders.ORIGIN, "http://localhost:5173")
				.header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "GET")
				.build()
		)

		Main().corsFilter("*").filter(exchange) { Mono.empty() }.block()

		assertEquals("*", exchange.response.headers.accessControlAllowOrigin)
	}
}
