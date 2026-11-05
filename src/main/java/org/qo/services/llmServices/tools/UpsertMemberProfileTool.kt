package org.qo.services.llmServices.tools

import com.google.gson.JsonObject
import org.qo.services.llmServices.LLMGroupChatPolicy
import org.qo.services.llmServices.LLMMemberProfileService
import org.qo.services.llmServices.LLMToolContext
import org.springframework.stereotype.Component

@Component
class UpsertMemberProfileTool(
	private val profileService: LLMMemberProfileService,
) : Tools {
	override val id = "upsert_member_profile"
	override val definition = ToolSupport.functionTool(
		name = id,
		description = "为当前提问者的 QQ uid 新增或更新一个画像字段。在自然对话中当用户提及自身稳定身份、称呼偏好、兴趣爱好、习惯、技术栈或纠正先前的个人偏好时可主动调用。仅允许保存当前提问者本人明确表达的事实；禁止保存他人画像，禁止保存推测内容，禁止保存密码、Token、证件号等敏感信息。",
		properties = linkedMapOf(
			"qq_uid" to ToolSupport.property(type = "string", description = "画像所属 QQ 号。必须等于当前提问者 uid。"),
			"field_key" to ToolSupport.property(type = "string", description = "属性键，例如 preferred_name、favorite_game、response_style、summary 或 group_nickname。"),
			"value" to ToolSupport.property(type = "string", description = "属性值。必须来自用户明确表达，不得添加推测。"),
			"scope" to ToolSupport.property(type = "string", description = "global 或 group。群昵称必须使用 group；其他字段默认 global。"),
		),
		required = listOf("qq_uid", "field_key", "value"),
	)

	override suspend fun execute(args: JsonObject, context: LLMToolContext): String {
		val currentUid = context.uid?.toLongOrNull()
			?: return ToolSupport.errorResult("missing_uid", "缺少当前提问者 QQ uid")
		val targetUid = args.stringArgument("qq_uid")?.toLongOrNull()
			?: return ToolSupport.errorResult("bad_arguments", "qq_uid 必须是有效 QQ 号")
		if (targetUid != currentUid) {
			return ToolSupport.errorResult("forbidden_target", "只能更新当前提问者本人的画像")
		}
		val fieldKey = args.stringArgument("field_key").orEmpty()
		val value = args.stringArgument("value").orEmpty()
		if (fieldKey.isBlank() || value.isBlank()) {
			return ToolSupport.errorResult("bad_arguments", "field_key 和 value 不能为空")
		}
		if (isSensitive(fieldKey, value)) {
			return ToolSupport.errorResult("sensitive_information_rejected", "禁止保存密码、密钥、身份证号或银行卡号等敏感信息")
		}
		val category = LLMGroupChatPolicy.EXPLICIT_USER_PROFILE_CATEGORY
		val requestedScope = args.stringArgument("scope")?.lowercase()
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
		return ToolSupport.result(id) {
			addProperty("saved", true)
			addProperty("created", mutation.created)
			addProperty("changed", mutation.changed)
			addProperty("qq_uid", targetUid)
			addProperty("profile_id", mutation.profile.profileId)
			add("field", ToolSupport.gson.toJsonTree(mutation.field))
		}
	}

	private fun isSensitive(fieldKey: String, value: String): Boolean {
		val keyLower = fieldKey.lowercase()
		if (listOf("password", "passwd", "token", "secret", "auth", "credential", "id_card", "idcard", "id_number", "idnumber").any { keyLower.contains(it) }) {
			return true
		}
		val valLower = value.lowercase()
		if (Regex("""\b(password|token|bearer|secret)\s*[:=]\s*\S+""", RegexOption.IGNORE_CASE).containsMatchIn(valLower)) {
			return true
		}
		if (Regex("""\b\d{17}[\dXx]\b""").containsMatchIn(value)) {
			return true
		}
		if (Regex("""\b\d{16,19}\b""").containsMatchIn(value)) {
			return true
		}
		return false
	}
}
