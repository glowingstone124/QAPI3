package org.qo.webscoket

import org.springframework.context.annotation.Configuration
import org.springframework.web.socket.TextMessage
import org.springframework.web.socket.WebSocketSession
import org.springframework.web.socket.config.annotation.EnableWebSocket
import org.springframework.web.socket.config.annotation.WebSocketConfigurer
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry
import org.springframework.web.socket.handler.TextWebSocketHandler

@Configuration
@EnableWebSocket
class SocketConfig : WebSocketConfigurer {
	override fun registerWebSocketHandlers(registry: WebSocketHandlerRegistry) {
		registry.addHandler(TextWebSocketHandler(), "/ws").setAllowedOrigins("*")
	}
}

class SocketHandler : TextWebSocketHandler()
{
	override fun handleTextMessage(session: WebSocketSession, message: TextMessage) {
		//TODO
	}
}
