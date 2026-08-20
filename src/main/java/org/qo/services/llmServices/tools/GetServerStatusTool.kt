package org.qo.services.llmServices.tools

import com.google.gson.JsonObject
import org.qo.services.gameStatusService.Status
import org.qo.services.llmServices.LLMToolContext
import org.springframework.stereotype.Component

@Component
class GetServerStatusTool(
	private val status: Status,
) : Tools {
	override val id = "get_server_status"
	override val definition = ToolSupport.functionTool(
		name = id,
		description = "查询 QO Minecraft 服务器当前状态、在线人数、总注册人数和 MSPT。用户询问服务器人数、在线人数、服务器状态时使用。",
		properties = linkedMapOf(
			"server_id" to ToolSupport.property(
				type = "integer",
				description = "服务器编号。默认 1；survival/生存为 1，creative/创造为 4。"
			),
			"server_name" to ToolSupport.property(
				type = "string",
				description = "服务器名称，可选 survival、生存、creative、创造。"
			),
		)
	)

	override suspend fun execute(args: JsonObject, context: LLMToolContext): String {
		val serverId = args.get("server_id")?.takeIf { !it.isJsonNull }?.asInt
			?: serverIdFromName(args.get("server_name")?.takeIf { !it.isJsonNull }?.asString)
			?: 1
		val data = status.downloadAsync(serverId)
		return ToolSupport.gson.toJson(JsonObject().apply {
			addProperty("tool", id)
			addProperty("server_id", serverId)
			add("status", data.deepCopy())
		})
	}

	private fun serverIdFromName(name: String?): Int? = when (name?.trim()?.lowercase().orEmpty()) {
		"survival", "生存", "生存服" -> 1
		"creative", "创造", "创造服" -> 4
		else -> null
	}
}
