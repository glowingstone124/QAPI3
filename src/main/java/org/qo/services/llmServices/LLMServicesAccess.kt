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
			database.execute(
				"""
                CREATE TABLE IF NOT EXISTS llm_access_records (
				   id BIGINT AUTO_INCREMENT PRIMARY KEY,
				   uid BIGINT NOT NULL,
				   username VARCHAR(128) NOT NULL,
				   source VARCHAR(32) NOT NULL DEFAULT 'unknown',
				   source_identity VARCHAR(128) NULL,
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
			ensureAccessRecordColumn("source", "VARCHAR(32) NOT NULL DEFAULT 'unknown' AFTER username")
			ensureAccessRecordColumn("source_identity", "VARCHAR(128) NULL AFTER source")
			accessRecordSchemaReady.complete(Unit)
		} catch (error: Exception) {
			accessRecordSchemaReady.completeExceptionally(error)
			println("LLM access record table init failed: ${error.message}")
		}
	}
}

internal suspend fun LLMServices.ensureAccessRecordColumn(name: String, definition: String) {
	try {
		database.execute("ALTER TABLE llm_access_records ADD COLUMN $name $definition")
	} catch (error: Exception) {
		if (!error.message.orEmpty().contains("duplicate", ignoreCase = true)) {
			println("LLM access record column migration failed for $name: ${error.message}")
		}
	}
}

internal suspend fun LLMServices.insertAccessRecord(principal: LLMPrincipal, model: String, stream: Boolean): Long {
	val requestId = "chatcmpl-qo-${UUID.randomUUID()}"
	return try {
		awaitAccessRecordSchema()
		database.inTransaction {
			database.execute(
				"""
				INSERT INTO llm_access_records(
					uid, username, source, source_identity, request_id, model, stream, status, created_at
				)
				VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                """.trimIndent(),
				listOf(
					principal.qqUid,
					principal.displayName.take(128),
					principal.source.value,
					principal.sourceIdentity.take(128),
					requestId,
					model.take(128),
					stream,
					"started",
					System.currentTimeMillis()
				),
			)
			database.one(
				"SELECT id FROM llm_access_records WHERE request_id = ? ORDER BY id DESC LIMIT 1",
				listOf(requestId),
			) { row ->
				row.get("id", java.lang.Long::class.java)!!.toLong()
			} ?: -1L
		}
	} catch (_: Exception) {
		-1L
	}
}

internal suspend fun LLMServices.updateAccessRecord(
	id: Long,
	status: String,
	usage: LLMServices.Usage? = null,
	errorMessage: String? = null,
) {
	usage?.let { logPromptCacheUsage("chat", it) }
	if (id <= 0) return
	try {
		awaitAccessRecordSchema()
		database.execute(
			"""
             UPDATE llm_access_records
             SET status = ?, prompt_tokens = ?, completion_tokens = ?, total_tokens = ?, error_message = ?, completed_at = ?
             WHERE id = ?
             """.trimIndent(),
			listOf(
				status,
				usage?.promptTokens,
				usage?.completionTokens,
				usage?.totalTokens,
				errorMessage?.take(512),
				System.currentTimeMillis(),
				id,
			),
		)
	} catch (_: Exception) {
		// Access-record persistence must not replace the upstream response with a database error.
	}
}

internal fun LLMServices.parseUsage(body: String): LLMServices.Usage? = runCatching {
	val obj = jsonParser.parse(body).asJsonObject
	val usage = obj.getAsJsonObject("usage") ?: return null
	val promptTokens = usage.get("prompt_tokens")?.asInt ?: usage.get("input_tokens")?.asInt
	val cachedTokens = usage.get("prompt_cache_hit_tokens")?.asInt
		?: usage.getAsJsonObject("prompt_tokens_details")?.get("cached_tokens")?.asInt
		?: usage.getAsJsonObject("input_tokens_details")?.get("cached_tokens")?.asInt
	LLMServices.Usage(
		promptTokens,
		usage.get("completion_tokens")?.asInt ?: usage.get("output_tokens")?.asInt,
		usage.get("total_tokens")?.asInt,
		cachedTokens,
		usage.get("prompt_cache_miss_tokens")?.asInt
			?: promptTokens?.let { total -> cachedTokens?.let { (total - it).coerceAtLeast(0) } },
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
