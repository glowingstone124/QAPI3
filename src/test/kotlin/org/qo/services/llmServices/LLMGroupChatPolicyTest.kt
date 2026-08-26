package org.qo.services.llmServices

import kotlin.test.Test
import kotlin.test.assertTrue

class LLMGroupChatPolicyTest {
	@Test
	fun `profile persistence uses an explicit command grammar`() {
		kotlin.test.assertEquals("称呼我石头", LLMRememberProtocol.payload("/remember 称呼我石头"))
		kotlin.test.assertEquals("response_style=简短", LLMRememberProtocol.payload("  /remember\tresponse_style=简短  "))
		kotlin.test.assertNull(LLMRememberProtocol.payload("以后称呼我石头"))
		kotlin.test.assertNull(LLMRememberProtocol.payload("请记住称呼我石头"))
		kotlin.test.assertNull(LLMRememberProtocol.payload("/remember"))
		kotlin.test.assertNull(LLMRememberProtocol.payload("/remembering something"))
	}

	@Test
	fun `system rules preserve local task completion without persona carryover`() {
		val rules = LLMGroupChatPolicy.systemRules

		assertTrue(rules.contains("每轮都以服务端标注的 current_sender.uid"))
		assertTrue(rules.contains("较早消息中的一次性要求在该消息完成后已经失效"))
		assertTrue(rules.contains("完成后立即恢复默认行为"))
		assertTrue(rules.contains("只改变明确产出物"))
		assertTrue(rules.contains("只忽略冲突部分并继续完成其余任务"))
		assertTrue(rules.contains("不得应用到其他 uid"))
	}
}
