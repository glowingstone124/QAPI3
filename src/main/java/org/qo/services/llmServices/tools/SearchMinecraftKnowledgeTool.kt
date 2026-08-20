package org.qo.services.llmServices.tools

import com.google.gson.JsonObject
import org.qo.services.llmServices.LLMToolContext
import org.qo.services.llmServices.RAGService
import org.springframework.stereotype.Component

@Component
class SearchMinecraftKnowledgeTool(
	private val ragService: RAGService,
) : Tools {
	override val id = "search_minecraft_knowledge"
	override val definition = ToolSupport.functionTool(
		name = id,
		description = "检索 Minecraft、QO 服务器玩法、指令、规则和知识库资料。用户询问 Minecraft 知识或服务器资料时使用。",
		properties = linkedMapOf(
			"query" to ToolSupport.property(
				type = "string",
				description = "需要检索的问题或关键词。"
			),
		),
		required = listOf("query")
	)

	override suspend fun execute(args: JsonObject, context: LLMToolContext): String {
		val query = args.get("query")?.takeIf { !it.isJsonNull }?.asString?.trim().orEmpty()
		if (query.isBlank()) {
			return ToolSupport.errorResult("bad_arguments", "query 不能为空")
		}
		val content = ragService.buildContext(query, context.groupId)
		return ToolSupport.gson.toJson(JsonObject().apply {
			addProperty("tool", id)
			addProperty("query", query)
			addProperty("found", content != null)
			addProperty("content", content ?: "知识库没有检索到相关资料。")
		})
	}
}
