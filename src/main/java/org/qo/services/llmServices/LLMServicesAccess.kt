package org.qo.services.llmServices

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import java.util.UUID

internal suspend fun LLMServices.awaitAccessRecordSchema() {
	ensureAccessRecordSchemaInitialization()
	accessRecordSchemaReady.await()
}

internal fun LLMServices.ensureAccessRecordSchemaInitialization() {
	if (!accessRecordSchemaInitializationStarted.compareAndSet(false, true)) {
		return
	}
	initializationScope.launch {
		try {
			accessRecordSchema.ensure()
			accessRecordSchemaReady.complete(Unit)
		} catch (error: Exception) {
			accessRecordSchemaReady.completeExceptionally(error)
			println("LLM access record table init failed: ${error.message}; cause=${accessRecordSchemaErrorDetail(error)}")
		}
	}
}

internal suspend fun LLMServices.insertAccessRecord(
	principal: LLMPrincipal,
	model: String,
	stream: Boolean,
	groupName: String? = null,
): Long {
	val requestId = "chatcmpl-qo-${UUID.randomUUID()}"
	return try {
		awaitAccessRecordSchema()
		accessRecordRepository.insertStartRecord(
			uid = principal.qqUid,
			username = principal.displayName,
			source = principal.source.value,
			sourceIdentity = principal.sourceIdentity,
			groupName = groupName,
			requestId = requestId,
			model = model,
			stream = stream,
			createdAt = System.currentTimeMillis(),
		)
	} catch (_: Exception) {
		-1L
	}
}

internal suspend fun LLMServices.updateAccessRecord(
	id: Long,
	status: String,
	usage: LLMServices.Usage? = null,
	errorMessage: String? = null,
	groupName: String? = null,
	qqUid: Long? = null,
) {
	usage?.let { logPromptCacheUsage("chat", it) }
	val cached = usage?.cacheHitTokens
	val prompt = usage?.promptTokens
	val uncached = usage?.cacheMissTokens ?: prompt?.let { p -> cached?.let { (p - it).coerceAtLeast(0) } }

	if (status == "completed" && usage != null) {
		tokenStatisticsService?.recordUsage(groupName, qqUid ?: -1L, usage)
	}

	if (id <= 0) return
	try {
		awaitAccessRecordSchema()
		accessRecordRepository.updateRecord(
			id = id,
			status = status,
			promptTokens = usage?.promptTokens,
			completionTokens = usage?.completionTokens,
			totalTokens = usage?.totalTokens,
			cachedTokens = cached,
			uncachedTokens = uncached,
			errorMessage = errorMessage,
			completedAt = System.currentTimeMillis(),
		)
	} catch (_: Exception) {
		// Access-record persistence must not replace the upstream response with a database error.
	}
}

internal fun LLMServices.parseUsage(body: String): LLMServices.Usage? = runCatching {
	val obj = JsonParser.parseString(body).asJsonObject
	val usage = obj.getAsJsonObject("usage") ?: return null
	val promptTokens = usage.get("prompt_tokens")?.takeUnless { it.isJsonNull }?.asInt ?: usage.get("input_tokens")?.takeUnless { it.isJsonNull }?.asInt
	val cachedTokens = usage.get("prompt_cache_hit_tokens")?.takeUnless { it.isJsonNull }?.asInt
		?: usage.getAsJsonObject("prompt_tokens_details")?.get("cached_tokens")?.takeUnless { it.isJsonNull }?.asInt
		?: usage.getAsJsonObject("input_tokens_details")?.get("cached_tokens")?.takeUnless { it.isJsonNull }?.asInt
	LLMServices.Usage(
		promptTokens,
		usage.get("completion_tokens")?.takeUnless { it.isJsonNull }?.asInt ?: usage.get("output_tokens")?.takeUnless { it.isJsonNull }?.asInt,
		usage.get("total_tokens")?.takeUnless { it.isJsonNull }?.asInt,
		cachedTokens,
		usage.get("prompt_cache_miss_tokens")?.takeUnless { it.isJsonNull }?.asInt
			?: promptTokens?.let { total -> cachedTokens?.let { (total - it).coerceAtLeast(0) } },
		usage.getAsJsonObject("completion_tokens_details")?.get("reasoning_tokens")?.takeUnless { it.isJsonNull }?.asLong
			?: usage.getAsJsonObject("output_tokens_details")?.get("reasoning_tokens")?.takeUnless { it.isJsonNull }?.asLong,
		usage.get("qapi_api_calls")?.takeUnless { it.isJsonNull }?.asInt ?: 1,
		usage.get("qapi_usage_complete")?.asBoolean ?: true,
	)
}.getOrNull()

internal fun LLMServices.logPromptCacheUsage(source: String, usage: LLMServices.Usage) {
	if (usage.cacheHitTokens != null || usage.cacheMissTokens != null) {
		println("[LLM] prompt cache source=$source hit_tokens=${usage.cacheHitTokens ?: 0} miss_tokens=${usage.cacheMissTokens ?: 0}")
	}
}

internal fun LLMServices.errorJson(code: String, message: String): String {
	return JsonObject().apply {
		add("error", JsonObject().apply {
			addProperty("message", message)
			addProperty("type", code)
			addProperty("code", code)
		})
	}.toString()
}

internal fun LLMServices.quote(value: String): String =
	JsonObject().apply { addProperty("value", value) }.get("value").toString()

internal fun LLMServices.flowOfText(text: String): Flow<String> = flow { emit(text) }
