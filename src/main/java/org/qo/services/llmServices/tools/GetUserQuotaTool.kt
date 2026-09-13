package org.qo.services.llmServices.tools

import com.google.gson.JsonObject
import org.qo.orm.UserORM
import org.qo.services.llmServices.LLMDailyQuotaService
import org.qo.services.llmServices.LLMQuotaStatus
import org.qo.services.llmServices.LLMToolContext
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.stereotype.Component

@Component
class GetUserQuotaTool @Autowired constructor(
	private val dailyQuotaService: LLMDailyQuotaService,
) : Tools {
	private val userORM: UserORM = UserORM()

	override val id = "get_user_quota"
	override val definition = ToolSupport.functionTool(
		name = id,
		description = """
			查询当前用户每周剩余的 LLM Units、Paid Credits、每周额度上限与账户类型（QO绑定用户或游客）。
			当用户询问自己本周额度、剩余 Credits、对话次数限制，或者咨询如何获取更多额度时使用。
		""".trimIndent(),
		properties = linkedMapOf(
			"target_uid" to ToolSupport.property(
				type = "string",
				description = "可选，要查询的用户的 QQ 号。如果不传则默认为当前提问用户本人。",
			),
		),
	)

	override suspend fun execute(args: JsonObject, context: LLMToolContext): String {
		val currentUid = context.uid?.toLongOrNull()
		val targetUid = args.get("target_uid")?.takeIf { !it.isJsonNull }?.asString?.trim()?.toLongOrNull() ?: currentUid
		if (targetUid == null || targetUid <= 0) {
			return ToolSupport.errorResult("missing_uid", "缺少有效的用户 QQ 号")
		}

		if (targetUid != currentUid) return ToolSupport.errorResult("forbidden", "只能查询自己的额度")
		val user = runCatching { userORM.readAsync(targetUid) }.getOrNull()
		val hasAccount = user != null
		val decision = dailyQuotaService.snapshot(targetUid, hasAccount)
		if (decision.status == LLMQuotaStatus.UNAVAILABLE) {
			return ToolSupport.errorResult("quota_unavailable", "额度服务暂时不可用")
		}
		val view = decision.view

		return ToolSupport.gson.toJson(JsonObject().apply {
			addProperty("tool", id)
			addProperty("uid", targetUid)
			addProperty("has_qo_account", hasAccount)
			addProperty("account_type", if (hasAccount) "QO绑定用户" else "游客")
			addProperty("limit", view.limit)
			addProperty("used", view.used)
			addProperty("remaining", view.remaining)
			addProperty("reset_at", view.resetAtEpochSeconds)
			addProperty("paid_credits", view.paidCredits)
			addProperty("period", "weekly")
			if (!hasAccount) {
				addProperty("tip", "QQ 身份每周 30 Units。注册 QO 账户后同一身份提升至每周 90 Units，已用额度保留。")
			}
		})
	}
}
