package org.qo.services.llmServices.tools

import com.google.gson.JsonObject
import org.qo.services.llmServices.LLMMemberProfileService
import org.qo.services.llmServices.LLMToolContext
import org.springframework.stereotype.Component

@Component
class GetMemberProfileTool(
	private val profileService: LLMMemberProfileService,
) : Tools {
	override val id = "get_member_profile"
	override val definition = ToolSupport.functionTool(
		name = id,
		description = "读取当前提问者以 QQ uid 为主键的持久化画像，包括全局偏好和当前群昵称。",
		properties = linkedMapOf(
			"qq_uid" to ToolSupport.property(type = "string", description = "要读取的 QQ 号，必须等于当前提问者 uid。"),
		),
	)

	override suspend fun execute(args: JsonObject, context: LLMToolContext): String {
		val currentUid = context.uid?.toLongOrNull()
			?: return ToolSupport.errorResult("missing_uid", "缺少当前提问者 QQ uid")
		val targetUid = args.get("qq_uid")?.takeIf { !it.isJsonNull }?.asString?.trim()?.toLongOrNull() ?: currentUid
		if (targetUid != currentUid) {
			return ToolSupport.errorResult("forbidden_target", "只能读取当前提问者本人的完整画像")
		}
		val profile = profileService.profile(targetUid, context.groupId)
		return ToolSupport.gson.toJson(JsonObject().apply {
			addProperty("tool", id)
			addProperty("qq_uid", targetUid)
			addProperty("found", profile != null)
			profile?.let { add("profile", ToolSupport.gson.toJsonTree(it)) }
		})
	}
}
