package org.qo.services.llmServices

import kotlin.test.Test
import kotlin.test.assertTrue

class LLMToolInstructionsTest {
	@Test
	fun `current date and time require the authoritative local tool`() {
		val rules = LLMToolInstructions.systemRules

		assertTrue(rules.contains("必须先调用 get_current_date"))
		assertTrue(rules.contains("不得用 web search 代替 get_current_date"))
		assertTrue(rules.contains("不得猜测"))
	}
}
