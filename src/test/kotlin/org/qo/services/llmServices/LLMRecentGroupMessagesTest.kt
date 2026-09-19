package org.qo.services.llmServices

import com.google.gson.JsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class LLMRecentGroupMessagesTest {
	@Test
	fun `recent messages retain distinct ids and omit the triggering message`() {
		val summary = JsonObject().apply { addProperty("facts", "群摘要") }
		val context = withRecentGroupMessages(
			summary,
			listOf(
				record("onebot:3", 11, "同名", "当前问题", 3),
				record("onebot:2", 22, "同名", "第二个人发言", 2),
				record("onebot:1", 11, "同名", "第一个人发言", 1),
			),
			currentMessageId = 3,
			currentUid = 11,
		)!!
		val recent = context.getAsJsonArray("recent_messages")
		val participants = context.getAsJsonArray("recent_participants")

		assertEquals("群摘要", context.get("facts").asString)
		assertFalse(summary.has("recent_messages"))
		assertEquals(2, recent.size())
		assertEquals(11, recent[0].asJsonObject.get("qquid").asLong)
		assertEquals("onebot:1", recent[0].asJsonObject.get("source_id").asString)
		assertEquals(true, recent[0].asJsonObject.get("is_current_sender").asBoolean)
		assertEquals("第一个人发言", recent[0].asJsonObject.get("text").asString)
		assertEquals(22, recent[1].asJsonObject.get("qquid").asLong)
		assertEquals(false, recent[1].asJsonObject.get("is_current_sender").asBoolean)
		assertEquals("同名", recent[1].asJsonObject.get("nickname").asString)
		assertEquals(2, participants.size())
		assertEquals(22, participants[0].asJsonObject.get("qquid").asLong)
		assertEquals(11, participants[1].asJsonObject.get("qquid").asLong)
	}

	@Test
	fun `recent context keeps twenty messages and caps long text`() {
		val messages = (25 downTo 1).map { number ->
			record("onebot:$number", number.toLong(), "user$number", "x".repeat(700), number.toLong())
		}
		val context = withRecentGroupMessages(JsonObject(), messages, null, 1)!!
		val recent = context.getAsJsonArray("recent_messages")

		assertEquals(20, recent.size())
		assertEquals("onebot:6", recent[0].asJsonObject.get("source_id").asString)
		assertEquals("onebot:25", recent[19].asJsonObject.get("source_id").asString)
		assertEquals(500, recent[0].asJsonObject.get("text").asString.length)
	}

	private fun record(sourceId: String, uid: Long, name: String, content: String, time: Long) =
		LLMChatHistoryRecord(sourceId, 100, uid, name, content, time, time)
}
