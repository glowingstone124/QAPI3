package org.qo.services.llmServices

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import kotlin.test.Test
import kotlin.test.assertEquals

class LLMWebSearchAdapterTest {
	@Test
	fun `keeps web search available to chat completions alongside local functions`() {
		val functionTools = JsonArray().apply {
			add(JsonObject().apply {
				addProperty("type", "function")
				add("function", JsonObject().apply { addProperty("name", "get_current_date") })
			})
		}

		val request = LLMWebSearchAdapter.enableChatCompletions(
			"""{"model":"provider-model","messages":[{"role":"user","content":"news"}],"tool_choice":"none"}""",
			functionTools,
		)

		assertEquals("function", request.getAsJsonArray("tools")[0].asJsonObject.get("type").asString)
		assertEquals(1, request.getAsJsonArray("tools").size())
		assertEquals(0, request.getAsJsonObject("web_search_options").size())
		assertEquals("auto", request.get("tool_choice").asString)
	}

	@Test
	fun `keeps web search enabled even when local tools are disabled`() {
		val request = LLMWebSearchAdapter.enableChatCompletions(
			"""{"model":"provider-model","messages":[{"role":"user","content":"hello"}]}""",
			JsonArray(),
		)

		assertEquals(0, request.getAsJsonObject("web_search_options").size())
		assertEquals(false, request.has("tools"))
		assertEquals(false, request.has("tool_choice"))
	}
}
