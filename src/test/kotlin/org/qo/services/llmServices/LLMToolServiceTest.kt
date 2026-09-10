package org.qo.services.llmServices

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import org.qo.services.llmServices.tools.Tools
import kotlin.test.assertEquals

class LLMToolServiceTest {
	private val toolIds = listOf(
		"get_server_status",
		"get_qo_player_profile",
		"get_current_date",
		"get_player_rankings",
		"query_metro_lines",
		"search_minecraft_knowledge",
		"search_chat_history",
		"get_member_profile",
		"upsert_member_profile",
		"forget_member_profile_field",
		"add_memory",
		"search_memory",
		"forget_memory",
		"get_remain_balance",
		"get_user_quota",
		"set_msg_emoji_like",
	)

	private val service = LLMToolService(toolIds.map(::StubTool))

	@Test
	fun `minecraft requests can execute qo scoped tools without group mapping`(): Unit = runBlocking {
		val result = service.execute(
			"query_metro_lines",
			"{}",
			LLMToolContext(groupId = null, uid = "10001", name = "player", source = "minecraft"),
		)

		assertEquals("query_metro_lines", JsonParser.parseString(result).asJsonObject.get("tool").asString)
	}

	@Test
	fun `non minecraft requests still require the qo group`() = runBlocking {
		val result = service.execute(
			"query_metro_lines",
			"{}",
			LLMToolContext(groupId = null, uid = "10001", name = "user", source = "qq"),
		)

		assertEquals("qo_group_required", JsonParser.parseString(result).asJsonObject.get("error").asString)
	}

	private class StubTool(
		override val id: String,
	) : Tools {
		override val definition = JsonObject().apply {
			addProperty("type", "function")
			add("function", JsonObject().apply {
				addProperty("name", id)
				addProperty("description", id)
				add("parameters", JsonObject().apply { addProperty("type", "object") })
			})
		}

		override suspend fun execute(args: JsonObject, context: LLMToolContext): String =
			JsonObject().apply { addProperty("tool", id) }.toString()
	}
}
