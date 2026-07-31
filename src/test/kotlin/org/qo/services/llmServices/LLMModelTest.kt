package org.qo.services.llmServices

import kotlin.test.Test
import kotlin.test.assertEquals

class LLMModelTest {
	@Test
	fun `model presets resolve to supported upstream model names`() {
		assertEquals("deepseek-v4-flash", LLMServices.MODELS.FAST.apiName)
		assertEquals("deepseek-v4-pro", LLMServices.MODELS.THINKING.apiName)
		assertEquals(LLMServices.MODELS.FAST, LLMServices.MODELS.fromRequest("fast"))
		assertEquals(LLMServices.MODELS.THINKING, LLMServices.MODELS.fromRequest("thinking"))
	}
}
