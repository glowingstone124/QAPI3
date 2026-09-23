package org.qo.services.llmServices

import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.google.gson.JsonPrimitive
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.preparePost
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsChannel
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import io.ktor.utils.io.readUTF8Line
import jakarta.annotation.PostConstruct
import jakarta.annotation.PreDestroy
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import org.qo.datas.Mapping
import org.qo.datas.Nodes
import org.qo.datas.ReactiveDatabase
import org.qo.orm.UserORM
import org.qo.redis.DatabaseType
import org.qo.redis.Redis
import org.qo.services.loginService.AuthorityNeededServicesImpl
import org.qo.services.loginService.Login
import org.qo.services.loginService.QqLoginService
import org.qo.services.messageServices.Message
import org.qo.services.messageServices.Msg
import org.springframework.boot.context.event.ApplicationReadyEvent
import org.springframework.context.event.EventListener
import org.springframework.stereotype.Service
import java.net.URLDecoder
import java.nio.file.Path
import java.nio.charset.StandardCharsets
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

@Service
class LLMServices(
	internal val authorityNeededServicesImpl: AuthorityNeededServicesImpl,
	internal val login: Login,
	internal val nodes: Nodes,
	internal val database: ReactiveDatabase,
	internal val accessRecordSchema: LLMAccessRecordSchema,
	internal val ragService: RAGService,
	internal val memoryService: LLMMemoryService,
	internal val conversationService: LLMConversationService,
	internal val groupContextService: LLMGroupContextService,
	internal val memberProfileContextService: LLMMemberProfileContextService,
	internal val memberProfileService: LLMMemberProfileService,
	internal val chatHistoryService: LLMChatHistoryService,
	internal val toolService: LLMToolService,
	internal val providers: ReloadableLLMProvider,
	internal val dailyQuotaService: LLMDailyQuotaService,
	internal val kotshiConversationService: KotshiConversationService,
	internal val tokenStatisticsService: LLMTokenStatisticsService? = null,
	internal val accessRecordRepository: org.qo.db.repository.LlmAccessRecordDbRepository = org.qo.db.repository.LlmAccessRecordDbRepository(database),
) {
	internal val redis = Redis()
	internal val debugPrompt = readBoolean("LLM_DEBUG_PROMPT", false)
	internal val debugPromptMaxChars = readInt("LLM_DEBUG_PROMPT_MAX_CHARS", 12000).coerceAtLeast(1000)
	internal val maxToolRounds = readInt("LLM_TOOL_MAX_ROUNDS", 3).coerceIn(1, 8)
	internal val groupSummaryTimeoutMs = readLong("LLM_GROUP_SUMMARY_TIMEOUT_MS", 30_000L).coerceIn(1000L, 30_000L)
	internal val sanitizeOutput = readBoolean("LLM_SANITIZE_OUTPUT", true)
	internal val stripEmoji = System.getenv("LLM_STRIP_EMOJI")?.trim()?.lowercase()?.toBooleanStrictOrNull() == true
	internal val qoGroupId = System.getenv("LLM_QO_GROUP_ID")?.trim()?.toLongOrNull()
	internal val blockedQqUids = readLongSet("LLM_BLOCKED_QQ_UIDS")
	internal val ultraBriefQqUids = readLongSet("LLM_ULTRA_BRIEF_QQ_UIDS")
	internal val systemPrompt = ReloadableSystemPrompt(
		inlinePrompt = System.getenv("LLM_SYSTEM_PROMPT"),
		promptFile = System.getenv("LLM_SYSTEM_PROMPT_FILE")?.trim()?.takeIf { it.isNotBlank() }?.let(Path::of),
		fallbackPrompt = "",
	)
	enum class MODELS(val alias: String, val apiName: String) {
		FAST("fast", "deepseek-v4-flash"),
		THINKING("thinking", "deepseek-v4-pro");

		companion object {
			fun fromRequest(value: String): MODELS? = entries.find {
				it.name.equals(value, ignoreCase = true) ||
						it.alias.equals(value, ignoreCase = true) ||
						it.apiName == value
			}
		}
	}

	fun modelFromRequest(value: String): MODELS? {
		return MODELS.fromRequest(value) ?: MODELS.entries.firstOrNull {
			providers.current().modelName(it) == value
		}
	}

	fun modelPresetFromRequest(value: String): String? = value.trim().lowercase()
		.takeIf { it in setOf("fast", "thinking") }

	internal val client = HttpClient(CIO) {
		install(HttpTimeout) {
			requestTimeoutMillis = 120 * 1000
			socketTimeoutMillis = 120 * 1000
			connectTimeoutMillis = 10 * 1000
		}
	}
	internal val userORM = UserORM()
	internal val initializationScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
	internal val accessRecordSchemaReady = CompletableDeferred<Unit>()
	internal val accessRecordSchemaInitializationStarted = AtomicBoolean(false)

	@PostConstruct
	fun init() {
		systemPrompt.start()
		providers.start()
	}

	@EventListener(ApplicationReadyEvent::class)
	fun initializeAccessRecordSchema() {
		ensureAccessRecordSchemaInitialization()
	}

	@PreDestroy
	fun shutdown() {
		systemPrompt.close()
		providers.close()
		initializationScope.cancel()
		client.close()
	}

	suspend fun authenticate(token: String): Mapping.Users? {
		val (user, ok) = authorityNeededServicesImpl.internalAuthorityCheck(token)
		if (!ok || user == null || user.frozen == true) {
			return null
		}
		return user
	}

	fun authenticateServerToken(token: String): Boolean = nodes.getServerFromToken(token) >= 0

	suspend fun authenticateWeb(token: String): LLMPrincipal? {
		val (identity, errorCode) = login.validate(token)
		if (errorCode != 0 || identity.isNullOrBlank()) return null
		if (identity.startsWith(QqLoginService.GUEST_PREFIX)) {
			val qqUid = identity.removePrefix(QqLoginService.GUEST_PREFIX).toLongOrNull()?.takeIf { it > 0 }
				?: return null
			val account = userORM.readAsync(qqUid)
			if (account?.frozen == true) return null
			return LLMPrincipal(
				qqUid = qqUid,
				displayName = account?.username ?: "QQ $qqUid",
				source = LLMSource.WEB,
				sourceIdentity = identity,
				hasAccount = account != null,
			)
		}
		val account = userORM.readAsync(identity) ?: return null
		if (account.frozen == true) return null
		return LLMPrincipal(
			qqUid = account.uid,
			displayName = account.username,
			source = LLMSource.WEB,
			sourceIdentity = account.username,
			hasAccount = true,
		)
	}

	suspend fun qqPrincipal(qqUid: Long, qqName: String?): LLMPrincipal? {
		if (qqUid in blockedQqUids) return null
		val user = userORM.readAsync(qqUid)
		val username = qqName?.takeIf { it.isNotBlank() }?.let { decodeHeader(it) } ?: (user?.username ?: "qq:$qqUid")
		return LLMPrincipal(qqUid, username, LLMSource.QQ, qqUid.toString(), hasAccount = user != null)
	}

	suspend fun quotaStatus(token: String): LLMNonStreamResult {
		val principal = authenticateWeb(token)
			?: return LLMNonStreamResult(401, errorJson("invalid_token", "权限验证失败"))
		val quota = dailyQuotaService.snapshot(principal.qqUid, hasAccount = principal.hasAccount)
		if (quota.status == LLMQuotaStatus.UNAVAILABLE) {
			return LLMNonStreamResult(
				503,
				errorJson("quota_unavailable", "额度服务暂时不可用，请稍后重试"),
				quota.view,
			)
		}
		return LLMNonStreamResult(200, quotaJson(quota.view), quota.view)
	}

	fun buildPromptRequest(prompt: String, stream: Boolean = true, model: String): String {
		val provider = providers.current().forMode(model)
		return JsonObject().apply {
			addProperty("model", provider.modelName(model) ?: throw IllegalArgumentException("请求的模型不可用"))
			addProperty("stream", stream)
			add(
				"messages", JsonParser.parseString(
					"""
             [
                {"role":"system","content":"You are a helpful assistant."},
                {"role":"user","content":${quote(prompt)}}
             ]
             """.trimIndent()
				).asJsonArray
			)
		}.toString()
	}

	fun buildPromptRequest(prompt: String, stream: Boolean = true, model: MODELS): String =
		buildPromptRequest(prompt, stream, model.alias)

	suspend fun completeChat(
		body: String,
		token: String,
		model: String,
		clientRequestId: String? = null,
		conversationId: String? = null,
	): LLMNonStreamResult = dispatchCompleteChat(body, token, model, clientRequestId, conversationId)

	suspend fun completeChat(
		body: String,
		token: String,
		model: MODELS,
		clientRequestId: String? = null,
		conversationId: String? = null,
	): LLMNonStreamResult = completeChat(body, token, model.alias, clientRequestId, conversationId)

	suspend fun streamChat(
		body: String,
		token: String,
		model: String,
		clientRequestId: String? = null,
		conversationId: String? = null,
	): LLMStreamResult = dispatchStreamChat(body, token, model, clientRequestId, conversationId)

	suspend fun streamChat(
		body: String,
		token: String,
		model: MODELS,
		clientRequestId: String? = null,
		conversationId: String? = null,
	): LLMStreamResult = streamChat(body, token, model.alias, clientRequestId, conversationId)

	suspend fun completeBotChat(
		body: String,
		token: String,
		qqUid: Long,
		qqGroupId: Long?,
		qqName: String?,
		qqMessageId: Long? = null,
		model: String,
		clientRequestId: String? = null,
		qqGroupName: String? = null,
	): LLMNonStreamResult = dispatchCompleteBotChat(
		body, token, qqUid, qqGroupId, qqName, qqMessageId, model, clientRequestId, qqGroupName,
	)

	suspend fun archiveBotChatHistory(token: String, groupId: Long, body: String): LLMNonStreamResult {
		if (!authenticateServerToken(token)) {
			return LLMNonStreamResult(401, errorJson("invalid_token", "Bot token 验证失败"))
		}
		val count = chatHistoryService.archiveRequest(groupId, body)
		return LLMNonStreamResult(200, JsonObject().apply {
			addProperty("success", true)
			addProperty("archived", count)
		}.toString())
	}

	suspend fun completeMinecraftChat(
		body: String,
		token: String,
		minecraftName: String,
		minecraftCoordinate: String,
		minecraftHP: String,
		model: String,
		clientRequestId: String? = null,
	): LLMNonStreamResult = dispatchCompleteMinecraftChat(
		body, token, minecraftName, minecraftCoordinate, minecraftHP, model, clientRequestId,
	)

	suspend fun completeMinecraftChat(
		body: String, token: String, minecraftName: String, minecraftCoordinate: String, minecraftHP: String, model: MODELS,
		clientRequestId: String? = null,
	): LLMNonStreamResult = completeMinecraftChat(
		body, token, minecraftName, minecraftCoordinate, minecraftHP, model.alias, clientRequestId,
	)


	companion object {
		internal fun hardOutputRules(enableMarkdown: Boolean, isWeb: Boolean = false): String =
			LLMPromptRules.hardOutputRules(enableMarkdown, isWeb)
	}

	internal fun webSearchRules(includeLinks: Boolean = true): String =
		LLMPromptRules.webSearchRules(includeLinks)

	internal fun debugPrompt(source: String, body: String) {
		if (!debugPrompt) {
			return
		}
		val redacted = body.replace(
			Regex("""data:image/[a-zA-Z0-9.+-]+;base64,[a-zA-Z0-9+/=_-]+"""),
			"data:image/<redacted>;base64,<redacted>",
		)
		val clipped = if (redacted.length > debugPromptMaxChars) {
			redacted.take(debugPromptMaxChars) + "\n...<clipped ${redacted.length - debugPromptMaxChars} chars>"
		} else {
			redacted
		}
		println("===== LLM REQUEST BODY [$source] =====")
		println(clipped)
		println("===== END LLM REQUEST BODY [$source] =====")
	}

	internal fun logUpstreamRequest(source: String, body: String, provider: LLMProvider, api: String) {
		val model = runCatching {
			JsonParser.parseString(body).asJsonObject.let { request ->
				request.get("model")?.asString ?: request.getAsJsonObject("params")?.get("model")?.asString
			}
		}.getOrNull() ?: "unknown"
		println("[LLM] upstream request source=$source provider=${provider.name} model=$model api=$api")
	}

	private fun readInt(name: String, defaultValue: Int): Int =
		System.getenv(name)?.trim()?.toIntOrNull() ?: defaultValue

	private fun readLong(name: String, defaultValue: Long): Long =
		System.getenv(name)?.trim()?.toLongOrNull() ?: defaultValue

	private fun readBoolean(name: String, defaultValue: Boolean): Boolean =
		System.getenv(name)?.trim()?.lowercase()?.toBooleanStrictOrNull() ?: defaultValue

	private fun readLongSet(name: String): Set<Long> =
		System.getenv(name)
			?.split(',', '，', ';', '；', ' ')
			?.mapNotNull { it.trim().toLongOrNull() }
			?.toSet()
			.orEmpty()


	internal data class MinecraftRelated(
		val coord: String,
		val hp: String,
	)

	internal data class NormalizedRequest(
		val preset: String,
		val model: String,
		val body: String,
		val userContent: JsonElement,
		val currentUserText: String,
		val enableMarkdown: Boolean,
		val reasoningEffort: LLMReasoningEffort,
		val pricing: LLMModelPricing? = null,
		val clientTools: JsonArray? = null,
	)

	internal data class LLMRequester(
		val uid: Long,
		val name: String,
		val source: String,
		val groupId: Long? = null,
		val groupName: String? = null,
		val messageId: Long? = null,
		val conversationSource: String = source,
		val minecraftRelated: MinecraftRelated? = null,
		val conversationId: String? = null,
		val model: String = "fast",
	) {
		fun identityKey(): String = "qq:$uid"

		fun conversationKey(): String =
			if (conversationSource == "web" && !conversationId.isNullOrBlank()) {
				"web:$uid:${conversationId.trim()}"
			} else {
				listOfNotNull(conversationSource, groupId?.toString(), uid.toString()).joinToString(":")
			}

		fun toolContext(currentMessage: String? = null): LLMToolContext =
			LLMToolContext(groupId, uid.toString(), name, currentMessage, messageId, source, conversationKey())
	}

	internal data class ToolCall(val id: String, val name: String, val arguments: String?)
	internal data class Usage(
		val promptTokens: Int? = null,
		val completionTokens: Int? = null,
		val totalTokens: Int? = null,
		val cacheHitTokens: Int? = null,
		val cacheMissTokens: Int? = null,
		val reasoningTokens: Long? = null,
		val apiCalls: Int = 1,
		val complete: Boolean = true,
	)
}

data class LLMNonStreamResult(
	val status: Int,
	val body: String,
	val quota: LLMQuotaView? = null,
)

data class LLMStreamResult(
	val status: Int,
	val chunks: Flow<String>,
	val quota: LLMQuotaView? = null,
)
