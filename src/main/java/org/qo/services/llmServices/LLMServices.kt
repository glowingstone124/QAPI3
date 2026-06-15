package org.qo.services.llmServices

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsChannel
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import io.ktor.utils.io.jvm.javaio.toInputStream
import jakarta.annotation.PostConstruct
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.withContext
import org.qo.datas.ConnectionPool
import org.qo.datas.Mapping
import org.qo.datas.Nodes
import org.qo.orm.UserORM
import org.qo.redis.DatabaseType
import org.qo.redis.Redis
import org.qo.services.loginService.AuthorityNeededServicesImpl
import org.qo.services.messageServices.Message
import org.qo.services.messageServices.Msg
import org.springframework.stereotype.Service
import java.net.URLDecoder
import java.nio.file.Files
import java.nio.file.Path
import java.nio.charset.StandardCharsets
import java.util.UUID

@Service
class LLMServices(
	private val authorityNeededServicesImpl: AuthorityNeededServicesImpl,
	private val nodes: Nodes,
	private val ragService: RAGService,
	private val conversationService: LLMConversationService,
	private val toolService: LLMToolService,
) {
	private val redis = Redis()
	private val jsonParser = JsonParser()
	private val upstreamUrl = System.getenv("LLM_API_URL") ?: "https://api.deepseek.com/v1/chat/completions"
	private val upstreamModel = System.getenv("LLM_DEFAULT_MODEL") ?: "deepseek-chat"
	private val debugPrompt = readBoolean("LLM_DEBUG_PROMPT", false)
	private val debugPromptMaxChars = readInt("LLM_DEBUG_PROMPT_MAX_CHARS", 12000).coerceAtLeast(1000)
	private val maxToolRounds = readInt("LLM_TOOL_MAX_ROUNDS", 3).coerceIn(1, 8)
	private val sanitizeOutput = readBoolean("LLM_SANITIZE_OUTPUT", true)
	private val groupContextMaxChars = readInt("LLM_GROUP_CONTEXT_MAX_CHARS", 120000).coerceIn(0, 1_000_000)
	private val defaultSystemPrompt by lazy { loadSystemPrompt() }
	private val upstreamToken by lazy {
		System.getenv("LLM_API_TOKEN")
			?: runCatching { Files.readString(Path.of("LLMAPITOKEN")).trim() }.getOrDefault("")
	}
	private val client = HttpClient(CIO) {
		install(HttpTimeout) {
			requestTimeoutMillis = 120 * 1000
			socketTimeoutMillis = 120 * 1000
			connectTimeoutMillis = 10 * 1000
		}
	}
	private val userORM = UserORM()

	@PostConstruct
	fun init() {
		runCatching {
			ConnectionPool.getConnection().use { conn ->
				conn.createStatement().use { stmt ->
					stmt.executeUpdate(
						"""
						CREATE TABLE IF NOT EXISTS llm_access_records (
							id BIGINT AUTO_INCREMENT PRIMARY KEY,
							uid BIGINT NOT NULL,
							username VARCHAR(128) NOT NULL,
							request_id VARCHAR(80) NOT NULL,
							model VARCHAR(128) NOT NULL,
							stream BOOLEAN NOT NULL,
							status VARCHAR(32) NOT NULL,
							prompt_tokens INT NULL,
							completion_tokens INT NULL,
							total_tokens INT NULL,
							error_message VARCHAR(512) NULL,
							created_at BIGINT NOT NULL,
							completed_at BIGINT NULL,
							INDEX idx_llm_access_uid_created (uid, created_at)
						)
						""".trimIndent()
					)
				}
			}
		}.onFailure {
			println("LLM access record table init failed: ${it.message}")
		}
	}

	suspend fun authenticate(token: String): Mapping.Users? {
		val (user, ok) = authorityNeededServicesImpl.internalAuthorityCheck(token)
		if (!ok || user == null || user.frozen == true) {
			return null
		}
		return user
	}

	fun buildPromptRequest(prompt: String, stream: Boolean = true): String {
		return JsonObject().apply {
			addProperty("model", upstreamModel)
			addProperty("stream", stream)
			add("messages", jsonParser.parse(
				"""
				[
					{"role":"system","content":"You are a helpful assistant."},
					{"role":"user","content":${quote(prompt)}}
				]
				""".trimIndent()
			).asJsonArray)
		}.toString()
	}

	suspend fun completeChat(body: String, token: String): LLMNonStreamResult {
		val user = authenticate(token) ?: return LLMNonStreamResult(401, errorJson("invalid_token", "权限验证失败"))
		val requester = LLMRequester(user.uid, user.username, "login")
		val request = normalizeRequest(body, false, requester)
		val requestId = insertAccessRecord(user.uid, user.username, request.model, false)
		if (!reserveRequest(token)) {
			updateAccessRecord(requestId, "rejected", errorMessage = "duplicate request")
			return LLMNonStreamResult(429, errorJson("rate_limited", "请求过于频繁"))
		}
		if (upstreamToken.isBlank()) {
			updateAccessRecord(requestId, "failed", errorMessage = "missing upstream token")
			return LLMNonStreamResult(500, errorJson("server_error", "LLM 上游令牌未配置"))
		}

		return try {
			val (statusCode, text) = completeWithOptionalTools(request, requester, "chat")
			val usage = parseUsage(text)
			updateAccessRecord(requestId, if (statusCode in 200..299) "completed" else "failed", usage, text.take(512))
			if (statusCode in 200..299) {
				recordConversation(requester, request.userQuestion, text)
			}
			LLMNonStreamResult(statusCode, text)
		} catch (e: Exception) {
			updateAccessRecord(requestId, "failed", errorMessage = e.message)
			LLMNonStreamResult(502, errorJson("upstream_error", e.message ?: "LLM 上游请求失败"))
		}
	}

	suspend fun streamChat(body: String, token: String): LLMStreamResult {
		val user = authenticate(token) ?: return LLMStreamResult(401, flowOfText(errorJson("invalid_token", "权限验证失败")))
		val requester = LLMRequester(user.uid, user.username, "login")
		val request = normalizeRequest(body, true, requester)
		val requestId = insertAccessRecord(user.uid, user.username, request.model, true)
		if (!reserveRequest(token)) {
			updateAccessRecord(requestId, "rejected", errorMessage = "duplicate request")
			return LLMStreamResult(429, flowOfText(errorJson("rate_limited", "请求过于频繁")))
		}
		if (upstreamToken.isBlank()) {
			updateAccessRecord(requestId, "failed", errorMessage = "missing upstream token")
			return LLMStreamResult(500, flowOfText(errorJson("server_error", "LLM 上游令牌未配置")))
		}

		return LLMStreamResult(200, streamFromUpstream(request.body, requestId, "stream"))
	}

	suspend fun completeBotChat(body: String, token: String, qqUid: Long, qqGroupId: Long?, qqName: String?): LLMNonStreamResult {
		if (!authenticateServerToken(token)) {
			return LLMNonStreamResult(401, errorJson("invalid_token", "Bot token 验证失败"))
		}
		val username = qqName?.takeIf { it.isNotBlank() }?.let { decodeHeader(it) } ?: "qq:$qqUid"
		val requester = LLMRequester(qqUid, username, "qq", qqGroupId)
		val request = normalizeRequest(body, false, requester)
		val requestId = insertAccessRecord(qqUid, username, request.model, false)
		if (!reserveRequest("bot:$qqUid")) {
			updateAccessRecord(requestId, "rejected", errorMessage = "duplicate request")
			return LLMNonStreamResult(429, errorJson("rate_limited", "请求过于频繁"))
		}
		if (upstreamToken.isBlank()) {
			updateAccessRecord(requestId, "failed", errorMessage = "missing upstream token")
			return LLMNonStreamResult(500, errorJson("server_error", "LLM 上游令牌未配置"))
		}

		return try {
			val (statusCode, text) = completeWithOptionalTools(request, requester, "bot")
			val usage = parseUsage(text)
			updateAccessRecord(requestId, if (statusCode in 200..299) "completed" else "failed", usage, text.take(512))
			if (statusCode in 200..299) {
				recordConversation(requester, request.userQuestion, text)
			}
			LLMNonStreamResult(statusCode, text)
		} catch (e: Exception) {
			updateAccessRecord(requestId, "failed", errorMessage = e.message)
			LLMNonStreamResult(502, errorJson("upstream_error", e.message ?: "LLM 上游请求失败"))
		}
	}

	suspend fun completeMinecraftChat(body: String, token: String, minecraftName: String): LLMNonStreamResult {
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
		val requester = LLMRequester(
			user.uid,
			"$playerName/qq:${user.uid}",
			"minecraft",
			groupId,
			conversationSource = groupId?.let { "qq" } ?: "minecraft"
		)
		val request = normalizeRequest(body, false, requester)
		val requestId = insertAccessRecord(user.uid, playerName, request.model, false)
		if (!reserveRequest("minecraft:$playerName")) {
			updateAccessRecord(requestId, "rejected", errorMessage = "duplicate request")
			return LLMNonStreamResult(429, errorJson("rate_limited", "请求过于频繁"))
		}
		if (upstreamToken.isBlank()) {
			updateAccessRecord(requestId, "failed", errorMessage = "missing upstream token")
			return LLMNonStreamResult(500, errorJson("server_error", "LLM 上游令牌未配置"))
		}

		return try {
			val (statusCode, text) = completeWithOptionalTools(request, requester, "minecraft")
			val usage = parseUsage(text)
			updateAccessRecord(requestId, if (statusCode in 200..299) "completed" else "failed", usage, text.take(512))
			if (statusCode in 200..299) {
				recordConversation(requester, request.userQuestion, text)
			}
			LLMNonStreamResult(statusCode, text)
		} catch (e: Exception) {
			updateAccessRecord(requestId, "failed", errorMessage = e.message)
			LLMNonStreamResult(502, errorJson("upstream_error", e.message ?: "LLM 上游请求失败"))
		}
	}

	private suspend fun completeWithOptionalTools(
		request: NormalizedRequest,
		requester: LLMRequester,
		source: String,
	): Pair<Int, String> {
		if (!toolService.enabled()) {
			val response = postUpstream(source, request.body)
			return response.status.value to sanitizeResponseBody(response.bodyAsText())
		}

		val obj = jsonParser.parse(request.body).asJsonObject
		obj.add("tools", toolService.definitions())
		if (!obj.has("tool_choice")) {
			obj.addProperty("tool_choice", "auto")
		}

		var latestStatus = 502
		var latestBody = ""
		repeat(maxToolRounds) { round ->
			val body = obj.toString()
			val response = postUpstream("$source/tool-round-${round + 1}", body)
			latestStatus = response.status.value
			latestBody = response.bodyAsText()
			if (!response.status.isSuccess()) {
				return latestStatus to latestBody
			}
			val toolCalls = extractToolCalls(latestBody)
			if (toolCalls.isEmpty()) {
				if (containsToolMarkup(latestBody)) {
					return 502 to errorJson("invalid_tool_call", "LLM 输出了无法解析的工具调用")
				}
				return latestStatus to sanitizeResponseBody(latestBody)
			}
			appendAssistantToolCallMessage(obj.getAsJsonArray("messages"), latestBody, toolCalls)
			toolCalls.forEach { call ->
				obj.getAsJsonArray("messages").add(JsonObject().apply {
					addProperty("role", "tool")
					addProperty("tool_call_id", call.id)
					addProperty("name", call.name)
					addProperty("content", toolService.execute(call.name, call.arguments, requester.groupId))
				})
			}
		}
		return 502 to errorJson("tool_round_limit", "工具调用轮数超过限制，请调高 LLM_TOOL_MAX_ROUNDS")
	}

	private suspend fun postUpstream(source: String, body: String) = client.post(upstreamUrl) {
		header(HttpHeaders.Authorization, "Bearer $upstreamToken")
		contentType(ContentType.Application.Json)
		debugPrompt(source, body)
		setBody(body)
	}

	private fun authenticateServerToken(token: String): Boolean = nodes.getServerFromToken(token) >= 0
	private fun authenticatedServerId(token: String): Int? = nodes.getServerFromToken(token).takeIf { it >= 0 }
	private fun decodeHeader(value: String): String = runCatching {
		URLDecoder.decode(value, StandardCharsets.UTF_8)
	}.getOrDefault(value)

	private fun streamFromUpstream(body: String, requestId: Long, source: String): Flow<String> = flow {
		try {
			val response = client.post(upstreamUrl) {
				header(HttpHeaders.Authorization, "Bearer $upstreamToken")
				contentType(ContentType.Application.Json)
				debugPrompt(source, body)
				setBody(body)
			}
			if (!response.status.isSuccess()) {
				val errorBody = response.bodyAsText()
				updateAccessRecord(requestId, "failed", errorMessage = errorBody.take(512))
				emit(errorJson("upstream_error", errorBody.take(256)))
				return@flow
			}

			var latestUsage: Usage? = null
			response.bodyAsChannel().toInputStream().bufferedReader().use { reader ->
				while (true) {
					val line = reader.readLine() ?: break
					if (!line.startsWith("data:")) continue
					val data = line.removePrefix("data:").trim()
					if (data.isBlank()) continue
					if (data != "[DONE]") {
						parseUsage(data)?.let { latestUsage = it }
					}
					emit(data)
				}
			}
			updateAccessRecord(requestId, "completed", latestUsage)
		} catch (e: Exception) {
			updateAccessRecord(requestId, "failed", errorMessage = e.message)
			emit(errorJson("upstream_error", e.message ?: "LLM 上游请求失败"))
		}
	}

	private fun normalizeRequest(body: String, stream: Boolean, requester: LLMRequester? = null): NormalizedRequest {
		val obj = jsonParser.parse(body).asJsonObject
		if (!obj.has("model") || obj.get("model").asString.isBlank()) {
			obj.addProperty("model", upstreamModel)
		}
		requester?.let {
			obj.addProperty("user_id", it.conversationKey())
		}
		obj.addProperty("stream", stream)
		if (!obj.has("messages") || !obj.get("messages").isJsonArray) {
			throw IllegalArgumentException("OpenAI chat completions request must contain messages array")
		}
		val groupContext = obj.getAsJsonArray("group_context")
		obj.remove("group_context")
		val effectiveGroupContext = groupContext ?: requester
			?.takeIf { it.source == "minecraft" }
			?.let { buildSyncedChatContext() }
		obj.add("messages", enrichMessages(obj.getAsJsonArray("messages"), requester, effectiveGroupContext))
		return NormalizedRequest(obj.get("model").asString, obj.toString(), latestUserQuestion(obj.getAsJsonArray("messages")))
	}

	private fun enrichMessages(messages: JsonArray, requester: LLMRequester?, groupContext: JsonArray?): JsonArray {
		val enriched = JsonArray()
		val userQuestion = latestUserQuestion(messages)
		val contextParts = mutableListOf<String>()
		contextParts.add(defaultSystemPrompt)
		requester?.let {
			contextParts.add(
				"""
				当前提问用户：
				- 来源：${it.source}
				- 群：${it.groupId ?: "未指定"}
				- uid：${it.uid}
				- 昵称/用户名：${it.name}
				- 如果来源是 minecraft，uid 是该玩家绑定的 QQ 号，昵称/用户名形如 Minecraft用户名/qq:QQ号。
				""".trimIndent()
			)
		}
		ragService.buildContext(userQuestion, requester?.groupId)?.let {
			contextParts.add(it)
		}
		buildGroupContext(groupContext)?.let {
			contextParts.add(it)
		}
		contextParts.add(hardOutputRules())
		enriched.add(JsonObject().apply {
			addProperty("role", "system")
			addProperty("content", contextParts.joinToString("\n\n"))
		})
		requester?.let {
			conversationService.historyMessages(it.conversationKey()).forEach { message ->
				enriched.add(message)
			}
		}
		messages.forEach { message ->
			val role = message.takeIf { it.isJsonObject }?.asJsonObject?.get("role")?.asString
			if (role != "system" && role != "developer") {
				enriched.add(message)
			}
		}
		return enriched
	}

	private fun buildGroupContext(groupContext: JsonArray?): String? {
		if (groupContext == null || groupContext.size() == 0 || groupContextMaxChars <= 0) {
			return null
		}
		val lines = mutableListOf<String>()
		var used = 0
		for (item in groupContext) {
			val obj = item.takeIf { it.isJsonObject }?.asJsonObject ?: continue
			val uid = obj.get("uid")?.takeIf { !it.isJsonNull }?.asString ?: "unknown"
			val name = obj.get("name")?.takeIf { !it.isJsonNull }?.asString ?: "qq:$uid"
			val content = obj.get("content")?.takeIf { !it.isJsonNull }?.asString?.trim().orEmpty()
			if (content.isBlank()) {
				continue
			}
			val time = obj.get("time")?.takeIf { !it.isJsonNull }?.asLong
			val line = if (time != null) {
				"[$time] $name($uid): $content"
			} else {
				"$name($uid): $content"
			}
			if (used + line.length > groupContextMaxChars) {
				break
			}
			lines.add(line)
			used += line.length
		}
		if (lines.isEmpty()) {
			return null
		}
		return """
			最近跨平台聊天记录如下，按时间从旧到新排列。它用于理解上下文、省略指代和多人对话；不要把这些记录逐字复述给用户。
			记录格式是：[时间戳] 显示名(身份标识): 内容。
			身份标识说明：
			- QQ/<昵称或QQ号>(QQ号)：来自 QQ 群。
			- Minecraft/<玩家名>(1:<玩家名>)：来自生存服 Minecraft 玩家。
			- Minecraft-Creative/<玩家名>(4:<玩家名>)：来自创造服 Minecraft 玩家。
			- Web/<用户名>(3:<用户名>)：来自网页聊天。
			- System/<来源>(2:<来源>)：来自系统消息。
			这些前缀是来源标记，不是用户实际名字的一部分。回答时可以称呼玩家名或昵称，不要把 “Minecraft/”、“Web/”、“1:” 这类内部标记当作自然语言称呼。
			${lines.joinToString("\n")}
		""".trimIndent()
	}

	private fun buildSyncedChatContext(): JsonArray {
		val context = JsonArray()
		Msg.msgQueue.forEach { message ->
			context.add(JsonObject().apply {
				addProperty("uid", syncedMessageUid(message))
				addProperty("name", syncedMessageName(message))
				addProperty("content", message.message)
				addProperty("time", message.time)
			})
		}
		return context
	}

	private fun syncedMessageUid(message: Message): String =
		if (message.from == 0) message.sender else "${message.from}:${message.sender}"

	private fun syncedMessageName(message: Message): String {
		val sender = message.sender.ifBlank { "unknown" }
		return when (message.from) {
			0 -> "QQ/$sender"
			1 -> "Minecraft/$sender"
			2 -> "System/$sender"
			3 -> "Web/$sender"
			4 -> "Minecraft-Creative/$sender"
			else -> "Synced/$sender"
		}
	}

	private fun minecraftGroupId(serverId: Int): Long? {
		val specific = System.getenv("LLM_MINECRAFT_GROUP_ID_$serverId")
			?.trim()
			?.toLongOrNull()
		if (specific != null) {
			return specific
		}
		return System.getenv("LLM_MINECRAFT_GROUP_ID")
			?.trim()
			?.toLongOrNull()
	}

	private fun latestUserQuestion(messages: JsonArray): String {
		for (index in messages.size() - 1 downTo 0) {
			val message = messages[index].takeIf { it.isJsonObject }?.asJsonObject ?: continue
			if (message.get("role")?.asString == "user") {
				return message.get("content")?.asString.orEmpty()
			}
		}
		return ""
	}

	private fun reserveRequest(token: String): Boolean {
		return redis.setIfAbsentWithExpire("llm:req:$token", "1", DatabaseType.QO_ASSISTANT_DATABASE.value, 2)
			.ignoreException() ?: true
	}

	private fun recordConversation(requester: LLMRequester, userQuestion: String, responseBody: String) {
		val answer = extractAssistantContent(responseBody) ?: return
		conversationService.append(requester.conversationKey(), userQuestion, answer)
	}

	private fun extractToolCalls(responseBody: String): List<ToolCall> = runCatching {
		val root = jsonParser.parse(responseBody).asJsonObject
		val choices = root.getAsJsonArray("choices") ?: return emptyList()
		if (choices.size() == 0) return emptyList()
		val message = choices[0].asJsonObject.getAsJsonObject("message") ?: return emptyList()
		message.getAsJsonArray("tool_calls")?.let { toolCalls ->
			return toolCalls.mapNotNull { item ->
				val call = item.takeIf { it.isJsonObject }?.asJsonObject ?: return@mapNotNull null
				val function = call.getAsJsonObject("function") ?: return@mapNotNull null
				ToolCall(
					id = call.get("id")?.asString ?: "call_${UUID.randomUUID()}",
					name = function.get("name")?.asString ?: return@mapNotNull null,
					arguments = function.get("arguments")?.asString,
				)
			}
		}
		val content = message.get("content")?.takeIf { !it.isJsonNull }?.asString.orEmpty()
		extractDsmlToolCalls(content)
	}.getOrDefault(emptyList())

	private fun extractDsmlToolCalls(content: String): List<ToolCall> {
		if (!content.contains("tool_calls") && !content.contains("invoke name=")) {
			return emptyList()
		}
		val invokeBlocks = Regex("""<[^>\n]*invoke[^>\n]*name=["']([^"']+)["'][^>]*>([\s\S]*?)(?:</[^>]*invoke>|$)""")
			.findAll(content)
			.toList()
		if (invokeBlocks.isNotEmpty()) {
			return invokeBlocks.mapNotNull { invoke ->
				dsmlInvokeToToolCall(invoke.groupValues[1], invoke.groupValues[2])
			}
		}
		val invokeName = Regex("""invoke[^>\n]*name=["']([^"']+)["']""").find(content)?.groupValues?.getOrNull(1)
			?: return emptyList()
		return listOfNotNull(dsmlInvokeToToolCall(invokeName, content))
	}

	private fun dsmlInvokeToToolCall(name: String, body: String): ToolCall? {
		val toolName = name.trim()
		if (toolName.isBlank()) {
			return null
		}
		val args = JsonObject()
		Regex("""<[^>\n]*parameter[^>\n]*name=["']([^"']+)["'][^>]*>([\s\S]*?)(?:</[^>]*parameter>|$)""")
			.findAll(body)
			.forEach { parameter ->
				val parameterName = parameter.groupValues[1].trim()
				val value = parameter.groupValues[2].trim()
				if (parameterName.isNotBlank()) {
					args.addProperty(parameterName, value)
				}
			}
		return ToolCall(
			id = "call_${UUID.randomUUID()}",
			name = toolName,
			arguments = args.toString(),
		)
	}

	private fun appendAssistantToolCallMessage(messages: JsonArray, responseBody: String, parsedToolCalls: List<ToolCall>) {
		runCatching {
			val root = jsonParser.parse(responseBody).asJsonObject
			val choices = root.getAsJsonArray("choices") ?: return
			if (choices.size() == 0) return
			val message = choices[0].asJsonObject.getAsJsonObject("message") ?: return
			messages.add(JsonObject().apply {
				addProperty("role", "assistant")
				if (message.has("content") && !message.get("content").isJsonNull) {
					add("content", message.get("content"))
				} else {
					addProperty("content", "")
				}
				message.getAsJsonArray("tool_calls")?.let {
					add("tool_calls", it.deepCopy())
				} ?: add("tool_calls", JsonArray().apply {
					parsedToolCalls.forEach { call ->
						add(JsonObject().apply {
							addProperty("id", call.id)
							addProperty("type", "function")
							add("function", JsonObject().apply {
								addProperty("name", call.name)
								addProperty("arguments", call.arguments ?: "{}")
							})
						})
					}
				})
			})
		}
	}

	private fun extractAssistantContent(responseBody: String): String? = runCatching {
		val root = jsonParser.parse(responseBody).asJsonObject
		val choices = root.getAsJsonArray("choices") ?: return null
		if (choices.size() == 0) return null
		choices[0].asJsonObject
			.getAsJsonObject("message")
			?.get("content")
			?.asString
			?.trim()
			?.takeIf { it.isNotBlank() }
	}.getOrNull()

	private fun sanitizeResponseBody(responseBody: String): String {
		if (!sanitizeOutput) {
			return responseBody
		}
		return runCatching {
			val root = jsonParser.parse(responseBody).asJsonObject
			val choices = root.getAsJsonArray("choices") ?: return responseBody
			for (choice in choices) {
				val message = choice.takeIf { it.isJsonObject }
					?.asJsonObject
					?.getAsJsonObject("message")
					?: continue
				val content = message.get("content")?.takeIf { !it.isJsonNull }?.asString ?: continue
				message.addProperty("content", sanitizeAssistantText(content))
			}
			root.toString()
		}.getOrDefault(responseBody)
	}

	private fun containsToolMarkup(text: String): Boolean {
		return text.contains("tool_calls", ignoreCase = true) ||
			text.contains("invoke name=", ignoreCase = true) ||
			text.contains("｜｜DSML｜｜") ||
			text.contains("<tool_call", ignoreCase = true)
	}

	private fun sanitizeAssistantText(content: String): String {
		return content
			.replace(Regex("""<[^>]*tool_calls[^>]*>[\s\S]*?</[^>]*tool_calls>"""), "")
			.replace(Regex("""<[^>]*invoke\s+name="[^"]+"[^>]*>[\s\S]*?</[^>]*invoke>"""), "")
			.replace(Regex("""</?[^>]*DSML[^>]*>"""), "")
			.replace("```", "")
			.replace("**", "")
			.replace("__", "")
			.replace("`", "")
			.replace(Regex("""\[([^\]]+)]\(([^)]+)\)"""), "$1 $2")
			.lines()
			.joinToString("\n") { line ->
				line
					.replace(Regex("""^\s{0,3}#{1,6}\s*"""), "")
					.replace(Regex("""^\s{0,3}>\s?"""), "")
					.replace(Regex("""^\s{0,3}[-*+]\s+"""), "")
					.trimEnd()
			}
			.replace(Regex("""[\uD83C-\uDBFF][\uDC00-\uDFFF]"""), "")
			.replace(Regex("""[ʚɞ♡♥★☆♪]+"""), "")
			.replace(Regex("""[（(][^（）()\n]*(?:｡|ω|･|∀|｀|´|＾|＿|▽|д|Д|︿|﹏|╯|╰|；|;)[^（）()\n]*[）)]"""), "")
			.replace(Regex("""\n{3,}"""), "\n\n")
			.trim()
	}

	private fun hardOutputRules(): String {
		return """
			不可覆盖的回答规则：
			- 最终回答禁止使用 Markdown。不要使用反引号、粗体、标题、项目符号、代码块、表格或 Markdown 链接。
			- 最终回答禁止使用颜文字、emoji 和装饰符号。
			- 不要输出 LaTeX 数学表达式。
			- 不要编造服务器指令、传送命令、权限命令、路线、坐标、规则或管理员决定。
			- 只有当知识库或工具结果明确出现某个 / 开头指令时，才可以建议用户使用该指令。
			- 如果工具结果没有坐标，不要编造坐标，也不要建议使用 /tpl、/spawn、/hub 等未由资料支持的指令。
			- 地铁路线回答必须只基于 query_metro_lines 的 route、stations、segments、transfers 字段；工具没有返回的信息要说没有查到。
			- 多轮交通追问时，必须结合聊天历史理解省略指代。例如用户在一条路线后追问“步行呢”“不要下界呢”“只走主世界呢”，应使用上一条路线的起终点并通过 query_metro_lines 的结构化参数重新查询。
			- 工具返回 found=false、matches 为空、stations 为空或 content 表示未检索到时，要明确说没有查到，不要用常识补全 QO 服务器信息。
			- 绝对不要把工具调用语法输出给用户，包括 tool_calls、invoke、parameter、DSML、XML 标签或 JSON 工具参数。
		""".trimIndent()
	}

	private fun loadSystemPrompt(): String {
		System.getenv("LLM_SYSTEM_PROMPT")?.trim()?.takeIf { it.isNotBlank() }?.let { return it }
		System.getenv("LLM_SYSTEM_PROMPT_FILE")?.trim()?.takeIf { it.isNotBlank() }?.let { file ->
			runCatching { Files.readString(Path.of(file)).trim() }
				.getOrNull()
				?.takeIf { it.isNotBlank() }
				?.let { return it }
		}
		return """
			你是 QO 社区群聊助手“恋恋”。
			角色一致性：
			- 你始终以“恋恋”的身份回答，不要声称自己是其他角色。
			- 语气友好、克制，适合 QQ 群聊和 Minecraft 服务器聊天，不刷屏，不主动输出过长段落。
			回答规范：
			- 默认使用中文，回答简洁。
			- 不确定时明确说不确定，不要编造服务器规则、账号状态或管理员决定。
			- 如果用户问“我”“我的账号”“我的权限”等和身份有关的问题，必须结合“当前提问用户”判断。
			- 当前提问用户来源可能是 qq、minecraft 或 login。minecraft 来源的 uid 是该 Minecraft 玩家绑定的 QQ 号，昵称/用户名中会包含 Minecraft 玩家名。
			- 聊天记录可能来自 QQ、Minecraft、Web 和系统。来源前缀只用于区分平台，不要把它们当作玩家真实昵称。
			- 涉及账号、安全、封禁、权限、绕过限制的问题时保持谨慎。
			- 优先根据知识库资料回答；资料没有覆盖时说明缺少依据。
			工具使用：
			- 用户询问服务器当前人数、在线人数、MSPT、服务器状态时，使用 get_server_status。
			- 用户询问地铁线路、站点、区间、坐标时，使用 query_metro_lines。
			- 多轮交通追问时，结合聊天历史补全上一条路线的起终点，并用 query_metro_lines 重新查询。
			- 用户询问 Minecraft、QO 玩法、指令、规则资料时，可以使用 search_minecraft_knowledge。
			- 工具结果是内部资料，回答时直接整理成自然语言，不要暴露原始 JSON。
		""".trimIndent()
	}

	private fun debugPrompt(source: String, body: String) {
		if (!debugPrompt) {
			return
		}
		val clipped = if (body.length > debugPromptMaxChars) {
			body.take(debugPromptMaxChars) + "\n...<clipped ${body.length - debugPromptMaxChars} chars>"
		} else {
			body
		}
		println("===== LLM REQUEST BODY [$source] =====")
		println(clipped)
		println("===== END LLM REQUEST BODY [$source] =====")
	}

	private fun readInt(name: String, defaultValue: Int): Int =
		System.getenv(name)?.trim()?.toIntOrNull() ?: defaultValue

	private fun readBoolean(name: String, defaultValue: Boolean): Boolean =
		when (System.getenv(name)?.trim()?.lowercase()) {
			"1", "true", "yes", "on" -> true
			"0", "false", "no", "off" -> false
			else -> defaultValue
		}

	private suspend fun insertAccessRecord(uid: Long, username: String, model: String, stream: Boolean): Long = withContext(Dispatchers.IO) {
		runCatching {
			ConnectionPool.getConnection().use { conn ->
				conn.prepareStatement(
					"""
					INSERT INTO llm_access_records(uid, username, request_id, model, stream, status, created_at)
					VALUES (?, ?, ?, ?, ?, ?, ?)
					""".trimIndent(),
					java.sql.Statement.RETURN_GENERATED_KEYS
				).use { stmt ->
					stmt.setLong(1, uid)
					stmt.setString(2, username)
					stmt.setString(3, "chatcmpl-qo-${UUID.randomUUID()}")
					stmt.setString(4, model)
					stmt.setBoolean(5, stream)
					stmt.setString(6, "started")
					stmt.setLong(7, System.currentTimeMillis())
					stmt.executeUpdate()
					stmt.generatedKeys.use { keys -> if (keys.next()) keys.getLong(1) else -1L }
				}
			}
		}.getOrDefault(-1L)
	}

	private suspend fun updateAccessRecord(
		id: Long,
		status: String,
		usage: Usage? = null,
		errorMessage: String? = null,
	) = withContext(Dispatchers.IO) {
		if (id <= 0) return@withContext
		runCatching {
			ConnectionPool.getConnection().use { conn ->
				conn.prepareStatement(
					"""
					UPDATE llm_access_records
					SET status = ?, prompt_tokens = ?, completion_tokens = ?, total_tokens = ?, error_message = ?, completed_at = ?
					WHERE id = ?
					""".trimIndent()
				).use { stmt ->
					stmt.setString(1, status)
					stmt.setObject(2, usage?.promptTokens)
					stmt.setObject(3, usage?.completionTokens)
					stmt.setObject(4, usage?.totalTokens)
					stmt.setString(5, errorMessage?.take(512))
					stmt.setLong(6, System.currentTimeMillis())
					stmt.setLong(7, id)
					stmt.executeUpdate()
				}
			}
		}
	}

	private fun parseUsage(body: String): Usage? = runCatching {
		val obj = jsonParser.parse(body).asJsonObject
		val usage = obj.getAsJsonObject("usage") ?: return null
		Usage(
			usage.get("prompt_tokens")?.asInt,
			usage.get("completion_tokens")?.asInt,
			usage.get("total_tokens")?.asInt,
		)
	}.getOrNull()

	private fun errorJson(code: String, message: String): String {
		return JsonObject().apply {
			add("error", JsonObject().apply {
				addProperty("message", message)
				addProperty("type", code)
				addProperty("code", code)
			})
		}.toString()
	}

	private fun quote(value: String): String = JsonObject().apply { addProperty("value", value) }.get("value").toString()
	private fun flowOfText(text: String): Flow<String> = flow { emit(text) }

	private data class NormalizedRequest(val model: String, val body: String, val userQuestion: String)
	private data class LLMRequester(
		val uid: Long,
		val name: String,
		val source: String,
		val groupId: Long? = null,
		val conversationSource: String = source,
	) {
		fun conversationKey(): String = listOfNotNull(conversationSource, groupId?.toString(), uid.toString()).joinToString(":")
	}
	private data class ToolCall(val id: String, val name: String, val arguments: String?)
	private data class Usage(val promptTokens: Int?, val completionTokens: Int?, val totalTokens: Int?)
}

data class LLMNonStreamResult(val status: Int, val body: String)
data class LLMStreamResult(val status: Int, val chunks: Flow<String>)
