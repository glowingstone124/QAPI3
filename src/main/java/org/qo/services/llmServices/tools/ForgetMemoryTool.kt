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
		description = "删除一条结构化记忆。用户明确要求忘记、删除或取消某项记忆时使用。优先使用 memory_id 精确删除；或提供 subject + memory_key 精确匹配；关键词匹配只删除最佳匹配的一条。",
		properties = linkedMapOf(
			"memory_id" to ToolSupport.property(type = "string", description = "search_memory 返回的记忆 ID。"),
			"query" to ToolSupport.property(type = "string", description = "没有 ID 时用于语义匹配待删除记忆的关键词。"),
			"subject" to ToolSupport.property(type = "string", description = "可选记忆主体，配合 memory_key 可精确删除特定记录。"),
			"memory_key" to ToolSupport.property(type = "string", description = "可选事实属性键。"),
			"scope" to ToolSupport.property(type = "string", description = "作用域：group 或 global。私聊默认 global，群聊默认 group。"),
		)
	)

	override suspend fun execute(args: JsonObject, context: LLMToolContext): String {
		val requestedScope = args.stringArgument("scope")?.lowercase()?.trim()
		val targetGroupId = if (context.groupId == null || requestedScope == "global") 0L else context.groupId
		val memoryId = args.stringArgument("memory_id")
		val query = args.stringArgument("query")
		val subject = args.stringArgument("subject")
		val memoryKey = args.stringArgument("memory_key")
		if (memoryId.isNullOrBlank() && query.isNullOrBlank() && subject.isNullOrBlank()) {
			return ToolSupport.errorResult("bad_arguments", "memory_id、query 或 subject 至少提供一个")
		}
		val removed = llmMemoryService.forget(
			groupId = targetGroupId,
			memoryId = memoryId,
			query = query,
			sourceUid = context.uid,
			subject = subject,
			memoryKey = memoryKey,
		)
		return ToolSupport.result(id) {
			addProperty("group_id", targetGroupId)
			addProperty("scope", if (targetGroupId == 0L) "global" else "group")
			addProperty("removed", removed.size)
			add("memories", ToolSupport.gson.toJsonTree(removed))
		}
	}
}
