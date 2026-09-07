package org.qo.services.llmServices

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class LLMPeriodicSummaryParsingTest {
	@Test
	fun `accepts only profiles for qquids present in the archived batch`() {
		val result = parseGroupAndMemberSummary(
			"""
			```json
			{"group_summary":"正在讨论新站点","member_profiles":[{"qquid":42,"summary":"常做 Web 项目"},{"qquid":99,"summary":"伪造画像"}]}
			```
			""".trimIndent(),
			setOf(42),
		)!!

		assertEquals("正在讨论新站点", result.groupSummary)
		assertEquals(listOf(42L), result.memberProfiles.map { it.qqUid })
	}

	@Test
	fun `rejects malformed output so archive cursor is not advanced`() {
		assertNull(parseGroupAndMemberSummary("普通文本", setOf(42)))
		assertNull(parseGroupAndMemberSummary("{\"member_profiles\":[]}", setOf(42)))
	}
}
