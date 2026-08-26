package org.qo.services.llmServices.tools

import com.google.gson.JsonObject
import org.qo.services.llmServices.LLMGroupChatPolicy
import org.qo.services.llmServices.LLMMemberProfileService
import org.qo.services.llmServices.LLMRememberProtocol
import org.qo.services.llmServices.LLMToolContext
import org.springframework.stereotype.Component

@Component
class UpsertMemberProfileTool(
	private val profileService: LLMMemberProfileService,
) : Tools {
	override val id = "upsert_member_profile"
	override val definition = ToolSupport.functionTool(
		name = id,
		description = "为当前提问者的 QQ uid 新增或更新一个画像字段。仅当当前消息使用 `/remember 内容` 协议时才可调用；普通群聊和自然语言中的临时称呼、格式、语气、文体或角色要求不得自动持久化。仅保存本人明确确认的稳定身份、群昵称、偏好，或根据这些已确认事实生成的不含推测的简短总结；禁止保存密码、令牌、住址等敏感信息。",
		properties = linkedMapOf(
			"qq_uid" to ToolSupport.property(type = "string", description = "画像所属 QQ 号。必须等于当前提问者 uid。"),
			"field_key" to ToolSupport.property(type = "string", description = "属性键，例如 preferred_name、favorite_game、response_style、summary 或 group_nickname。"),
			"value" to ToolSupport.property(type = "string", description = "属性值。必须来自用户明确表达，不得添加推测。"),
			"scope" to ToolSupport.property(type = "string", description = "global 或 group。群昵称必须使用 group；其他字段默认 global。"),
		),
		required = listOf("qq_uid", "field_key", "value"),
	)

	override suspend fun execute(args: JsonObject, context: LLMToolContext): String {
		if (LLMRememberProtocol.payload(context.currentMessage) == null) {
			return ToolSupport.errorResult(
				"persistence_consent_required",
				"持久画像只能通过 `/remember 内容` 协议更新",
			)
		}
		val currentUid = context.uid?.toLongOrNull()
			?: return ToolSupport.errorResult("missing_uid", "缺少当前提问者 QQ uid")
		val targetUid = args.get("qq_uid")?.takeIf { !it.isJsonNull }?.asString?.trim()?.toLongOrNull()
			?: return ToolSupport.errorResult("bad_arguments", "qq_uid 必须是有效 QQ 号")
		if (targetUid != currentUid) {
			return ToolSupport.errorResult("forbidden_target", "只能更新当前提问者本人的画像")
		}
		val fieldKey = args.get("field_key")?.takeIf { !it.isJsonNull }?.asString?.trim().orEmpty()
		val value = args.get("value")?.takeIf { !it.isJsonNull }?.asString?.trim().orEmpty()
		if (fieldKey.isBlank() || value.isBlank()) {
			return ToolSupport.errorResult("bad_arguments", "field_key 和 value 不能为空")
		}
		val category = LLMGroupChatPolicy.EXPLICIT_USER_PROFILE_CATEGORY
		val requestedScope = args.get("scope")?.takeIf { !it.isJsonNull }?.asString?.trim()?.lowercase()
		if (fieldKey.equals("group_nickname", true) && requestedScope == "global") {
			return ToolSupport.errorResult("bad_arguments", "group_nickname 必须使用 group 作用域")
		}
		val groupScoped = requestedScope == "group" || (requestedScope == null && fieldKey.equals("group_nickname", true))
		if (requestedScope != null && requestedScope !in setOf("global", "group")) {
			return ToolSupport.errorResult("bad_arguments", "scope 只能是 global 或 group")
		}
		val groupId = if (groupScoped) context.groupId
			?: return ToolSupport.errorResult("missing_group", "群范围画像字段需要群上下文") else null
		val mutation = profileService.upsertField(
			uid = targetUid,
			fieldKey = fieldKey,
			value = value,
			category = category,
			groupId = groupId,
			sourceUid = context.uid,
			sourceName = context.name,
		) ?: return ToolSupport.errorResult("bad_arguments", "画像字段无效")
		return ToolSupport.gson.toJson(JsonObject().apply {
			addProperty("tool", id)
			addProperty("saved", true)
			addProperty("created", mutation.created)
			addProperty("changed", mutation.changed)
			addProperty("qq_uid", targetUid)
			addProperty("profile_id", mutation.profile.profileId)
			add("field", ToolSupport.gson.toJsonTree(mutation.field))
		})
	}
}
