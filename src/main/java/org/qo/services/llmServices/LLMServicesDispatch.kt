package org.qo.services.llmServices

import com.google.gson.JsonParser
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onCompletion

internal suspend fun LLMServices.dispatchCompleteChat(
	body: String,
	token: String,
	model: String,
	clientRequestId: String? = null,
	conversationId: String? = null,
): LLMNonStreamResult {
	val principal = authenticateWeb(token) ?: return LLMNonStreamResult(401, errorJson("invalid_token", "权限验证失败"))
	val resolvedConvId = conversationId ?: extractConversationId(body)
	val requester = principal.toRequester(conversationId = resolvedConvId, model = model)
	val provider = providers.current().forMode(model)
	if (provider.apiToken.isBlank()) {
		return LLMNonStreamResult(500, errorJson("server_error", "LLM 上游令牌未配置"))
	}
	val request = normalizeRequest(body, false, requester, model, provider)
	val requestId = insertAccessRecord(principal, request.preset, false, requester.groupName)
	val quota = reserveQuota(principal, clientRequestId, request, provider)
	quotaFailure(quota, principal)?.let {
		updateAccessRecord(requestId, "rejected", errorMessage = it.body.take(512))
		return it
	}
	val reservation = requireNotNull(quota.reservation)

	var modelSucceeded = false
	return try {
		val (statusCode, rawText, actualRequest, actualProvider) = completeWithFallback(request, requester, "chat", provider)
		modelSucceeded = statusCode in 200..299
		val usage = parseUsage(rawText)
		val settled = if (modelSucceeded) settleUsage(reservation, usage, actualRequest, actualProvider, requester.conversationId) else null
		val text = if (settled != null) publicModel(attachQuota(rawText, settled), request.preset) else rawText
		updateAccessRecord(
			requestId,
			if (statusCode in 200..299) "completed" else "failed",
			usage,
			text.take(512),
			groupName = requester.groupName,
			qqUid = requester.uid,
		)
		if (statusCode in 200..299) {
			if (request.clientTools == null || extractToolCalls(rawText).isEmpty()) {
				recordConversation(requester, request.userContent, text, actualProvider)
			}
		} else {
			refundUsage(reservation, usage, actualRequest, actualProvider, requester.conversationId)
		}
		LLMNonStreamResult(statusCode, text, settled ?: quota.view)
	} catch (e: Exception) {
		LLMErrorLog.record("chat/complete", e, provider.name, requester, requestId)
		if (!modelSucceeded) kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable) { dailyQuotaService.refund(reservation) }
		updateAccessRecord(
			requestId,
			"failed",
			errorMessage = e.message,
			groupName = requester.groupName,
			qqUid = requester.uid,
		)
		LLMNonStreamResult(502, errorJson("upstream_error", e.message ?: "LLM 上游请求失败"), quota.view)
	}
}

internal suspend fun LLMServices.dispatchStreamChat(
	body: String,
	token: String,
	model: String,
	clientRequestId: String? = null,
	conversationId: String? = null,
): LLMStreamResult {
	val principal = authenticateWeb(token)
		?: return LLMStreamResult(401, flowOfText(errorJson("invalid_token", "权限验证失败")))
	val resolvedConvId = conversationId ?: extractConversationId(body)
	val requester = principal.toRequester(conversationId = resolvedConvId, model = model)
	val provider = providers.current().forMode(model)
	if (provider.apiToken.isBlank()) {
		return LLMStreamResult(500, flowOfText(errorJson("server_error", "LLM 上游令牌未配置")))
	}
	val request = normalizeRequest(body, true, requester, model, provider)
	val requestId = insertAccessRecord(principal, request.preset, true, requester.groupName)
	val quota = reserveQuota(principal, clientRequestId, request, provider)
	quotaStreamFailure(quota, principal)?.let {
		updateAccessRecord(requestId, "rejected", errorMessage = quotaErrorMessage(quota.status, principal))
		return it
	}
	val reservation = requireNotNull(quota.reservation)

	val chunks = if (request.clientTools != null) streamClientTools(request, requester, requestId, provider, reservation) else when (provider.protocol(request.preset)) {
		LLMProtocol.RESPONSES -> streamFromResponses(request, requester, requestId, "stream", provider, reservation)
		LLMProtocol.ANTHROPIC -> streamFromAnthropic(request, requester, requestId, "stream", provider, reservation)
		LLMProtocol.CHAT_COMPLETIONS -> streamFromUpstream(request, requester, requestId, "stream", provider, reservation)
		LLMProtocol.COMMANDCODE -> streamFromCommandCode(request, requester, requestId, "stream", provider, reservation)
	}
	return LLMStreamResult(
		200,
		chunks.map { publicModel(it, request.preset) }.onCompletion { kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable) { dailyQuotaService.refund(reservation) } },
		quota.view,
	)
}

