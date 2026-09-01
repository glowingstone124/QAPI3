package org.qo

import org.junit.jupiter.api.Test
import org.springframework.http.HttpHeaders
import org.springframework.mock.http.server.reactive.MockServerHttpRequest
import org.springframework.mock.web.server.MockServerWebExchange
import reactor.core.publisher.Mono
import kotlin.test.assertEquals

class CorsConfigurationTest {
	@Test
	fun `configured ai origin is allowed`() {
		val exchange = MockServerWebExchange.from(
			MockServerHttpRequest.options("https://api.qoriginal.vip/qo/asking/v1/chat/completions")
				.header(HttpHeaders.ORIGIN, "https://ai.qoriginal.vip")
				.header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "POST")
				.build()
		)

		Main().corsFilter("https://ai.qoriginal.vip").filter(exchange) { Mono.empty() }.block()

		assertEquals("https://ai.qoriginal.vip", exchange.response.headers.accessControlAllowOrigin)
	}

	@Test
	fun `preflight requests allow every origin`() {
		val exchange = MockServerWebExchange.from(
			MockServerHttpRequest.options("https://api.qoriginal.vip/qo/download/avatar?name=steve")
				.header(HttpHeaders.ORIGIN, "http://localhost:5173")
				.header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "GET")
				.build()
		)

		Main().corsFilter("*").filter(exchange) { Mono.empty() }.block()

		assertEquals("*", exchange.response.headers.accessControlAllowOrigin)
	}
}
