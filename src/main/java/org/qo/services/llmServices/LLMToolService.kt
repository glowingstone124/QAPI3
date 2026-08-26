package org.qo.services.llmServices

import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import org.qo.services.llmServices.tools.Tools
import org.springframework.stereotype.Service

@Service
class LLMToolService(
	private val registeredTools: List<Tools>,
) {
	private val qoGroupId = System.getenv("LLM_QO_GROUP_ID")?.trim()?.toLongOrNull()
	private val qoScopedToolIds = setOf(
		"get_server_status",
		"get_player_rankings",
		"query_metro_lines",
		"search_minecraft_knowledge",
	)
	private val gson: Gson = GsonBuilder().disableHtmlEscaping().create()
	private val tools: List<Tools> by lazy {
		val order = listOf(
			"get_server_status",
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
		)
		val byId = registeredTools.associateBy { it.id }
		order.map { id -> byId[id] ?: error("Missing definition for tool: $id") }
	}

	fun enabled(): Boolean = readBoolean("LLM_TOOLS_ENABLED", true)

	fun definitions(): JsonArray = JsonArray().apply {
		tools.forEach { add(it.definition.deepCopy()) }
	}

	suspend fun execute(name: String, rawArguments: String?, context: LLMToolContext): String {
		if (name in qoScopedToolIds && (qoGroupId == null || context.groupId != qoGroupId)) {
			return errorResult("qo_group_required", "该工具只能在 QO 官方群中使用")
		}
		val args = parseArguments(rawArguments)
		return runCatching {
			tools.firstOrNull { it.id == name }
				?.execute(args, context)
				?: errorResult("unknown_tool", "未知工具：$name")
		}.getOrElse { errorResult("tool_error", it.message ?: "工具执行失败") }
	}

	private fun parseArguments(rawArguments: String?): JsonObject {
		if (rawArguments.isNullOrBlank()) {
			return JsonObject()
		}
		return runCatching { JsonParser.parseString(rawArguments).asJsonObject }
			.getOrDefault(JsonObject())
	}

	private fun errorResult(code: String, message: String): String =
		gson.toJson(JsonObject().apply {
			addProperty("error", code)
			addProperty("message", message)
		})

	private fun readBoolean(name: String, defaultValue: Boolean): Boolean =
		System.getenv(name)?.trim()?.lowercase()?.toBooleanStrictOrNull() ?: defaultValue
}

data class LLMToolContext(
	val groupId: Long?,
	val uid: String?,
	val name: String?,
	val currentMessage: String? = null,
)
