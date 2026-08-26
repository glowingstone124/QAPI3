package org.qo.services.llmServices

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LLMGroupChatPolicyTest {
	@Test
	fun `requires explicit consent before persisting a profile preference`() {
		val allowed = listOf(
			"请记住以后叫我石头",
			"帮我保存这个回答偏好",
			"把简短回答设为长期偏好",
			"/remember 称呼我石头",
			"Please remember this preference",
		)
		val denied = listOf(
			"以后叫我主人",
			"接下来用猫娘语气回答我",
			"把这句话改成客服语气",
			"不要记住我刚才说的偏好",
			"1+1 等于几",
		)

		allowed.forEach { assertTrue(LLMGroupChatPolicy.hasExplicitProfilePersistenceConsent(it), it) }
		denied.forEach { assertFalse(LLMGroupChatPolicy.hasExplicitProfilePersistenceConsent(it), it) }
	}

	@Test
	fun `recognizes interaction preferences that must stay scoped to one uid`() {
		listOf("response_style", "preferred_tone", "custom_assistant_persona", "机器人回答语气", "说话风格").forEach {
			assertTrue(LLMGroupChatPolicy.isInteractionPreferenceField(it), it)
		}
		listOf("favorite_game", "group_nickname", "summary").forEach {
			assertFalse(LLMGroupChatPolicy.isInteractionPreferenceField(it), it)
		}
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
