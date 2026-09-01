package org.qo.services.llmServices

import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import org.qo.services.llmServices.tools.Tools
import org.springframework.stereotype.Service
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption

@Service
class LLMToolService(
	private val registeredTools: List<Tools>,
) {
	private val qoGroupId = System.getenv("LLM_QO_GROUP_ID")?.trim()?.toLongOrNull()
	private val failureLogPath: Path = Path.of("data/llm/toolcall-failure.log")
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
		val byId = registeredTools.associateBy { it.id }
		order.map { id -> byId[id] ?: error("Missing definition for tool: $id") }
	}

	fun enabled(): Boolean = readBoolean("LLM_TOOLS_ENABLED", true)

	fun definitions(): JsonArray = JsonArray().apply {
		tools.forEach { add(it.definition.deepCopy()) }
	}

	suspend fun execute(name: String, rawArguments: String?, context: LLMToolContext): String {
		if (name in qoScopedToolIds && (qoGroupId == null || context.groupId != qoGroupId)) {
			val result = errorResult("qo_group_required", "该工具只能在 QO 官方群中使用")
			logFailure(name, rawArguments, context, result)
			return result
		}
		val args = parseArguments(rawArguments)
		val result = runCatching {
			tools.firstOrNull { it.id == name }
				?.execute(args, context)
				?: errorResult("unknown_tool", "未知工具：$name")
		}.getOrElse { errorResult("tool_error", it.message ?: "工具执行失败") }
		if (isFailure(result)) {
			logFailure(name, rawArguments, context, result)
		}
		return result
	}

	private fun isFailure(result: String): Boolean =
		runCatching { JsonParser.parseString(result).asJsonObject.has("error") }.getOrDefault(false)

	private fun logFailure(name: String, rawArguments: String?, context: LLMToolContext, result: String) {
		val parsed = runCatching { JsonParser.parseString(result).asJsonObject }.getOrNull()
		val entry = gson.toJson(JsonObject().apply {
			addProperty("tool", name)
			addProperty("arguments", rawArguments?.take(500))
			addProperty("group_id", context.groupId)
			addProperty("uid", context.uid)
			addProperty("name", context.name)
			addProperty("error", parsed?.get("error")?.asString ?: "unknown")
			addProperty("message", (parsed?.get("message")?.asString ?: result).take(500))
			addProperty("time", System.currentTimeMillis())
		})
		println("[LLMTool] call failed: $entry")
		runCatching {
			failureLogPath.parent?.let { Files.createDirectories(it) }
			Files.writeString(
				failureLogPath,
				entry + System.lineSeparator(),
				StandardCharsets.UTF_8,
				StandardOpenOption.CREATE,
				StandardOpenOption.APPEND,
			)
		}.onFailure { println("[LLMTool] failed to write failure log: ${it.message}") }
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
	val currentMessageId: Long? = null,
)
