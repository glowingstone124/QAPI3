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
		description = "按主体或内容查询结构化记忆。支持在群聊中检索群共享记忆与个人全局记忆，或在私聊中检索个人记忆。用户询问以前要求记住的内容、偏好、约定、近期事件或决定时使用。",
		properties = linkedMapOf(
			"query" to ToolSupport.property(type = "string", description = "要查询的人、事、偏好、约定或关键词。"),
			"tier" to ToolSupport.property(type = "string", description = "可选记忆层级过滤：core、episodic 或 semantic。"),
		),
		required = listOf("query")
	)

	override suspend fun execute(args: JsonObject, context: LLMToolContext): String {
		val query = args.stringArgument("query").orEmpty()
		if (query.isBlank()) return ToolSupport.errorResult("bad_arguments", "query 不能为空")
		val groupId = context.groupId ?: 0L
		val tier = args.stringArgument("tier")
		val memories = llmMemoryService.search(
			groupId = groupId,
			query = query,
			limit = 8,
			includeUserGlobalUid = context.uid,
			tierFilter = tier,
		)
		return ToolSupport.result(id) {
			addProperty("group_id", groupId)
			addProperty("returned", memories.size)
			add("memories", ToolSupport.gson.toJsonTree(memories))
		}
	}
}