internal suspend fun LLMServices.dispatchCompleteBotChat(
	body: String,
	token: String,
	qqUid: Long,
	qqGroupId: Long?,
	qqName: String?,
	qqMessageId: Long? = null,
	model: String,
	clientRequestId: String? = null,
	qqGroupName: String? = null,
): LLMNonStreamResult {
	if (!authenticateServerToken(token)) {
		return LLMNonStreamResult(401, errorJson("invalid_token", "Bot token 验证失败"))
	}
	val principal = qqPrincipal(qqUid, qqName)
		?: return LLMNonStreamResult(403, errorJson("blocked_user", "该用户暂时不能使用此功能"))
	val decodedGroupName = qqGroupName?.takeIf { it.isNotBlank() }?.let { decodeHeader(it) }
		?: extractGroupNameFromBody(body)
		?: qqGroupId?.let { "group:$it" }
	val requester = principal.toRequester(groupId = qqGroupId, groupName = decodedGroupName, messageId = qqMessageId)
	val provider = providers.current().forMode(model)
	val modelPreset = modelPresetFromRequest(model)
		?: return LLMNonStreamResult(400, errorJson("model_not_available", "请求的模型不可用"))
	if (provider.apiToken.isBlank()) {
		return LLMNonStreamResult(500, errorJson("server_error", "LLM 上游令牌未配置"))
	}
	val request = normalizeRequest(body, false, requester, modelPreset, provider)
	val requestId = insertAccessRecord(principal, request.preset, false, requester.groupName)
	val quota = reserveQuota(principal, clientRequestId, request, provider)
	quotaFailure(quota, principal)?.let {
		updateAccessRecord(requestId, "rejected", errorMessage = it.body.take(512))
		return it
	}
	val reservation = requireNotNull(quota.reservation)

	var modelSucceeded = false
	return try {
		val (statusCode, rawText, actualRequest, actualProvider) = completeWithFallback(request, requester, "bot", provider)
		modelSucceeded = statusCode in 200..299
		val usage = parseUsage(rawText)
		val settled = if (modelSucceeded) settleUsage(reservation, usage, actualRequest, actualProvider, requester.conversationId) else null
		val text = if (settled != null) publicModel(attachQuota(rawText, settled), request.preset) else rawText
		updateAccessRecord(
			requestId,
			if (statusCode in 200..299) "completed" else "failed",
			usage,
			text.take(512),
			groupName = requester.groupName,
			qqUid = requester.uid,
		)
		if (statusCode in 200..299) {
			recordConversation(requester, request.userContent, text, actualProvider)
		} else {
			refundUsage(reservation, usage, actualRequest, actualProvider, requester.conversationId)
		}
		LLMNonStreamResult(statusCode, text, settled ?: quota.view)
	} catch (e: Exception) {
		LLMErrorLog.record("bot/complete", e, provider.name, requester, requestId)
		if (!modelSucceeded) kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable) { dailyQuotaService.refund(reservation) }
		updateAccessRecord(
			requestId,
			"failed",
			errorMessage = e.message,
			groupName = requester.groupName,
			qqUid = requester.uid,
		)
		LLMNonStreamResult(502, errorJson("upstream_error", e.message ?: "LLM 上游请求失败"), quota.view)
	}
}

