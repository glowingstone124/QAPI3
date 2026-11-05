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
		description = "新增或更新一条结构化记忆。可在对话中主动调用，记录或更新用户提及的有价值事实、近期事件、偏好或纠正。支持核心常驻(core)、近期情景(episodic)和长期归档(semantic)分层，以及群(group)和个人全局(global)作用域。相同 subject 和 memory_key 会更新原记录。",
		properties = linkedMapOf(
			"subject" to ToolSupport.property(type = "string", description = "记忆主体，例如玩家名、项目名、地点或群约定。"),
			"memory_key" to ToolSupport.property(type = "string", description = "事实属性键，例如 favorite_drink、preferred_name、project_role、event_time。"),
			"fact" to ToolSupport.property(type = "string", description = "需要保存的事实。不要包含用户未明确表达的额外推测。"),
			"category" to ToolSupport.property(type = "string", description = "分类，例如 preference、identity、project、decision、schedule；默认 general。"),
			"tier" to ToolSupport.property(type = "string", description = "记忆层级：core（核心常驻，如长期偏好、身份设定、重要规则）、episodic（近期情景，如近期动态、短期计划、最近状态，默认14天衰减）、semantic（长期归档知识/历史事件）。"),
			"scope" to ToolSupport.property(type = "string", description = "作用域：group（当前群共享记忆）或 global（当前用户跨群/私聊通用记忆）。私聊默认 global，群聊默认 group。"),
			"importance" to ToolSupport.property(type = "integer", description = "重要程度 1-5，默认 3。"),
			"expires_in_days" to ToolSupport.property(type = "integer", description = "可选有效天数。临时安排应设置；长期事实不设置。episodic 记忆默认14天。"),
		),
		required = listOf("subject", "memory_key", "fact")
	)

	override suspend fun execute(args: JsonObject, context: LLMToolContext): String {
		val requestedScope = args.stringArgument("scope")?.lowercase()?.trim()
		val targetGroupId = if (context.groupId == null || requestedScope == "global") 0L else context.groupId
		val fact = (args.get("fact") ?: args.get("data"))?.takeIf { !it.isJsonNull }?.asString?.trim().orEmpty()
		val subject = args.stringArgument("subject").orEmpty()
		val memoryKey = args.stringArgument("memory_key").orEmpty()
		if (subject.isBlank() || memoryKey.isBlank() || fact.isBlank()) {
			return ToolSupport.errorResult("bad_arguments", "subject、memory_key 和 fact 不能为空")
		}
		val category = args.argument("category")?.asString ?: "general"
		val tier = args.stringArgument("tier")
		val importance = args.argument("importance")?.asInt ?: 3
		val expiryDays = args.argument("expires_in_days")?.asLong?.coerceIn(1, 3650)
		val expiresAt = expiryDays?.let { System.currentTimeMillis() + it * 86_400_000L }
		val mutation = llmMemoryService.upsertMemory(
			groupId = targetGroupId,
			subject = subject,
			memoryKey = memoryKey,
			fact = fact,
			category = category,
			sourceUid = context.uid,
			sourceName = context.name,
			expiresAt = expiresAt,
			tier = tier,
			importance = importance,
		) ?: return ToolSupport.errorResult("bad_arguments", "记忆内容无效")
		return ToolSupport.result(id) {
			addProperty("saved", true)
			addProperty("created", mutation.created)
			addProperty("group_id", targetGroupId)
			addProperty("scope", if (targetGroupId == 0L) "global" else "group")
			add("memory", ToolSupport.gson.toJsonTree(mutation.record))
		}
	}
}
