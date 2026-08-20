package org.qo.services.llmServices.tools

import com.google.gson.JsonObject
import org.qo.services.llmServices.LLMChatHistoryService
import org.qo.services.llmServices.LLMToolContext
import org.springframework.stereotype.Component

@Component
class SearchChatHistoryTool(
	private val chatHistoryService: LLMChatHistoryService,
) : Tools {
	override val id = "search_chat_history"
	override val definition = ToolSupport.functionTool(
		name = id,
		description = "检索当前群已经持久化的历史聊天。当用户询问较早讨论、某人以前说过什么、旧决定或当前滑动窗口之外的信息时使用。查询范围始终限制在当前群。",
		properties = linkedMapOf(
			"query" to ToolSupport.property(type = "string", description = "聊天内容关键词；可与 uid、时间范围组合。"),
			"uid" to ToolSupport.property(type = "integer", description = "可选 QQ uid，只查该成员。"),
			"from_time" to ToolSupport.property(type = "integer", description = "可选起始 Unix 时间戳，支持秒或毫秒。"),
			"to_time" to ToolSupport.property(type = "integer", description = "可选结束 Unix 时间戳，支持秒或毫秒。"),
			"limit" to ToolSupport.property(type = "integer", description = "返回条数，默认 12，最大 30。"),
		),
		required = listOf("query")
	)

	override suspend fun execute(args: JsonObject, context: LLMToolContext): String {
		val groupId = context.groupId
			?: return ToolSupport.errorResult("missing_group", "缺少群上下文，无法查询聊天历史")
		val query = args.get("query")?.takeIf { !it.isJsonNull }?.asString?.trim().orEmpty()
		if (query.isBlank()) return ToolSupport.errorResult("bad_arguments", "query 不能为空")
		val uid = args.get("uid")?.takeIf { !it.isJsonNull }?.asLong
		val fromTime = normalizeTimestamp(args.get("from_time")?.takeIf { !it.isJsonNull }?.asLong)
		val toTime = normalizeTimestamp(args.get("to_time")?.takeIf { !it.isJsonNull }?.asLong)
		val limit = args.get("limit")?.takeIf { !it.isJsonNull }?.asInt ?: 12
		val messages = chatHistoryService.search(groupId, query, uid, fromTime, toTime, limit)
			.map { it.copy(content = it.content.take(1000)) }
		return ToolSupport.gson.toJson(JsonObject().apply {
			addProperty("tool", id)
			addProperty("group_id", groupId)
			addProperty("returned", messages.size)
			add("messages", ToolSupport.gson.toJsonTree(messages))
		})
	}

	private fun normalizeTimestamp(value: Long?): Long? =
		value?.let { if (it in 1..9_999_999_999L) it * 1000 else it }
}