internal suspend fun LLMServices.dispatchCompleteMinecraftChat(
	body: String,
	token: String,
	minecraftName: String,
	minecraftCoordinate: String,
	minecraftHP: String,
	model: String,
	clientRequestId: String? = null,
): LLMNonStreamResult {
	val serverId = authenticatedServerId(token)
		?: return LLMNonStreamResult(401, errorJson("invalid_token", "Minecraft token 验证失败"))
	val playerName = minecraftName.trim()
	if (playerName.isBlank()) {
		return LLMNonStreamResult(400, errorJson("bad_request", "缺少 Minecraft 玩家名"))
	}
	val user = userORM.readAsync(playerName)
		?: return LLMNonStreamResult(404, errorJson("user_not_found", "玩家未绑定 QO/QQ 账号"))
	if (user.frozen == true) {
		return LLMNonStreamResult(403, errorJson("account_frozen", "账号已被冻结"))
	}

	val groupId = minecraftGroupId(serverId)
	val serverGroupName = groupId?.let { "group:$it" } ?: "minecraft:$serverId"
	val principal = LLMPrincipal(
		user.uid,
		"$playerName/qq:${user.uid}",
		LLMSource.MINECRAFT,
		playerName,
		hasAccount = true,
	)
	val requester = principal.toRequester(
		groupId = groupId,
		groupName = serverGroupName,
		conversationSource = groupId?.let { LLMSource.QQ.value } ?: LLMSource.MINECRAFT.value,
		minecraftRelated = LLMServices.MinecraftRelated(
			minecraftCoordinate,
			minecraftHP,
		)
	)
	val provider = providers.current().forMode(model)
	if (provider.apiToken.isBlank()) {
		return LLMNonStreamResult(500, errorJson("server_error", "LLM 上游令牌未配置"))
	}
	val request = normalizeRequest(body, false, requester, model, provider)
	val requestId = insertAccessRecord(principal, request.preset, false, requester.groupName)
	val quota = reserveQuota(principal, clientRequestId, request, provider)
	quotaFailure(quota, principal)?.let {
		updateAccessRecord(requestId, "rejected", errorMessage = it.body.take(512))
		return it
	}
	val reservation = requireNotNull(quota.reservation)

	var modelSucceeded = false
	return try {
		val (statusCode, rawText, actualRequest, actualProvider) = completeWithFallback(request, requester, "minecraft", provider)
		modelSucceeded = statusCode in 200..299
		val usage = parseUsage(rawText)
		val settled = if (modelSucceeded) settleUsage(reservation, usage, actualRequest, actualProvider, requester.conversationId) else null
		val text = if (settled != null) publicModel(attachQuota(rawText, settled), request.preset) else rawText
		updateAccessRecord(
			requestId,
			if (statusCode in 200..299) "completed" else "failed",
			usage,
			text.take(512),
			groupName = requester.groupName,
			qqUid = requester.uid,
		)
		if (statusCode in 200..299) {
			recordConversation(requester, request.userContent, text, actualProvider)
		} else {
			refundUsage(reservation, usage, actualRequest, actualProvider, requester.conversationId)
		}
		LLMNonStreamResult(statusCode, text, settled ?: quota.view)
	} catch (e: Exception) {
		LLMErrorLog.record("minecraft/complete", e, provider.name, requester, requestId)
		if (!modelSucceeded) kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable) { dailyQuotaService.refund(reservation) }
		updateAccessRecord(
			requestId,
			"failed",
			errorMessage = e.message,
			groupName = requester.groupName,
			qqUid = requester.uid,
		)
		LLMNonStreamResult(502, errorJson("upstream_error", e.message ?: "LLM 上游请求失败"), quota.view)
	}
}

internal fun LLMServices.extractGroupNameFromBody(body: String): String? = runCatching {
	val obj = JsonParser.parseString(body).asJsonObject
	(obj.get("group_name") ?: obj.get("groupName"))?.takeIf { !it.isJsonNull }?.asString?.takeIf { it.isNotBlank() }
}.getOrNull()

internal fun LLMPrincipal.toRequester(
	groupId: Long? = null,
	groupName: String? = null,
	messageId: Long? = null,
	conversationSource: String = source.value,
	minecraftRelated: LLMServices.MinecraftRelated? = null,
	conversationId: String? = null,
	model: String = "fast",
): LLMServices.LLMRequester = LLMServices.LLMRequester(
	uid = qqUid,
	name = displayName,
	source = source.value,
	groupId = groupId,
	groupName = groupName,
	messageId = messageId,
	conversationSource = conversationSource,
	minecraftRelated = minecraftRelated,
	conversationId = conversationId,
	model = model,
)
