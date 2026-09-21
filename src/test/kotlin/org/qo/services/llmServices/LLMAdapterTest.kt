package org.qo.services.llmServices

import com.google.gson.JsonArray
import com.google.gson.JsonParser
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class LLMAdapterTest {
	@Test
	fun `registers an adapter for every protocol`() {
		val chat = JsonParser.parseString(
			"""{"model":"adapter-test","messages":[{"role":"user","content":"hello"}]}"""
		).asJsonObject

		LLMProtocol.entries.forEach { protocol ->
			val adapter = LLMAdapterRegistry.forProtocol(protocol)
			val request = adapter.adapt(
				LLMAdapterRequest(
					chat = chat,
					functionTools = JsonArray(),
					reasoningEffort = LLMReasoningEffort.NONE,
					webSearch = false,
				)
			)

			assertEquals(protocol, adapter.protocol)
			assertTrue(request.isJsonObject)
		}
	}
}
