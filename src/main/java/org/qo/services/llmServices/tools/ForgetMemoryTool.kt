package org.qo.services.llmServices.tools

import com.google.gson.JsonObject
import org.qo.services.llmServices.LLMMemoryService
import org.qo.services.llmServices.LLMToolContext
import org.springframework.stereotype.Component

@Component
class ForgetMemoryTool(
	private val llmMemoryService: LLMMemoryService,
) : Tools {
	override val id = "forget_memory"
	override val definition = ToolSupport.functionTool(
		name = id,
		description = "删除当前群的一条结构化长期记忆。用户明确要求忘记或删除记忆时使用。优先使用 memory_id 精确删除；关键词只删除最佳匹配的一条。",
		properties = linkedMapOf(
			"memory_id" to ToolSupport.property(type = "string", description = "search_memory 返回的记忆 ID。"),
			"query" to ToolSupport.property(type = "string", description = "没有 ID 时用于匹配待删除记忆的关键词。")
		)
	)

	override suspend fun execute(args: JsonObject, context: LLMToolContext): String {
		val groupId = context.groupId
			?: return ToolSupport.errorResult("missing_group", "缺少群上下文，无法删除记忆")
		val memoryId = args.get("memory_id")?.takeIf { !it.isJsonNull }?.asString?.trim()
		val query = args.get("query")?.takeIf { !it.isJsonNull }?.asString?.trim()
		if (memoryId.isNullOrBlank() && query.isNullOrBlank()) {
			return ToolSupport.errorResult("bad_arguments", "memory_id 和 query 至少提供一个")
		}
		val removed = llmMemoryService.forget(groupId, memoryId, query)
		return ToolSupport.gson.toJson(JsonObject().apply {
			addProperty("tool", id)
			addProperty("group_id", groupId)
			addProperty("removed", removed.size)
			add("memories", ToolSupport.gson.toJsonTree(removed))
		})
	}
}
