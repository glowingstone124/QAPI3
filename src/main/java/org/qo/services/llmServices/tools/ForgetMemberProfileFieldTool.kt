package org.qo.services.llmServices.tools

import com.google.gson.JsonObject
import org.qo.services.llmServices.LLMMemberProfileService
import org.qo.services.llmServices.LLMToolContext
import org.springframework.stereotype.Component

@Component
class ForgetMemberProfileFieldTool(
	private val profileService: LLMMemberProfileService,
) : Tools {
	override val id = "forget_member_profile_field"
	override val definition = ToolSupport.functionTool(
		name = id,
		description = "删除当前提问者明确要求忘记的一个画像字段。",
		properties = linkedMapOf(
			"qq_uid" to ToolSupport.property(type = "string", description = "画像所属 QQ 号，必须等于当前提问者 uid。"),
			"field_key" to ToolSupport.property(type = "string", description = "待删除属性键。"),
			"scope" to ToolSupport.property(type = "string", description = "global 或 group；群昵称应使用 group。"),
		),
		required = listOf("qq_uid", "field_key"),
	)

	override suspend fun execute(args: JsonObject, context: LLMToolContext): String {
		val currentUid = context.uid?.toLongOrNull()
			?: return ToolSupport.errorResult("missing_uid", "缺少当前提问者 QQ uid")
		val targetUid = args.get("qq_uid")?.takeIf { !it.isJsonNull }?.asString?.trim()?.toLongOrNull()
			?: return ToolSupport.errorResult("bad_arguments", "qq_uid 必须是有效 QQ 号")
		if (targetUid != currentUid) {
			return ToolSupport.errorResult("forbidden_target", "只能删除当前提问者本人的画像")
		}
		val fieldKey = args.get("field_key")?.takeIf { !it.isJsonNull }?.asString?.trim().orEmpty()
		if (fieldKey.isBlank()) return ToolSupport.errorResult("bad_arguments", "field_key 不能为空")
		val scope = args.get("scope")?.takeIf { !it.isJsonNull }?.asString?.trim()?.lowercase()
			?: if (fieldKey.equals("group_nickname", true)) "group" else "global"
		if (scope !in setOf("global", "group")) {
			return ToolSupport.errorResult("bad_arguments", "scope 只能是 global 或 group")
		}
		val groupId = if (scope == "group") context.groupId
			?: return ToolSupport.errorResult("missing_group", "群范围画像字段需要群上下文") else null
		val removed = profileService.deleteField(targetUid, fieldKey, groupId)
		return ToolSupport.gson.toJson(JsonObject().apply {
			addProperty("tool", id)
			addProperty("qq_uid", targetUid)
			addProperty("field_key", fieldKey)
			addProperty("removed", removed)
		})
	}
}
