package org.qo.services.llmServices

import com.google.gson.JsonParser
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource
import org.mockito.Mockito
import kotlin.test.assertEquals

class LLMConversationControllerTest {
	private val llm = Mockito.mock(LLMServices::class.java, Mockito.RETURNS_DEEP_STUBS)
	private val conversations = Mockito.mock(KotshiConversationService::class.java)
	private val controller = LLMConversationController(llm, conversations)

	enum class Action { LIST, CREATE, MESSAGES, DELETE, UPDATE }

	private suspend fun call(action: Action, token: String?, authorization: String?) = when (action) {
		Action.LIST -> controller.listConversations(token, authorization)
		Action.CREATE -> controller.createConversation(token, authorization, null)
		Action.MESSAGES -> controller.getConversationMessages(token, authorization, "conversation")
		Action.DELETE -> controller.deleteConversation(token, authorization, "conversation")
		Action.UPDATE -> controller.updateConversation(token, authorization, "conversation", "{}")
	}

	@ParameterizedTest
	@EnumSource(Action::class)
	fun `all conversation operations reject a missing token before touching history`(action: Action) = runBlocking {
		val response = call(action, null, "Bearer")
		assertEquals(401, response.statusCode.value())
		assertEquals("缺少或无效的令牌", JsonParser.parseString(response.body).asJsonObject
			.getAsJsonObject("error").get("message").asString)
		Mockito.verifyNoInteractions(llm, conversations)
	}

	@ParameterizedTest
	@EnumSource(Action::class)
	fun `all operations authenticate the explicit token ahead of the bearer token`(action: Action) = runBlocking {
		Mockito.`when`(llm.authenticateWeb("invalid")).thenReturn(null)
		val response = call(action, " invalid ", "Bearer fallback")
		assertEquals(401, response.statusCode.value())
		assertEquals("权限验证失败", JsonParser.parseString(response.body).asJsonObject
			.getAsJsonObject("error").get("message").asString)
		Mockito.verify(llm).authenticateWeb("invalid")
		Mockito.verifyNoInteractions(conversations)
	}

	@Test
	fun `deletion retains the authenticated owner and clears resumable history`() = runBlocking {
		val user = LLMPrincipal(123, "user", LLMSource.WEB, "user", true)
		Mockito.`when`(llm.authenticateWeb("valid")).thenReturn(user)
		Mockito.`when`(conversations.deleteConversation(123, " conversation ")).thenReturn(true)
		val response = controller.deleteConversation(null, "Bearer valid", " conversation ")
		assertEquals(200, response.statusCode.value())
		assertEquals("{\"success\":true}", response.body)
		Mockito.verify(conversations).deleteConversation(123, " conversation ")
		Mockito.verify(llm.conversationService).delete("web:123:conversation")
	}
}
