package org.qo.services.llmServices.tools

import com.google.gson.JsonObject
import org.qo.services.llmServices.LLMMemoryService
import org.qo.services.llmServices.LLMToolContext
import org.springframework.stereotype.Component

@Component
class SearchMemoryTool(
	private val llmMemoryService: LLMMemoryService,
) : Tools {
	override val id = "search_memory"
	override val definition = ToolSupport.functionTool(
		name = id,
		description = "按主体或内容查询当前群的结构化长期记忆。用户询问以前要求记住的内容、偏好、约定或决定时使用。",
		properties = linkedMapOf(
			"query" to ToolSupport.property(type = "string", description = "要查询的人、事、偏好、约定或关键词。")
		),
		required = listOf("query")
	)

	override suspend fun execute(args: JsonObject, context: LLMToolContext): String {
		val groupId = context.groupId
			?: return ToolSupport.errorResult("missing_group", "缺少群上下文，无法查询记忆")
		val query = args.get("query")?.takeIf { !it.isJsonNull }?.asString?.trim().orEmpty()
		if (query.isBlank()) return ToolSupport.errorResult("bad_arguments", "query 不能为空")
		val memories = llmMemoryService.search(groupId, query, 8)
		return ToolSupport.gson.toJson(JsonObject().apply {
			addProperty("tool", id)
			addProperty("group_id", groupId)
			addProperty("returned", memories.size)
			add("memories", ToolSupport.gson.toJsonTree(memories))
		})
	}
}
