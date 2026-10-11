package org.qo.services.llmServices

import com.google.gson.GsonBuilder
import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonParser
import com.google.gson.JsonPrimitive

/** Shared redaction for durable LLM diagnostics; null fields remain available for debugging. */
internal object LLMLogRedaction {
	private val gson = GsonBuilder().disableHtmlEscaping().serializeNulls().setPrettyPrinting().create()
	private val sensitiveKeys = setOf(
		"authorization", "api_key", "apikey", "api_token", "apitoken", "access_token", "refresh_token",
		"token", "password", "client_secret", "cookie", "set-cookie", "x-api-key",
	)
	private val bearerPattern = Regex("""(?i)\bBearer\s+[a-zA-Z0-9._~+/=-]+""")
	private val dataPattern = Regex("""data:[a-zA-Z0-9.+/-]+;base64,[a-zA-Z0-9+/=_-]+""")
	private val secretPattern = Regex("""(?i)("(?:authorization|api_key|apikey|api_token|apitoken|access_token|refresh_token|token|password|client_secret|cookie|set-cookie|x-api-key)"\s*:\s*)"(?:\\.|[^"\\])*"""")

	fun formatBody(body: String, parseJson: Boolean = true): String {
		val json = if (parseJson) runCatching { JsonParser.parseString(body) }.getOrNull() else null
		return if (json == null) redactText(body) else gson.toJson(redactJson(json))
	}

	fun redactJson(value: JsonElement): JsonElement = when {
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
}
