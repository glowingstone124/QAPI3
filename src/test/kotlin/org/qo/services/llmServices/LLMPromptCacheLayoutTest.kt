package org.qo.services.llmServices

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.google.gson.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class LLMPromptCacheLayoutTest {
	@Test
	fun `persists dynamic context as part of the user turn for prefix reuse`() {
		val firstTurn = LLMPromptCacheLayout.prepareCurrentTurn(messages("first question"), "retrieved context one")
		val stableSystem = message("system", JsonPrimitive("stable rules"))
		val firstCompletedPrefix = JsonArray().apply {
			add(stableSystem.deepCopy())
			firstTurn.messages.forEach { add(it.deepCopy()) }
			add(message("assistant", JsonPrimitive("first answer")))
		}
		val history = JsonArray().apply {
			add(message("user", firstTurn.persistedUserContent))
			add(message("assistant", JsonPrimitive("first answer")))
		}
		val secondTurn = LLMPromptCacheLayout.prepareCurrentTurn(messages("second question"), "retrieved context two")
		val secondRequest = JsonArray().apply {
			add(stableSystem.deepCopy())
			history.forEach { add(it.deepCopy()) }
			secondTurn.messages.forEach { add(it.deepCopy()) }
		}

		firstCompletedPrefix.forEachIndexed { index, message -> assertEquals(message, secondRequest[index]) }
		assertTrue(secondRequest[3].asJsonObject.get("content").asString.contains("retrieved context two"))
	}

	@Test
	fun `prepends dynamic context without changing multimodal parts`() {
		val messages = JsonParser.parseString(
			"""{"messages":[{"role":"user","content":[{"type":"text","text":"question"},{"type":"image_url","image_url":{"url":"https://example.com/a.png"}}]}]}"""
		).asJsonObject.getAsJsonArray("messages")

		val turn = LLMPromptCacheLayout.prepareCurrentTurn(messages, "retrieved context")
		val content = turn.messages[0].asJsonObject.getAsJsonArray("content")

		assertEquals(3, content.size())
		assertTrue(content[0].asJsonObject.get("text").asString.contains("retrieved context"))
		assertEquals("question", content[1].asJsonObject.get("text").asString)
		assertEquals("image_url", content[2].asJsonObject.get("type").asString)
	}

	private fun messages(content: String): JsonArray = JsonArray().apply {
		add(message("user", JsonPrimitive(content)))
	}

	private fun message(role: String, content: com.google.gson.JsonElement): JsonObject = JsonObject().apply {
		addProperty("role", role)
		add("content", content)
	}
}
