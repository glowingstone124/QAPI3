package org.qo.services.llmServices.tools

import com.google.gson.JsonObject
import org.qo.services.llmServices.LLMMemoryService
import org.qo.services.llmServices.LLMToolContext
import org.springframework.stereotype.Component

@Component
class AddMemoryTool(
	private val llmMemoryService: LLMMemoryService,
) : Tools {
	override val id = "add_memory"
	override val definition = ToolSupport.functionTool(
		name = id,
		description = "新增或更新一条结构化群长期记忆。你可以选择记住许多信息。相同 subject 和 memory_key 会更新原记录。",
		properties = linkedMapOf(
			"subject" to ToolSupport.property(type = "string", description = "记忆主体，例如玩家名、项目名、地点或群约定。"),
			"memory_key" to ToolSupport.property(type = "string", description = "事实属性键，例如 favorite_drink、preferred_name、project_role、event_time。"),
			"fact" to ToolSupport.property(type = "string", description = "需要保存的事实。不要包含用户未明确要求保存的额外推测。"),
			"category" to ToolSupport.property(type = "string", description = "分类，例如 preference、identity、project、decision、schedule；默认 general。"),
			"expires_in_days" to ToolSupport.property(type = "integer", description = "可选有效天数。临时安排应设置；长期事实不设置。"),
		),
		required = listOf("subject", "memory_key", "fact")
	)

	override suspend fun execute(args: JsonObject, context: LLMToolContext): String {
		val groupId = context.groupId
			?: return ToolSupport.errorResult("missing_group", "缺少群上下文，无法保存记忆")
		val fact = (args.get("fact") ?: args.get("data"))?.takeIf { !it.isJsonNull }?.asString?.trim().orEmpty()
		val subject = args.get("subject")?.takeIf { !it.isJsonNull }?.asString?.trim().orEmpty()
		val memoryKey = args.get("memory_key")?.takeIf { !it.isJsonNull }?.asString?.trim().orEmpty()
		if (subject.isBlank() || memoryKey.isBlank() || fact.isBlank()) {
			return ToolSupport.errorResult("bad_arguments", "subject、memory_key 和 fact 不能为空")
		}
		val category = args.get("category")?.takeIf { !it.isJsonNull }?.asString ?: "general"
		val expiryDays = args.get("expires_in_days")?.takeIf { !it.isJsonNull }?.asLong?.coerceIn(1, 3650)
		val expiresAt = expiryDays?.let { System.currentTimeMillis() + it * 86_400_000L }
		val mutation = llmMemoryService.upsertMemory(
			groupId = groupId,
			subject = subject,
			memoryKey = memoryKey,
			fact = fact,
			category = category,
			sourceUid = context.uid,
			sourceName = context.name,
			expiresAt = expiresAt,
		) ?: return ToolSupport.errorResult("bad_arguments", "记忆内容无效")
		return ToolSupport.gson.toJson(JsonObject().apply {
			addProperty("tool", id)
			addProperty("saved", true)
			addProperty("created", mutation.created)
			addProperty("group_id", groupId)
			add("memory", ToolSupport.gson.toJsonTree(mutation.record))
		})
	}
}
