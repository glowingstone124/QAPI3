package org.qo.services.llmServices

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LLMHardOutputRulesTest {
	@Test
	fun `allows latex math expressions when platform is web kotshi`() {
		val rules = LLMServices.hardOutputRules(enableMarkdown = true, isWeb = true)

		assertTrue(rules.contains("允许输出 LaTeX 数学表达式"))
		assertFalse(rules.contains("不要输出 LaTeX 数学表达式"))
	}

	@Test
	fun `disallows latex math expressions when platform is not web`() {
		val rules = LLMServices.hardOutputRules(enableMarkdown = false, isWeb = false)

		assertTrue(rules.contains("不要输出 LaTeX 数学表达式"))
		assertFalse(rules.contains("允许输出 LaTeX 数学表达式"))
	}
}
