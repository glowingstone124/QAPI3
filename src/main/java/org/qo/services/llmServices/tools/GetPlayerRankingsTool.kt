package org.qo.services.llmServices.tools

import com.google.gson.JsonObject
import org.qo.services.llmServices.LLMToolContext
import org.qo.services.rankingServices.RankingService
import org.springframework.stereotype.Component

@Component
class GetPlayerRankingsTool(
	private val rankingService: RankingService,
) : Tools {
	override val id = "get_player_rankings"
	override val definition = ToolSupport.functionTool(
		name = id,
		description = "查询 QO 玩家挖掘方块、放置方块和累计在线时长榜单。用户询问榜单、排行、谁挖得最多、谁在线最久时使用。",
		properties = linkedMapOf(
			"limit" to ToolSupport.property(
				type = "integer",
				description = "每个榜单返回的人数，默认 10，最大 20。"
			),
		)
	)

	override suspend fun execute(args: JsonObject, context: LLMToolContext): String {
		val limit = args.get("limit")?.takeIf { !it.isJsonNull }?.asInt ?: 10
		return rankingService.leaderboards(limit.coerceIn(1, 20)).toString()
	}
}
