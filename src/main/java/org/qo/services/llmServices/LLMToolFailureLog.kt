package org.qo.services.llmServices

import com.google.gson.GsonBuilder
import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.google.gson.JsonPrimitive
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption.APPEND
import java.nio.file.StandardOpenOption.CREATE
import java.time.Instant

/** Readable, append-only diagnostics shared by tool parsing and execution failures. */
internal class LLMToolFailureLog(
	private val path: Path = Path.of("data", "llm", "toolcall-failure.log"),
) {
	fun recordInvalidToolCall(
		responseBody: String,
		context: LLMToolContext,
		requestSource: String,
		provider: String,
		model: String,
		round: Int,
	) {
		val content = runCatching {
			JsonParser.parseString(responseBody).asJsonObject.getAsJsonArray("choices")[0]
				.asJsonObject.getAsJsonObject("message").get("content")
				.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }?.asString
		}.getOrNull()
		record(
			context = context,
			stage = "parse",
			error = "invalid_tool_call",
			message = "LLM 输出了无法解析的工具调用",
			requestSource = requestSource,
			provider = provider,
			model = model,
			round = round,
			details = linkedMapOf("upstream_response" to responseBody).apply {
				content?.let { put("assistant_content", it) }
			},
		)
	}

	fun record(
		context: LLMToolContext,
		stage: String,
		error: String,
		message: String,
		details: Map<String, String>,
		tool: String? = null,
		requestSource: String? = null,
		provider: String? = null,
		model: String? = null,
		round: Int? = null,
	) {
		runCatching {
			val time = Instant.now().toString()
			val metadata = JsonObject().apply {
				addProperty("time", time)
				addProperty("stage", stage)
				addProperty("error", error)
				addProperty("message", message)
				addProperty("tool", tool)
				addProperty("source", context.source)
				addProperty("request_source", requestSource)
				addProperty("provider", provider)
				addProperty("model", model)
				addProperty("round", round)
				addProperty("group_id", context.groupId)
				addProperty("uid", context.uid)
				addProperty("name", context.name)
				addProperty("message_id", context.currentMessageId)
				addProperty("conversation_key", context.conversationKey)
			}
			val entry = buildString {
				appendLine("===== LLM TOOL FAILURE [$time] =====")
				appendLine(gson.toJson(redactJson(metadata)))
				for ((label, body) in details) {
					appendLine("--- $label ---")
					val json = if (label == "assistant_content") null else runCatching { JsonParser.parseString(body) }.getOrNull()
					val formatted = if (json == null) redactText(body) else gson.toJson(redactJson(json))
					formatted.lineSequence().forEach { append("  ").appendLine(it) }
				}
				appendLine("===== END LLM TOOL FAILURE =====")
				appendLine()
			}
			// Keep console output compact; the file contains the complete diagnostic sections.
			println("[LLMTool] failure ${redactJson(metadata)} log=$path")
			synchronized(lock) {
				path.parent?.let { Files.createDirectories(it) }
				Files.writeString(path, entry, StandardCharsets.UTF_8, CREATE, APPEND)
			}
		}.onFailure {
			System.err.println("[LLMTool] failed to write failure log: ${it.javaClass.name}")
		}
	}

	private fun redactJson(value: JsonElement): JsonElement = when {
		value.isJsonObject -> value.asJsonObject.deepCopy().apply {
			for ((key, child) in entrySet().toList()) {
				add(key, if (key.lowercase() in sensitiveKeys) JsonPrimitive("<redacted>") else redactJson(child))
			}
		}
		value.isJsonArray -> JsonArray().apply { value.asJsonArray.forEach { add(redactJson(it)) } }
		value.isJsonPrimitive && value.asJsonPrimitive.isString -> JsonPrimitive(redactText(value.asString))
		else -> value
	}

	private fun redactText(value: String): String = value
		.replace(bearerPattern, "Bearer <redacted>")
		.replace(dataPattern, "data:<redacted>")
		.replace(secretPattern) { "${it.groupValues[1]}\"<redacted>\"" }

	private companion object {
		val lock = Any()
		val gson = GsonBuilder().disableHtmlEscaping().serializeNulls().setPrettyPrinting().create()
		val sensitiveKeys = setOf("authorization", "api_key", "apikey", "api_token", "apitoken", "access_token", "token", "password", "cookie", "set-cookie")
		val bearerPattern = Regex("""(?i)\bBearer\s+[a-zA-Z0-9._~+/=-]+""")
		val dataPattern = Regex("""data:[a-zA-Z0-9.+/-]+;base64,[a-zA-Z0-9+/=_-]+""")
		val secretPattern = Regex("""(?i)("(?:authorization|api_key|apikey|api_token|apitoken|access_token|token|password|cookie|set-cookie)"\s*:\s*)"(?:\\.|[^"\\])*"""")
	}
}
