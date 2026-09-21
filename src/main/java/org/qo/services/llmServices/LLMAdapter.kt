package org.qo.services.llmServices

import com.google.gson.JsonArray
import com.google.gson.JsonObject

internal data class LLMAdapterRequest(
	val chat: JsonObject,
	val functionTools: JsonArray,
	val reasoningEffort: LLMReasoningEffort,
	val stream: Boolean = false,
	val webSearch: Boolean = true,
	val thinkingMode: String = "enabled",
)

/**
 * Converts the normalized Chat Completions request into a provider protocol request.
 *
 * Response parsing and protocol-specific tool continuation remain on the concrete
 * implementations because their native response shapes are not interchangeable.
 */
internal interface LLMAdapter {
	val protocol: LLMProtocol

	fun adapt(request: LLMAdapterRequest): JsonObject
}

internal object LLMAdapterRegistry {
	private val adapters = listOf(
		LLMWebSearchAdapter,
		LLMResponsesAdapter,
		LLMAnthropicAdapter,
		LLMCommandCodeAdapter,
	).also { registered ->
		val protocols = registered.map(LLMAdapter::protocol)
		require(protocols.size == protocols.toSet().size) { "Duplicate LLM adapter protocol registration" }
		require(protocols.toSet() == LLMProtocol.entries.toSet()) { "Not every LLM protocol has an adapter" }
	}.associateBy(LLMAdapter::protocol)

	fun forProtocol(protocol: LLMProtocol): LLMAdapter =
		adapters[protocol] ?: error("No LLM adapter registered for protocol '${protocol.wireValue}'")
}
