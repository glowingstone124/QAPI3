package org.qo.services.llmServices

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
import org.qo.redis.DatabaseType
import org.qo.redis.Redis
import org.qo.services.loginService.AuthorityNeededServicesImpl
import org.springframework.stereotype.Service
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID

@Service
class LLMServices(
	private val authorityNeededServicesImpl: AuthorityNeededServicesImpl,
	private val nodes: Nodes,
) {
	private val redis = Redis()
	private val jsonParser = JsonParser()
	private val upstreamUrl = System.getenv("LLM_API_URL") ?: "https://api.deepseek.com/v1/chat/completions"
	private val upstreamModel = System.getenv("LLM_DEFAULT_MODEL") ?: "deepseek-chat"
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
		val request = normalizeRequest(body, false)
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
			val response = client.post(upstreamUrl) {
				header(HttpHeaders.Authorization, "Bearer $upstreamToken")
				contentType(ContentType.Application.Json)
				setBody(request.body)
			}
			val text = response.bodyAsText()
			val usage = parseUsage(text)
			updateAccessRecord(requestId, if (response.status.isSuccess()) "completed" else "failed", usage, text.take(512))
			LLMNonStreamResult(response.status.value, text)
		} catch (e: Exception) {
			updateAccessRecord(requestId, "failed", errorMessage = e.message)
			LLMNonStreamResult(502, errorJson("upstream_error", e.message ?: "LLM 上游请求失败"))
		}
	}

	suspend fun streamChat(body: String, token: String): LLMStreamResult {
		val user = authenticate(token) ?: return LLMStreamResult(401, flowOfText(errorJson("invalid_token", "权限验证失败")))
		val request = normalizeRequest(body, true)
		val requestId = insertAccessRecord(user.uid, user.username, request.model, true)
		if (!reserveRequest(token)) {
			updateAccessRecord(requestId, "rejected", errorMessage = "duplicate request")
			return LLMStreamResult(429, flowOfText(errorJson("rate_limited", "请求过于频繁")))
		}
		if (upstreamToken.isBlank()) {
			updateAccessRecord(requestId, "failed", errorMessage = "missing upstream token")
			return LLMStreamResult(500, flowOfText(errorJson("server_error", "LLM 上游令牌未配置")))
		}

		return LLMStreamResult(200, streamFromUpstream(request.body, requestId))
	}

	suspend fun completeBotChat(body: String, token: String, qqUid: Long): LLMNonStreamResult {
		if (!authenticateServerToken(token)) {
			return LLMNonStreamResult(401, errorJson("invalid_token", "Bot token 验证失败"))
		}
		val request = normalizeRequest(body, false)
		val requestId = insertAccessRecord(qqUid, "qq:$qqUid", request.model, false)
		if (!reserveRequest("bot:$qqUid")) {
			updateAccessRecord(requestId, "rejected", errorMessage = "duplicate request")
			return LLMNonStreamResult(429, errorJson("rate_limited", "请求过于频繁"))
		}
		if (upstreamToken.isBlank()) {
			updateAccessRecord(requestId, "failed", errorMessage = "missing upstream token")
			return LLMNonStreamResult(500, errorJson("server_error", "LLM 上游令牌未配置"))
		}

		return try {
			val response = client.post(upstreamUrl) {
				header(HttpHeaders.Authorization, "Bearer $upstreamToken")
				contentType(ContentType.Application.Json)
				setBody(request.body)
			}
			val text = response.bodyAsText()
			val usage = parseUsage(text)
			updateAccessRecord(requestId, if (response.status.isSuccess()) "completed" else "failed", usage, text.take(512))
			LLMNonStreamResult(response.status.value, text)
		} catch (e: Exception) {
			updateAccessRecord(requestId, "failed", errorMessage = e.message)
			LLMNonStreamResult(502, errorJson("upstream_error", e.message ?: "LLM 上游请求失败"))
		}
	}

	private fun authenticateServerToken(token: String): Boolean = nodes.getServerFromToken(token) >= 0

	private fun streamFromUpstream(body: String, requestId: Long): Flow<String> = flow {
		try {
			val response = client.post(upstreamUrl) {
				header(HttpHeaders.Authorization, "Bearer $upstreamToken")
				contentType(ContentType.Application.Json)
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

	private fun normalizeRequest(body: String, stream: Boolean): NormalizedRequest {
		val obj = jsonParser.parse(body).asJsonObject
		if (!obj.has("model") || obj.get("model").asString.isBlank()) {
			obj.addProperty("model", upstreamModel)
		}
		obj.addProperty("stream", stream)
		if (!obj.has("messages") || !obj.get("messages").isJsonArray) {
			throw IllegalArgumentException("OpenAI chat completions request must contain messages array")
		}
		return NormalizedRequest(obj.get("model").asString, obj.toString())
	}

	private fun reserveRequest(token: String): Boolean {
		return redis.setIfAbsentWithExpire("llm:req:$token", "1", DatabaseType.QO_ASSISTANT_DATABASE.value, 2)
			.ignoreException() ?: true
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

	private data class NormalizedRequest(val model: String, val body: String)
	private data class Usage(val promptTokens: Int?, val completionTokens: Int?, val totalTokens: Int?)
}

data class LLMNonStreamResult(val status: Int, val body: String)
data class LLMStreamResult(val status: Int, val chunks: Flow<String>)
