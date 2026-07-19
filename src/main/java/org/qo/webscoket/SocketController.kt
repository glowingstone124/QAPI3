package org.qo.webscoket

import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.web.reactive.HandlerMapping
import org.springframework.web.reactive.handler.SimpleUrlHandlerMapping
import org.springframework.web.reactive.socket.WebSocketHandler
import org.springframework.web.reactive.socket.WebSocketSession
import org.springframework.web.reactive.socket.server.support.WebSocketHandlerAdapter
import reactor.core.publisher.Mono

@Configuration
class SocketConfig {
	@Bean
	fun socketHandler(): WebSocketHandler = SocketHandler()

	@Bean
	fun webSocketMapping(socketHandler: WebSocketHandler): HandlerMapping {
		return SimpleUrlHandlerMapping(mapOf("/ws" to socketHandler), -1)
	}

	@Bean
	fun webSocketHandlerAdapter() = WebSocketHandlerAdapter()
}

class SocketHandler : WebSocketHandler {
	override fun handle(session: WebSocketSession): Mono<Void> {
		return session.receive().then()
	}
}
