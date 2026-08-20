package org.qo.services.llmServices.tools

import com.google.gson.JsonObject
import org.qo.services.llmServices.LLMBalanceService
import org.qo.services.llmServices.LLMToolContext
import org.springframework.stereotype.Component

@Component
class GetRemainBalanceTool(
	private val balanceService: LLMBalanceService,
) : Tools {
	override val id = "get_remain_balance"
	override val definition = ToolSupport.functionTool(
		name = id,
		description = """
            查询当前 LLM API 账户的剩余 token 余额。
            当用户询问账户余额、剩余 token、还可以使用多少 token 或 API 剩余额度时使用。
            返回当前账户剩余的余额数量CNY。
        """.trimIndent(),
		properties = linkedMapOf()
	)

	override suspend fun execute(args: JsonObject, context: LLMToolContext): String {
		val balance = balanceService.getBalance()
		if (!balance.first) {
			return ToolSupport.errorResult("invalid_balance", "LLM API不支持余额调用。")
		}
		return ToolSupport.gson.toJson(JsonObject().apply {
			addProperty("tool", id)
			addProperty("balance", balance.second)
			addProperty("unit", "CNY")
		})
	}
}
